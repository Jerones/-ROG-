package com.example.safetyrag.rag;

import com.example.safetyrag.config.AppProperties;
import com.example.safetyrag.service.DocumentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索器：向量检索（语义） + BM25（关键词）两路召回后合并去重。
 *
 * <p>合并策略（可讲解、可做对比实验）：</p>
 * <ol>
 *   <li>向量路：VectorStore.similaritySearch 取 topK×3 个候选，
 *       并按 app.retrieval.min-score 过滤掉低相似度结果——这一步是
 *       "无召回就不回答"的第一道闸；</li>
 *   <li>尖峰门限：过滤后的候选若"top1 与均值几乎一样高"，说明整批都是背景噪声
 *       （中文 embedding 对闲聊问句也给 0.6 分），按无召回处理——这是第二道闸，
 *       专门解决"抬高 min-score 会误伤口语化正样本"的两难；</li>
 *   <li>BM25 路：对内存中的全部块算分，默认只对向量路已召回的候选加权排序
 *       （原始分低于 min-bm25-score 的块直接排除）；是否允许 BM25 单独引入新块
 *       由 {@code bm25-introduce-new}、演示模式与向量库故障共同决定；</li>
 *   <li>融合：默认 RRF（倒数排名融合），{@code score = w_v/(k+rank_v) + w_b/(k+rank_b)}，
 *       只看名次不看分数；配置 {@code fusion: weighted} 可切回线性加权做对照；</li>
 *   <li>按最终分降序取 topK。展示用的"相似度"一律取 vectorScore，不取融合分。</li>
 * </ol>
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;
    private final AppProperties properties;
    /** BM25 只存了 id→文本，合并时需要按块 id 从注册表找回带 metadata 的 Document。 */
    private final DocumentRegistry registry;

    public HybridRetriever(VectorStore vectorStore,
                           Bm25Index bm25Index,
                           AppProperties properties,
                           DocumentRegistry registry) {
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.properties = properties;
        this.registry = registry;
    }

    /**
     * 向量检索的连接重试：与入库侧同一原因——Chroma(uvicorn) 会关闭闲置连接，
     * 连接池复用到失效连接时报 Connection reset。检索是只读操作，重试安全；
     * 重试仍失败才抛出，由调用方降级为纯 BM25。
     */
    private List<Document> similaritySearchWithRetry(SearchRequest request) {
        ResourceAccessException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return vectorStore.similaritySearch(request);
            } catch (ResourceAccessException e) {
                last = e;
                log.warn("向量检索第 {} 次连接失败，重试：{}", attempt, e.getMessage());
                try {
                    Thread.sleep(300L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    /**
     * 一次检索的参数包。
     *
     * @param query          检索问句（多轮场景下是改写后的问句）
     * @param topK           最终返回条数
     * @param docIds         限定文档范围；空集合表示全库
     * @param hybridOverride 强制开关混合检索；{@code null} 表示按 application.yml 配置。
     *                       评测面板靠它跑出「纯向量 vs 混合」同一批问题的对比数据。
     */
    public record Options(String query, int topK, Set<String> docIds, Boolean hybridOverride) {

        public static Options of(String query, int topK) {
            return new Options(query, topK, Set.of(), null);
        }

        public static Options of(String query, int topK, Set<String> docIds) {
            return new Options(query, topK, docIds == null ? Set.of() : docIds, null);
        }
    }

    /**
     * @param query 用户问题
     * @param topK  最终返回条数
     */
    public List<RetrievedChunk> retrieve(String query, int topK) {
        return retrieve(Options.of(query, topK));
    }

    public List<RetrievedChunk> retrieve(Options opts) {
        String query = opts.query();
        int topK = opts.topK();
        Set<String> docIds = opts.docIds() == null ? Set.of() : opts.docIds();
        int candidateN = Math.max(topK * 3, topK);
        AppProperties.Retrieval cfg = properties.retrieval();
        boolean hybrid = opts.hybridOverride() != null ? opts.hybridOverride() : cfg.hybridEnabled();

        // ---------- 第一路：向量检索 ----------
        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();
        Map<String, Double> vectorScores = new LinkedHashMap<>();
        /** 向量路名次（1 起）。RRF 融合只认名次，不认分数。 */
        Map<String, Integer> vectorRank = new LinkedHashMap<>();
        boolean vectorFailed = false;
        try {
            SearchRequest.Builder requestBuilder = SearchRequest.builder()
                    .query(query)
                    .topK(candidateN)
                    .similarityThreshold(0.0); // 阈值过滤在下面手动做，便于统一口径和日志
            // 范围限定下推到向量库：Chroma 会把 filterExpression 转成 where 子句，
            // 先筛元数据再算距离，比「先召回再在应用层丢掉」准得多——
            // 后者在限定范围很小时，topK 条可能被范围外的块占满，看起来像无召回。
            Filter.Expression docFilter = docFilter(docIds);
            if (docFilter != null) {
                requestBuilder.filterExpression(docFilter);
            }
            List<Document> hits = similaritySearchWithRetry(requestBuilder.build());
            if (hits != null) {
                // 不假定向量库一定按相似度降序返回：RRF 完全依赖名次，
                // 名次错了整个融合结果就错了，这里自己排一遍。
                List<Document> sorted = new ArrayList<>(hits);
                sorted.sort(Comparator.comparingDouble(
                        (Document d) -> d.getScore() == null ? 0.0 : d.getScore()).reversed());
                for (Document doc : sorted) {
                    double score = doc.getScore() == null ? 0.0 : doc.getScore();
                    if (score < cfg.minScore()) {
                        continue; // 低相似度视为无召回
                    }
                    vectorScores.put(doc.getId(), score);
                    vectorRank.put(doc.getId(), vectorRank.size() + 1);
                    merged.put(doc.getId(), new RetrievedChunk(doc, score, score, 0.0));
                }
                applySpikeGate(cfg, query, vectorScores, vectorRank, merged);
            }
        } catch (Exception e) {
            // 向量库不可用（如 Chroma 未启动）时不让整个问答崩掉，降级为纯 BM25
            vectorFailed = true;
            log.warn("向量检索失败，降级为纯 BM25 召回：{}", e.getMessage());
        }

        // ---------- 第二路：BM25 关键词检索 ----------
        Map<String, Double> bm25Scores = new LinkedHashMap<>();
        /** BM25 名次（1 起），RRF 融合用。 */
        Map<String, Integer> bm25Rank = new LinkedHashMap<>();
        if (hybrid) {
            // BM25 只认字面、不认语义：问题里只要有一个字出现在块里它就能给出正分，
            // 且分数按本批最大值归一化后必然有 top1（=1.0）。
            //
            // 关键设计：BM25 能不能"引入新块"必须挂在**向量路的结论**上，而不是无条件开放。
            //   · 向量路有召回 → 说明这个问题库里确实有答案，此时让 BM25 补充字面强命中
            //     的新块（精确术语、条文编号、数字指标）才有意义，这是混合检索真正的收益；
            //   · 向量路无召回 → 说明问题多半不在知识库范围内，此时任何 BM25 命中都是
            //     "凑字"，必须一律拒绝，否则问"今天食堂吃什么"也会召回几条食堂条款。
            //
            // 两种例外同样无条件放开（可用性优先）：
            //   1) 演示模式：哈希向量无语义判别力（实测余弦仅 0.02~0.11，全部低于
            //      min-score），向量路必然为空，只能由 BM25 兜底；
            //   2) 向量库调用失败：此时 BM25 是唯一检索手段。
            boolean vectorHasRecall = !vectorScores.isEmpty();
            boolean mayIntroduceNew = vectorFailed
                    || properties.demoMode()
                    || (vectorHasRecall && cfg.bm25IntroduceNewOrDefault());

            // 范围限定在 BM25 这路只能用块 id 集合过滤：BM25 索引是内存里的
            // 全库倒排，没有 docId 维度。文档不多时这个集合规模可以接受。
            Set<String> allowedChunkIds = chunkIdsOf(docIds);

            // 门槛之外的截断可能把合规块挡掉，因此多捞一批候选
            int lookahead = Math.max(candidateN * 4, 50);
            List<Bm25Index.ScoredDoc> bm25Hits = bm25Index.search(query, lookahead);
            double minBm25Score = cfg.minBm25ScoreOrDefault();

            List<Bm25Index.ScoredDoc> admitted = new ArrayList<>();
            for (Bm25Index.ScoredDoc hit : bm25Hits) {
                if (hit.score() < minBm25Score) {
                    continue; // 只蹭到零星字词，分数过低，不参与加权
                }
                if (allowedChunkIds != null && !allowedChunkIds.contains(hit.docId())) {
                    continue; // 范围外的块不参与
                }
                if (!mayIntroduceNew && !vectorScores.containsKey(hit.docId())) {
                    continue; // 默认策略：BM25 不得把向量路未召回的块拉进结果集
                }
                admitted.add(hit);
            }
            if (bm25Hits.size() > admitted.size()) {
                log.debug("BM25 候选 {} 条，剔除 {} 条（分数<{} / 范围外 / 不在向量候选集内，允许引入新块={}）",
                        bm25Hits.size(), bm25Hits.size() - admitted.size(),
                        minBm25Score, mayIntroduceNew);
            }

            // 名次必须按 BM25 原始分排序后再编号，不能沿用索引返回顺序
            admitted.sort(Comparator.comparingDouble(Bm25Index.ScoredDoc::score).reversed());
            double max = admitted.isEmpty() ? 0.0 : admitted.get(0).score();
            for (Bm25Index.ScoredDoc hit : admitted) {
                double normalized = max > 0 ? hit.score() / max : 0.0;
                bm25Scores.put(hit.docId(), normalized);
                bm25Rank.put(hit.docId(), bm25Rank.size() + 1);
                if (!merged.containsKey(hit.docId())) {
                    Document doc = registry.toDocument(hit.docId());
                    if (doc != null) {
                        merged.put(hit.docId(), new RetrievedChunk(doc, 0.0, 0.0, normalized));
                    }
                }
            }
        }

        // ---------- 融合打分 ----------
        boolean rrf = hybrid && cfg.useRrf();
        int rrfK = cfg.rrfKOrDefault();
        List<RetrievedChunk> results = new ArrayList<>();
        for (Map.Entry<String, RetrievedChunk> entry : merged.entrySet()) {
            String id = entry.getKey();
            double vectorScore = vectorScores.getOrDefault(id, 0.0);
            double bm25Score = bm25Scores.getOrDefault(id, 0.0);
            double combined;
            if (!hybrid) {
                combined = vectorScore;
            } else if (rrf) {
                // 倒数排名融合：只取名次，天然免疫两路分数的量纲差异。
                // 某一路未命中时该路贡献为 0，因此"两路都命中"的块依然占优，
                // 但单靠 BM25 名次不够高的块已经不可能压过语义 top1。
                double rv = vectorRank.containsKey(id) ? 1.0 / (rrfK + vectorRank.get(id)) : 0.0;
                double rb = bm25Rank.containsKey(id) ? 1.0 / (rrfK + bm25Rank.get(id)) : 0.0;
                combined = cfg.vectorWeight() * rv + cfg.bm25Weight() * rb;
            } else {
                combined = cfg.vectorWeight() * vectorScore + cfg.bm25Weight() * bm25Score;
            }
            results.add(new RetrievedChunk(entry.getValue().document(), combined, vectorScore, bm25Score));
        }
        results.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        if (results.isEmpty()) {
            log.debug("向量路无达标候选，且 BM25 未引入新块，按无召回处理：query={}", query);
        }

        // ---------- 按最终分截断 ----------
        return results.size() > topK ? new ArrayList<>(results.subList(0, topK)) : results;
    }

    /**
     * 相似度尖峰门限：真命中会在 top1 处跳起来，闲聊问句只会摊出一片差不多高的分。
     *
     * <p>为什么光靠 min-score 不够：实测「今天食堂中午吃什么」这类完全无关的问句，
     * 在 text-embedding-v3 上也能拿到 0.6 以上的余弦相似度——中文 embedding 的
     * 基线本来就高。把 min-score 提到 0.65 以上确实能挡住闲聊，但会把"问得口语化
     * 但库里有答案"的正样本一起挡掉。改成看相对尖峰：{@code top1 - 候选均值}
     * 小于门限就判定整批是背景噪声。这个判据只看分布形状，不受基线漂移影响。</p>
     *
     * <p>候选少于 3 条时不判——样本太少，均值没有统计意义，容易误杀。</p>
     */
    private void applySpikeGate(AppProperties.Retrieval cfg, String query,
                                Map<String, Double> vectorScores,
                                Map<String, Integer> vectorRank,
                                Map<String, RetrievedChunk> merged) {
        double margin = cfg.marginOrDefault();
        if (margin <= 0 || vectorScores.size() < 3) {
            return;
        }
        double top1 = vectorScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double avg = vectorScores.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        if (top1 - avg >= margin) {
            return;
        }
        log.info("向量分无尖峰（top1={} 均值={} 差={} < 门限 {}），按无召回处理：query={}",
                round4(top1), round4(avg), round4(top1 - avg), margin, query);
        vectorScores.clear();
        vectorRank.clear();
        merged.clear();
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * 诊断用：只跑向量路，返回 top1 的原始相似度（<b>不受</b> min-score 与尖峰门限约束）。
     *
     * <p>存在的理由很实际：要决定 min-score 该定多少，必须先看到正样本和负样本的
     * 原始分分布。以前调阈值全靠猜——把 0.2 提到 0.6 到底是挡住了闲聊还是把正样本
     * 一起砍了，没人知道。把原始分暴露到评测面板上，阈值就从"拍脑袋"变成"看数据"。</p>
     *
     * @return top1 相似度；向量库不可用或无结果时返回 {@code null}
     */
    public Double vectorTopScore(String query, Set<String> docIds) {
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query).topK(1).similarityThreshold(0.0);
            Filter.Expression filter = docFilter(docIds == null ? Set.of() : docIds);
            if (filter != null) {
                builder.filterExpression(filter);
            }
            List<Document> hits = similaritySearchWithRetry(builder.build());
            if (hits == null || hits.isEmpty()) {
                return null;
            }
            return hits.get(0).getScore();
        } catch (Exception e) {
            log.warn("取向量 top1 分失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 把「限定文档」翻译成 Chroma 的元数据过滤表达式。
     * 单篇用 EQ、多篇用 IN：Chroma 的 where 对单值等值判断走索引更稳，
     * 也避免 IN 列表只有一项时部分版本的解析差异。
     */
    private static Filter.Expression docFilter(Set<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return null;
        }
        if (docIds.size() == 1) {
            return new Filter.Expression(Filter.ExpressionType.EQ,
                    new Filter.Key("docId"), new Filter.Value(docIds.iterator().next()));
        }
        return new Filter.Expression(Filter.ExpressionType.IN,
                new Filter.Key("docId"), new Filter.Value(List.copyOf(docIds)));
    }

    /** 范围限定涉及的块 id 集合；不限范围时返回 {@code null}（表示不过滤）。 */
    private Set<String> chunkIdsOf(Set<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return null;
        }
        Set<String> ids = new HashSet<>();
        for (String docId : docIds) {
            for (var chunk : registry.chunksOf(docId)) {
                ids.add(chunk.chunkId());
            }
        }
        return ids;
    }
}
