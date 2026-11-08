package com.example.safetyrag.rag;

import com.example.safetyrag.config.AppProperties;
import com.example.safetyrag.service.DocumentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索器：向量检索（语义） + BM25（关键词）两路召回后合并去重。
 *
 * <p>合并策略（可讲解、可做对比实验）：</p>
 * <ol>
 *   <li>向量路：VectorStore.similaritySearch 取 topK×3 个候选，
 *       并按 app.retrieval.min-score 过滤掉低相似度结果——这一步是
 *       "无召回就不回答"的关键，避免模型硬编；</li>
 *   <li>BM25 路：对内存中的全部块算分，默认只对向量路已召回的候选加权排序
 *       （原始分低于 min-bm25-score 的块直接排除）；是否允许 BM25 单独引入新块
 *       由 {@code bm25-introduce-new}、演示模式与向量库故障共同决定；</li>
 *   <li>合并：同一块两路都命中时，最终分 = 0.7×向量分 + 0.3×BM25 分；
 *       只有一路命中的，缺失的那路记 0 分（所以两路都命中的块天然靠前）；</li>
 *   <li>按最终分降序取 topK。</li>
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
     * @param query 用户问题
     * @param topK  最终返回条数
     */
    public List<RetrievedChunk> retrieve(String query, int topK) {
        int candidateN = Math.max(topK * 3, topK);
        AppProperties.Retrieval cfg = properties.retrieval();

        // ---------- 第一路：向量检索 ----------
        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();
        Map<String, Double> vectorScores = new LinkedHashMap<>();
        boolean vectorFailed = false;
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(candidateN)
                    .similarityThreshold(0.0) // 阈值过滤在下面手动做，便于统一口径和日志
                    .build();
            List<Document> hits = similaritySearchWithRetry(request);
            if (hits != null) {
                for (Document doc : hits) {
                    double score = doc.getScore() == null ? 0.0 : doc.getScore();
                    if (score < cfg.minScore()) {
                        continue; // 低相似度视为无召回
                    }
                    vectorScores.put(doc.getId(), score);
                    merged.put(doc.getId(), new RetrievedChunk(doc, score));
                }
            }
        } catch (Exception e) {
            // 向量库不可用（如 Chroma 未启动）时不让整个问答崩掉，降级为纯 BM25
            vectorFailed = true;
            log.warn("向量检索失败，降级为纯 BM25 召回：{}", e.getMessage());
        }

        // ---------- 第二路：BM25 关键词检索 ----------
        Map<String, Double> bm25Scores = new LinkedHashMap<>();
        if (cfg.hybridEnabled()) {
            // BM25 只认字面、不认语义：问题里只要有一个字出现在块里它就能给出正分，
            // 且分数按本批最大值归一化后必然有 top1（=1.0）。因此它只能给向量路
            // 已召回的候选加权排序，不能自己决定"有没有召回"——否则问"今天食堂
            // 吃什么"也会凑出几条食堂条款，"无召回不编造"就成了死代码。
            //
            // 两种例外允许 BM25 单独引入新块（可用性优先）：
            //   1) 演示模式：哈希向量无语义判别力（实测余弦仅 0.02~0.11，全部低于
            //      min-score），向量路必然为空，只能由 BM25 兜底；
            //   2) 向量库调用失败：此时 BM25 是唯一检索手段。
            boolean mayIntroduceNew = vectorFailed
                    || properties.demoMode()
                    || cfg.bm25IntroduceNewOrDefault();

            // 门槛之外的截断可能把合规块挡掉，因此多捞一批候选
            int lookahead = Math.max(candidateN * 4, 50);
            List<Bm25Index.ScoredDoc> bm25Hits = bm25Index.search(query, lookahead);
            double minBm25Score = cfg.minBm25ScoreOrDefault();

            List<Bm25Index.ScoredDoc> admitted = new ArrayList<>();
            for (Bm25Index.ScoredDoc hit : bm25Hits) {
                if (hit.score() < minBm25Score) {
                    continue; // 只蹭到零星字词，分数过低，不参与加权
                }
                if (!mayIntroduceNew && !vectorScores.containsKey(hit.docId())) {
                    continue; // 默认策略：BM25 不得把向量路未召回的块拉进结果集
                }
                admitted.add(hit);
            }
            if (bm25Hits.size() > admitted.size()) {
                log.debug("BM25 候选 {} 条，剔除 {} 条（分数<{} 或不在向量候选集内，允许引入新块={}）",
                        bm25Hits.size(), bm25Hits.size() - admitted.size(),
                        minBm25Score, mayIntroduceNew);
            }

            double max = admitted.stream()
                    .mapToDouble(Bm25Index.ScoredDoc::score).max().orElse(0.0);
            for (Bm25Index.ScoredDoc hit : admitted) {
                double normalized = max > 0 ? hit.score() / max : 0.0;
                bm25Scores.put(hit.docId(), normalized);
                if (!merged.containsKey(hit.docId())) {
                    Document doc = registry.toDocument(hit.docId());
                    if (doc != null) {
                        merged.put(hit.docId(), new RetrievedChunk(doc, 0.0));
                    }
                }
            }
        }

        // ---------- 合并打分 ----------
        List<RetrievedChunk> results = new ArrayList<>();
        for (Map.Entry<String, RetrievedChunk> entry : merged.entrySet()) {
            String id = entry.getKey();
            double combined;
            if (cfg.hybridEnabled()) {
                combined = cfg.vectorWeight() * vectorScores.getOrDefault(id, 0.0)
                        + cfg.bm25Weight() * bm25Scores.getOrDefault(id, 0.0);
            } else {
                combined = vectorScores.getOrDefault(id, 0.0);
            }
            results.add(new RetrievedChunk(entry.getValue().document(), combined));
        }
        results.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        if (results.isEmpty()) {
            log.debug("向量路无达标候选，且 BM25 未引入新块，按无召回处理：query={}", query);
        }

        // ---------- 按最终分截断 ----------
        return results.size() > topK ? results.subList(0, topK) : results;
    }
}
