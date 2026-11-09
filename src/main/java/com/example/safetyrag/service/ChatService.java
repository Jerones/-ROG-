package com.example.safetyrag.service;

import com.example.safetyrag.config.AppProperties;
import com.example.safetyrag.rag.HybridRetriever;
import com.example.safetyrag.rag.QuestionRewriter;
import com.example.safetyrag.rag.RetrievedChunk;
import com.example.safetyrag.web.dto.ChatRequest;
import com.example.safetyrag.web.dto.ChatResponse;
import com.example.safetyrag.web.dto.CitationDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 问答服务：RAG 的"生成"环节。
 *
 * <p>为什么在这里手写组装 Prompt，而不是直接用 QuestionAnswerAdvisor？
 * Advisor 是把"检索→拼上下文"封装好的一键方案，省事但黑盒；课设答辩时
 * 老师通常会追问"上下文怎么拼的、怎么防编造"，手写一遍链路更讲得清。
 * 本类就是完整的手写版（Advisor 的用法在 README 里有对照说明）：</p>
 * <ol>
 * <li>先用 {@link HybridRetriever} 混合检索拿候选条款；</li>
 * <li>无召回（空结果）→ 直接返回"知识库中未找到相关规范"，绝不让模型编；</li>
 * <li>有召回 → 把条款原文按 [1][2]… 编号拼进 Prompt，要求模型只依据
 * 原文回答并标注引用编号；</li>
 * <li>返回回答正文 + 结构化引用列表（文档名/条款/片段/分数），前端渲染
 * 为引用溯源卡片。</li>
 * </ol>
 *
 * <p>演示模式（app.demo-mode=true）下不调模型，改用"抽取式回答"：
 * 直接把召回条款原文按相关度拼成回答并标注出处，保证无 key 也能演示。</p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 无召回时的固定回复（与前端约定：found=false）。 */
    public static final String NOT_FOUND_MESSAGE =
            "知识库中未找到相关规范。建议换一种问法（例如使用规范中的术语，如「安全帽」、「高处作业」），"
                    + "或先在知识库菜单中上传对应的规范文档。";

    /** 连接期失败的重试窗口：超过这个时间再重试，用户只会白等，不如直接降级。 */
    private static final long RETRY_WINDOW_MS = 8_000L;

    private static final String SYSTEM_PROMPT = """
            你是施工安全规范问答助手。必须遵守以下规则：
            1. 只能依据用户消息中【规范原文】给出的内容回答，不得编造规范条文；
            2. 每个结论后标注来源编号，如 [1]、[2]，编号与【规范原文】的编号对应；
            3. 如果【规范原文】不足以回答问题，明确说明"规范原文未覆盖该问题"，不要猜测；
            4. 回答用简体中文，条理清晰，可分点陈述。
            """;

    private final HybridRetriever retriever;
    private final QuestionRewriter rewriter;
    private final AppProperties properties;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private volatile ChatClient chatClient;

    public ChatService(HybridRetriever retriever,
                       QuestionRewriter rewriter,
                       AppProperties properties,
                       ObjectProvider<ChatModel> chatModelProvider) {
        this.retriever = retriever;
        this.rewriter = rewriter;
        this.properties = properties;
        this.chatModelProvider = chatModelProvider;
    }

    /**
     * 一次问答的准备结果。
     *
     * @param originalQuestion 用户原话（前端展示、日志用）
     * @param question         实际用于检索与生成的问题：多轮追问时是改写后的独立问句
     * @param rewritten        是否发生了改写（前端据此显示「已结合上下文理解为…」）
     */
    public record Prepared(String originalQuestion, String question, boolean rewritten,
                           List<RetrievedChunk> hits, List<CitationDto> citations) {
    }

    /**
     * 准备一次问答：追问改写 → 混合检索 → 组装引用。
     * 流式与非流式共用这一步，保证两条链路的召回结果完全一致。
     */
    public Prepared prepare(ChatRequest request) {
        QuestionRewriter.Result rewritten = rewriter.enrich(
                request.question(), request.historyOrEmpty());
        int topK = request.topK() == null ? 5 : Math.max(1, Math.min(request.topK(), 20));
        List<RetrievedChunk> hits = retriever.retrieve(new HybridRetriever.Options(
                rewritten.question(), topK, new HashSet<>(request.docIdsOrEmpty()), null));
        List<CitationDto> citations = new ArrayList<>();
        for (RetrievedChunk hit : hits) {
            // 展示给用户的"相似度"必须是语义相似度本身，不能是融合分：
            // RRF 的融合分量级是 1/(60+rank)，用户会看到 0.016 这种没法解释的值。
            // BM25 单独引入的块没有向量分，退回融合分（此时它就是排序依据）。
            double shown = hit.vectorScore() > 0 ? hit.vectorScore() : hit.score();
            citations.add(new CitationDto(
                    hit.docName(),
                    hit.clause(),
                    hit.page(),
                    truncate(hit.text(), 300),
                    Math.round(shown * 10000.0) / 10000.0));
        }
        return new Prepared(request.question(), rewritten.question(),
                rewritten.rewritten(), hits, citations);
    }

    /** 非流式问答。 */
    public ChatResponse ask(ChatRequest request) {
        Prepared prepared = prepare(request);
        if (prepared.hits().isEmpty()) {
            return new ChatResponse(NOT_FOUND_MESSAGE, false, properties.demoMode(), List.of());
        }
        String answer;
        if (properties.demoMode()) {
            answer = extractiveAnswer(prepared.question(), prepared.hits());
        } else {
            answer = callLlm(prepared.question(), prepared.hits());
        }
        return new ChatResponse(answer, true, properties.demoMode(), prepared.citations());
    }

    /**
     * 流式问答的 token 流。
     * 演示模式/抽取式：把整段回答按句切开发射，模拟逐字输出；
     * 真实模式：透传 ChatClient 的流式输出（stream().content()）。
     *
     * <p>真实模式下必须做三层保护，否则用户会直接看到 500 或莫名其妙的抽取式回答：</p>
     * <ol>
     *   <li>模型调用是异步的，异常在订阅之后才发生，外面那层 try/catch 接不住
     *       （这是曾经"对话请求失败 500"的真实原因）；</li>
     *   <li>百炼的 keep-alive 连接会被上游提前关闭，连接池复用到失效连接即
     *       报 {@code Connection reset}。连接期就失败的（还没吐出任何 token、
     *       且耗时很短）先原地重试一次；</li>
     *   <li>原地重试仍失败的，再换一条链路——用非流式 {@code call()} 补答
     *       （走 RestClient 的连接池，与 WebClient 不是同一批连接），补到就把
     *       这段文字逐块推给前端，效果和流式一样；只有它也不通，才退回抽取式。
     *       ——这一步是必要的：本机到百炼的 reset 是环境级偶发故障，
     *       换链路重试的成功率明显高于原地重试。</li>
     * </ol>
     */
    public Flux<String> streamTokens(Prepared prepared) {
        List<RetrievedChunk> hits = prepared.hits();
        String question = prepared.question();
        if (hits.isEmpty()) {
            return Flux.just(NOT_FOUND_MESSAGE);
        }
        if (properties.demoMode()) {
            return chunkedFlux(extractiveAnswer(question, hits), 25);
        }

        Flux<String> model;
        try {
            model = chatClient().prompt()
                    .system(SYSTEM_PROMPT)
                    .user(buildUserPrompt(question, hits))
                    .stream()
                    .content();
        } catch (Exception e) {
            log.warn("流式调用模型失败，降级为抽取式回答：{}", e.getMessage());
            return chunkedFlux(extractiveAnswer(question, hits), 25);
        }

        // 记录"是否已经开始输出"与"发起时间"，用于判断这次失败值不值得重试
        AtomicBoolean emitted = new AtomicBoolean(false);
        long startedAt = System.currentTimeMillis();
        return model
                .doOnNext(token -> emitted.set(true))
                .retryWhen(Retry.backoff(1, Duration.ofMillis(600))
                        .filter(e -> !emitted.get() && isTransient(e)
                                && System.currentTimeMillis() - startedAt < RETRY_WINDOW_MS)
                        .doBeforeRetry(signal -> log.warn("模型流式连接失败，重试一次：{}",
                                signal.failure().getClass().getSimpleName()))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .timeout(Duration.ofSeconds(120))
                .onErrorResume(e -> {
                    log.warn("模型流式输出中断（已输出={}）：{}", emitted.get(), e.getMessage());
                    if (emitted.get()) {
                        // 已经吐出一部分，再让模型重答一遍会重复内容，直接补原文摘录收尾
                        return chunkedFlux("\n\n（模型连接中断，以下为召回到的规范条款原文）\n\n"
                                + extractiveAnswer(question, hits), 12);
                    }
                    // 还没吐出任何 token：先换一条链路（非流式 call，走 RestClient 的
                    // 连接池与重试策略，与 WebClient 不是同一批连接）补答一次。
                    // 实测 reset 是环境级偶发故障，换链路重试的成功率明显高于原地重试。
                    return Flux.defer(() -> {
                                String answer = callLlmOrNull(question, hits);
                                if (answer != null) {
                                    log.info("非流式补答成功，回答 {} 字", answer.length());
                                    return chunkedFlux(answer, 12);
                                }
                                return chunkedFlux(extractiveAnswer(question, hits), 12);
                            })
                            .subscribeOn(Schedulers.boundedElastic());
                });
    }

    /** 抽取式/补答答案按小节切块，带一点延迟，前端仍是打字机效果。 */
    private Flux<String> chunkedFlux(String answer, long delayMillis) {
        return Flux.fromIterable(splitForStreaming(answer))
                .delayElements(Duration.ofMillis(delayMillis));
    }

    /** 连接类异常才值得重试：HTTP 4xx/5xx 重试也是白搭。 */
    private static boolean isTransient(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.io.IOException || t instanceof java.net.SocketException) {
                return true;
            }
            if (t == t.getCause()) {
                break;
            }
        }
        return false;
    }

    // ---------------- 内部实现 ----------------

    private String callLlm(String question, List<RetrievedChunk> hits) {
        String content = callLlmOrNull(question, hits);
        return content == null ? extractiveAnswer(question, hits) : content;
    }

    /**
     * 调非流式接口拿完整回答；失败/空回答返回 {@code null}，把"要不要降级"的决定权交给调用方。
     * 区分 null 与抽取式答案，是为了让流式那条路能判断"换链路补答是否成功"。
     */
    private String callLlmOrNull(String question, List<RetrievedChunk> hits) {
        try {
            String content = chatClient().prompt()
                    .system(SYSTEM_PROMPT)
                    .user(buildUserPrompt(question, hits))
                    .call()
                    .content();
            return (content == null || content.isBlank()) ? null : content;
        } catch (Exception e) {
            // 模型不可用（key 无效/网络不通）时降级抽取式回答，保证演示不中断
            log.warn("调用大模型失败：{}", e.getMessage());
            return null;
        }
    }

    /** 把召回条款编号拼进用户 Prompt——这是 RAG 防编造的核心一步。 */
    private String buildUserPrompt(String question, List<RetrievedChunk> hits) {
        StringBuilder sb = new StringBuilder("【规范原文】\n");
        for (int i = 0; i < hits.size(); i++) {
            RetrievedChunk hit = hits.get(i);
            sb.append('[').append(i + 1).append("] 《")
                    .append(hit.docName()).append("》")
                    .append(hit.clause());
            if (hit.page() != null) {
                sb.append("（第").append(hit.page()).append("页）");
            }
            sb.append("：\n").append(hit.text()).append("\n\n");
        }
        sb.append("【问题】\n").append(question);
        return sb.toString();
    }

    /** 抽取式回答：直接拼召回原文并标注出处，不做任何"创作"。 */
    private String extractiveAnswer(String question, List<RetrievedChunk> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("【抽取式回答】根据知识库中与\"").append(question)
                .append("\"最相关的规范条款，摘录如下：\n");
        for (int i = 0; i < hits.size(); i++) {
            RetrievedChunk hit = hits.get(i);
            sb.append("\n").append(i + 1).append(". 《").append(hit.docName())
                    .append("》").append(hit.clause()).append("：")
                    .append(truncate(hit.text(), 260));
        }
        sb.append(properties.demoMode()
                ? "\n\n（演示模式下未调用大模型，以上为召回条款原文摘录；"
                + "配置真实 API key 并关闭演示模式后，将由大模型基于这些条款生成回答。）"
                : "\n\n（模型本次未能正常生成回答，以上为召回到的规范条款原文摘录，未经改写。）");
        return sb.toString();
    }

    private ChatClient chatClient() {
        ChatClient local = chatClient;
        if (local == null) {
            synchronized (this) {
                if (chatClient == null) {
                    ChatModel model = chatModelProvider.getObject();
                    chatClient = ChatClient.builder(model).build();
                }
                local = chatClient;
            }
        }
        return local;
    }

    /** 把整段文本切成小片段，模拟打字机效果。 */
    private static List<String> splitForStreaming(String text) {
        List<String> pieces = new ArrayList<>();
        int step = 6;
        for (int i = 0; i < text.length(); i += step) {
            pieces.add(text.substring(i, Math.min(text.length(), i + step)));
        }
        return pieces;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
