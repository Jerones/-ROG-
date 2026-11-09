package com.example.safetyrag.rag;

import com.example.safetyrag.config.AppProperties;
import com.example.safetyrag.web.dto.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 追问改写器：把依赖上下文的追问补全成可独立检索的问句。
 *
 * <p>为什么需要它？RAG 的检索环节只拿到「本轮问题」这一个字符串。用户接着问
 * 「那颜色呢」，向量检索拿到的是孤零零的「那颜色呢」——指代对象丢失，召回必然
 * 跑偏。这一步先用一次轻量模型调用做指代消解，把问题改写成「安全帽的颜色有什么
 * 要求」，再拿改写结果去检索。</p>
 *
 * <p>三个刻意的取舍：</p>
 * <ol>
 *   <li><b>不是每轮都改写</b>。改写要额外一次模型调用（约 1~2 秒），首轮提问和
 *       本身完整的问题没必要付这个代价。只有「问题很短」或「含指代词」且存在
 *       历史时才触发。</li>
 *   <li><b>改写结果只用于检索，不替换展示给用户的原文</b>。前端会显示一行
 *       「已结合上下文理解为」，让用户能看出系统理解偏了没有。</li>
 *   <li><b>失败一律回退原文</b>。改写是锦上添花，不能因为它挂了就让人问不了问题。</li>
 * </ol>
 */
@Component
public class QuestionRewriter {

    private static final Logger log = LoggerFactory.getLogger(QuestionRewriter.class);

    /** 含这些词的问句大概率省略了主语，需要结合历史补全。 */
    private static final Pattern CONTEXT_DEPENDENT = Pattern.compile(
            "它|他们|其|这个|那个|这些|那些|上述|前面|刚才|前者|后者|还有|另外"
                    + "|该规范|该条|该项|该要求|怎么样呢|呢$");

    /** 短于这个字数、且已有历史时，按「追问」处理。 */
    private static final int SHORT_QUESTION_CHARS = 12;

    /** 送给模型的最近历史条数（user+assistant 合计，约 3 轮）。 */
    private static final int MAX_HISTORY_TURNS = 6;

    /** 历史里单条消息的截断长度：助手回答动辄几百字，全塞进去只会拖慢改写。 */
    private static final int MAX_HISTORY_CHARS = 220;

    /** 改写结果超过这个长度视为模型跑题（开始解释而不是改写），回退原文。 */
    private static final int MAX_REWRITE_CHARS = 80;

    private static final String REWRITE_SYSTEM_PROMPT = """
            你是检索查询改写器。任务是根据对话历史，把用户最后一轮提问改写成
            不依赖上下文、可以单独拿去检索规范的问句。
            规则：
            1. 补全指代：把它/这个/该/上述等指代对象替换成具体名称；
            2. 保留施工安全领域的专业术语，不要换同义词，不要扩写内容；
            3. 不要回答问题，不要解释，不要加编号或引号；
            4. 如果最后一轮提问本身已经完整、不依赖上下文，原样输出该提问；
            5. 只输出一行文本。
            """;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final AppProperties properties;
    private volatile ChatClient chatClient;

    public QuestionRewriter(ObjectProvider<ChatModel> chatModelProvider,
                            AppProperties properties) {
        this.chatModelProvider = chatModelProvider;
        this.properties = properties;
    }

    /**
     * @param rewritten 是否真的改写了（false 时 question 与入参相同）
     */
    public record Result(String question, boolean rewritten) {
    }

    /** 判断是否需要改写并执行；任何异常都回退为原问题。 */
    public Result enrich(String question, List<ChatRequest.Turn> history) {
        if (question == null || question.isBlank()) {
            return new Result(question, false);
        }
        if (history == null || history.isEmpty() || properties.demoMode()) {
            // 演示模式没有可用的聊天模型，硬改写只会把问题改坏
            return new Result(question, false);
        }
        if (!looksContextDependent(question)) {
            return new Result(question, false);
        }
        String rewritten = rewrite(question, history);
        if (rewritten == null || rewritten.isBlank()
                || rewritten.length() > MAX_REWRITE_CHARS
                || rewritten.equals(question)) {
            return new Result(question, false);
        }
        return new Result(rewritten, true);
    }

    /** 短问题，或含指代词，才认为它依赖上文。 */
    private static boolean looksContextDependent(String question) {
        String compact = question.replaceAll("\\s+", "");
        if (compact.length() >= SHORT_QUESTION_CHARS) {
            return CONTEXT_DEPENDENT.matcher(compact).find();
        }
        return true;
    }

    private String rewrite(String question, List<ChatRequest.Turn> history) {
        try {
            String content = chatClient().prompt()
                    .system(REWRITE_SYSTEM_PROMPT)
                    .user(buildUserPrompt(question, history))
                    .call()
                    .content();
            return sanitize(content);
        } catch (Exception e) {
            // 改写失败不影响主流程：用原问题检索，最多是这次追问召回差一点
            log.warn("追问改写失败，改用原问题检索：{}", e.getMessage());
            return null;
        }
    }

    private static String buildUserPrompt(String question, List<ChatRequest.Turn> history) {
        StringBuilder sb = new StringBuilder("【对话历史】\n");
        int from = Math.max(0, history.size() - MAX_HISTORY_TURNS);
        for (int i = from; i < history.size(); i++) {
            ChatRequest.Turn turn = history.get(i);
            String role = "assistant".equalsIgnoreCase(turn.role()) ? "助手" : "用户";
            sb.append(role).append('：')
                    .append(truncate(turn.content(), MAX_HISTORY_CHARS))
                    .append('\n');
        }
        sb.append("\n【最后一轮提问】\n").append(question).append('\n');
        sb.append("\n只输出改写后的问句。");
        return sb.toString();
    }

    /**
     * 清理模型输出：去掉引号、前缀、多行说明。
     * 实测模型偶尔会输出「改写后：xxx」或带一对中文引号，直接拿去检索会污染查询。
     */
    private static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip();
        int lineBreak = text.indexOf('\n');
        if (lineBreak > 0) {
            text = text.substring(0, lineBreak).strip();
        }
        text = text.replaceFirst("^(改写后|改写|问题|问句)\\s*[：:]\\s*", "");
        text = text.replaceAll("^[\"'“”‘’「」]+|[\"'“”‘’「」]+$", "").strip();
        return text;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private ChatClient chatClient() {
        ChatClient local = chatClient;
        if (local == null) {
            synchronized (this) {
                if (chatClient == null) {
                    chatClient = ChatClient.builder(chatModelProvider.getObject()).build();
                }
                local = chatClient;
            }
        }
        return local;
    }
}
