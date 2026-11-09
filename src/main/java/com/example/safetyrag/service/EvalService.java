package com.example.safetyrag.service;

import com.example.safetyrag.config.AppProperties;
import com.example.safetyrag.rag.HybridRetriever;
import com.example.safetyrag.rag.RetrievedChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索质量评测：用一批固定问题跑召回，输出可比较的指标。
 *
 * <p>为什么要有这个？调 chunk-size、改 min-score、开关混合检索，全靠"感觉好像好了
 * 一点"是没有说服力的——而且很容易改坏：BM25 那一路曾经把"今天食堂吃什么"凑出
 * 几条食堂条款，正是靠负样本评测才暴露出来。这个服务把"检索到底行不行"变成数字。</p>
 *
 * <p>两个刻意的设计：</p>
 * <ol>
 *   <li><b>同一个问题跑两遍</b>：一遍强制纯向量（{@code hybridOverride=false}），
 *       一遍按配置走混合。两条结果并排放，才能看出混合到底带来了多少提升，
 *       而不是只有一个孤零零的绝对分数。</li>
 *   <li><b>正负样本分开算指标</b>。正样本看 Hit@1 / Hit@K / MRR；负样本看
 *       "该拒答时是否真的没召回"。把两者混成一个准确率会掩盖问题——一个能把
 *       拒答率做到 100% 的系统，只要什么都不召回就行了。</li>
 * </ol>
 *
 * <p>评测集默认读 classpath 的 {@code eval/default-eval-set.json}；
 * 若 {@code data/eval-set.json} 存在则优先用它，方便自行增删问题而不必重新打包。</p>
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private static final String DEFAULT_SET = "eval/default-eval-set.json";
    private static final String OVERRIDE_SET = "eval-set.json";

    private final HybridRetriever retriever;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public EvalService(HybridRetriever retriever, AppProperties properties, ObjectMapper objectMapper) {
        this.retriever = retriever;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 一条评测用例。 */
    public record EvalCase(String id, String question, List<String> expectKeywords,
                           String expectDoc, boolean negative, String note) {
    }

    /** 评测集文件结构。 */
    public record EvalSet(String name, String description, List<EvalCase> cases) {
    }

    /** 单条用例在某种检索模式下的表现。 */
    public record Mode(boolean hit, int rank, int hits, long ms) {
    }

    /**
     * 逐条结果：同一问题的纯向量与混合两种表现并排。
     *
     * @param topScore 向量路 top1 的原始相似度（不受阈值约束）。调 min-score 时
     *                 全靠这一列——正负样本的分数若有大面积重叠，说明单靠阈值
     *                 分不开，得换判据（比如相对尖峰）。
     */
    public record CaseOutcome(String id, String question, boolean negative, String expect,
                              Double topScore,
                              boolean vectorHit, int vectorRank, int vectorHits, long vectorMs,
                              boolean hybridHit, int hybridRank, int hybridHits, long hybridMs) {
    }

    /** 某种模式的汇总指标。 */
    public record ModeSummary(String mode, String label, int positiveTotal,
                              int hitAt1, int hitAtK, double mrr,
                              int negativeTotal, int negativeCorrect,
                              double avgMs) {
    }

    /** 完整评测报告。 */
    public record EvalReport(String setName, String description, int topK, int total,
                             long totalMs, List<ModeSummary> summaries, List<CaseOutcome> cases) {
    }

    public EvalReport run(Integer topKRequested, List<String> docIdsRequested) {
        if (properties.demoMode()) {
            throw new IllegalStateException(
                    "演示模式使用哈希向量，不具备语义判别力，评测结果没有参考价值。"
                            + "请配置真实向量模型并关闭 APP_DEMO_MODE 后再评测。");
        }
        EvalSet set = loadSet();
        int topK = topKRequested == null ? 5 : Math.max(1, Math.min(topKRequested, 20));
        Set<String> docIds = docIdsRequested == null
                ? Set.of() : new HashSet<>(docIdsRequested);

        List<CaseOutcome> outcomes = new ArrayList<>();
        long startedAt = System.currentTimeMillis();
        for (EvalCase c : set.cases()) {
            Mode vector = runOne(c, topK, docIds, Boolean.FALSE);
            Mode hybrid = runOne(c, topK, docIds, null);
            Double topScore = retriever.vectorTopScore(c.question(), docIds);
            outcomes.add(new CaseOutcome(c.id(), c.question(), c.negative(), expectText(c),
                    topScore == null ? null : Math.round(topScore * 10000.0) / 10000.0,
                    vector.hit(), vector.rank(), vector.hits(), vector.ms(),
                    hybrid.hit(), hybrid.rank(), hybrid.hits(), hybrid.ms()));
        }
        long totalMs = System.currentTimeMillis() - startedAt;
        log.info("评测完成：{} 条用例，耗时 {} ms", outcomes.size(), totalMs);
        // 标签带上当前融合策略：同一张表在不同配置下跑出来的数字才能对上号，
        // 否则改完 fusion 再跑一次，两张表长得一模一样，根本分不清哪张是改前的。
        String hybridLabel = properties.retrieval().useRrf()
                ? "混合检索（RRF）" : "混合检索（加权）";
        return new EvalReport(set.name(), set.description(), topK, outcomes.size(), totalMs,
                List.of(summarize("vector", "纯向量", outcomes, true),
                        summarize("hybrid", hybridLabel, outcomes, false)),
                outcomes);
    }

    private Mode runOne(EvalCase c, int topK, Set<String> docIds, Boolean hybridOverride) {
        long startedAt = System.nanoTime();
        List<RetrievedChunk> hits = retriever.retrieve(
                new HybridRetriever.Options(c.question(), topK, docIds, hybridOverride));
        long ms = (System.nanoTime() - startedAt) / 1_000_000;
        if (c.negative()) {
            // 负样本的"正确"= 没有召回任何东西
            return new Mode(hits.isEmpty(), 0, hits.size(), ms);
        }
        int rank = rankOf(c, hits);
        return new Mode(rank > 0, rank, hits.size(), ms);
    }

    /**
     * 第一个命中位置（1 起）；0 表示前 topK 条都没命中。
     *
     * <p>命中只认<b>关键词</b>，不强制核对文档来源。原因很实际：同一句话在
     * 不同规范里可能都成立——问「剪刀撑与地面夹角」，答案既可能出自 JGJ59
     * （检查标准）也可能出自 JGJ130（脚手架规范），后者才是原始条文。
     * 早期版本要求 docName 必须等于 expectDoc，结果把召回到正确原文的那条
     * 判成"未命中"，评测分数比实际能力低了一截。expectDoc 现在只作为
     * 报告里的"期望来源"提示，不参与判定。</p>
     *
     * <p>匹配时把正文和关键词的空白都去掉再比：PDF 解析出来的条款常带断行和
     * 多余空格（"安全 帽"），直接 contains 会漏判。</p>
     */
    private static int rankOf(EvalCase c, List<RetrievedChunk> hits) {
        List<String> keywords = c.expectKeywords() == null ? List.of() : c.expectKeywords();
        if (keywords.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < hits.size(); i++) {
            String text = compact(hits.get(i).text());
            for (String keyword : keywords) {
                if (text.contains(compact(keyword))) {
                    return i + 1;
                }
            }
        }
        return 0;
    }

    private static ModeSummary summarize(String mode, String label,
                                         List<CaseOutcome> outcomes, boolean vector) {
        int positive = 0;
        int hit1 = 0;
        int hitK = 0;
        int negativeTotal = 0;
        int negativeCorrect = 0;
        double mrrSum = 0;
        long msSum = 0;
        for (CaseOutcome o : outcomes) {
            boolean hit = vector ? o.vectorHit() : o.hybridHit();
            int rank = vector ? o.vectorRank() : o.hybridRank();
            msSum += vector ? o.vectorMs() : o.hybridMs();
            if (o.negative()) {
                negativeTotal++;
                if (hit) {
                    negativeCorrect++;
                }
            } else {
                positive++;
                if (rank == 1) {
                    hit1++;
                }
                if (rank > 0) {
                    hitK++;
                    mrrSum += 1.0 / rank;
                }
            }
        }
        double mrr = positive == 0 ? 0 : Math.round(mrrSum / positive * 1000.0) / 1000.0;
        double avgMs = outcomes.isEmpty() ? 0
                : Math.round((double) msSum / outcomes.size() * 10.0) / 10.0;
        return new ModeSummary(mode, label, positive, hit1, hitK, mrr,
                negativeTotal, negativeCorrect, avgMs);
    }

    private static String expectText(EvalCase c) {
        if (c.negative()) {
            return "（应拒答）";
        }
        String keywords = c.expectKeywords() == null ? "" : String.join(" / ", c.expectKeywords());
        return c.expectDoc() == null || c.expectDoc().isBlank()
                ? keywords : c.expectDoc() + " · " + keywords;
    }

    /** 优先读 data/eval-set.json，其次读 classpath 内置集。 */
    private EvalSet loadSet() {
        Path override = Path.of(properties.dataDirOrDefault(), OVERRIDE_SET);
        try {
            if (Files.isReadable(override)) {
                log.info("使用自定义评测集：{}", override.toAbsolutePath());
                return objectMapper.readValue(override.toFile(), EvalSet.class);
            }
            try (InputStream in = new ClassPathResource(DEFAULT_SET).getInputStream()) {
                return objectMapper.readValue(in, EvalSet.class);
            }
        } catch (Exception e) {
            throw new IllegalStateException("评测集读取失败：" + e.getMessage(), e);
        }
    }

    private static String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }
}
