package com.example.safetyrag.rag;

import com.example.safetyrag.config.AppProperties;
import com.example.safetyrag.service.DocumentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.client.ResourceAccessException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link HybridRetriever} 的召回/拒答决策测试。
 *
 * <p>缺陷背景：BM25 是相对打分（按本批最大值归一化），只要有一个块蹭到查询
 * 里的任意一个字符，归一化后 TOP1 必定为 1.0，混合分恒为 bm25Weight &gt; 0。
 * 于是 {@code ChatService} 里 {@code hits.isEmpty()} 的拒答分支永远不触发，
 * "知识库中未找到相关规范"成了死代码——实测问"今天食堂吃什么"能凑出 5 条
 * 食堂相关条款。</p>
 *
 * <p>修复后的规则：<b>"有没有召回"只由向量路决定</b>，BM25 无权单独构成召回；
 * 但向量路一旦确认有召回，BM25 就可以补充字面强命中的新块（否则混合检索等于没开）。
 * 演示模式（哈希向量无语义判别力）与向量库故障时才无条件放开 BM25。</p>
 */
class HybridRetrieverTest {

    private static final Map<String, String> CORPUS = new LinkedHashMap<>();

    static {
        CORPUS.put("c1", "第四条 进入施工现场的所有人员必须正确佩戴安全帽，安全帽应系紧下颏带。");
        CORPUS.put("c2", "第八条 高处作业是指在坠落高度基准面2米以上有可能坠落的高处进行的作业。");
        CORPUS.put("c3", "第二十一条 塔式起重机的安装拆卸应由具有资质的单位承担，司机必须持证上岗。");
        CORPUS.put("c4", "第十七条 施工现场临时用电应采用三级配电二级漏电保护系统。");
        // 故意放一条"食堂"条款：闲聊问题"今天食堂吃什么"在字面上确实能命中它，
        // 修复前正是靠这种字面命中把"无召回"变成了"有召回"。
        CORPUS.put("c6", "第十九条 食堂应当设置在远离粉尘、噪声的位置，炊事人员应当持健康证上岗。");
    }

    private VectorStore vectorStore;
    private Bm25Index bm25Index;
    private DocumentRegistry registry;

    @BeforeEach
    void setUp() {
        bm25Index = new Bm25Index();
        CORPUS.forEach(bm25Index::addDocument);

        registry = mock(DocumentRegistry.class);
        when(registry.toDocument(anyString())).thenAnswer(inv -> chunkOf(inv.getArgument(0)));

        vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    }

    @Test
    void chitchatQuery_bm25MatchesButCannotStandAlone_isNoRecall() {
        // 向量路空（语义判定为不相关），BM25 能命中"食堂"条款但不允许单独生效
        List<RetrievedChunk> hits = retriever(false, false, 0.2, 1.0, null)
                .retrieve("今天食堂吃什么", 5);
        assertTrue(hits.isEmpty(),
                "字面命中不得单独构成召回，实际召回：" + hits.size() + " 条");
    }

    @Test
    void relevantQuery_presentInVectorCandidates_isKept() {
        stubVector(scoredChunk("c1", 0.66));
        List<RetrievedChunk> hits = retriever(false, false, 0.2, 1.0, null)
                .retrieve("安全帽有什么要求", 5);
        assertFalse(hits.isEmpty());
        assertEquals("c1", hits.get(0).document().getId());
    }

    @Test
    void bm25IntroduceOff_doesNotPullInBlocksMissedByVector() {
        // introduceNew 显式关掉时的保守行为：向量只召回 c3，BM25 对"安全帽"命中的
        // c1 不应被拉进来。这里的 false 是刻意传的——默认值已经是 true（见下一个用例）。
        stubVector(scoredChunk("c3", 0.55));
        List<RetrievedChunk> hits = retriever(false, false, 0.2, 1.0, false)
                .retrieve("安全帽有什么要求", 5);
        assertEquals(1, hits.size(), "关闭引入时只应保留向量路召回的 c3，实际：" + hits);
        assertEquals("c3", hits.get(0).document().getId());
    }

    @Test
    void bm25_canIntroduceNewBlocks_whenVectorHasRecall() {
        // 默认配置（introduceNew 缺省 = true）下的混合行为：向量路确认"库里有答案"
        // 之后，BM25 补进来的字面强命中块是**收益**而不是噪声——"安全帽"这种术语
        // 恰恰是向量容易漏、BM25 容易抓的目标。这与上面的拒答用例并不矛盾：
        // 那边的向量路是空的，BM25 一律不许救场。
        stubVector(scoredChunk("c3", 0.55));
        List<RetrievedChunk> hits = retriever(false, false, 0.2, 1.0, null)
                .retrieve("安全帽有什么要求", 5);
        assertEquals(2, hits.size(), "向量路有召回时应允许 BM25 引入字面命中块，实际：" + hits);
    }

    @Test
    void bm25IntroduceNew_restoresTwoPathRecall() {
        stubVector(scoredChunk("c3", 0.55));
        List<RetrievedChunk> hits = retriever(false, false, 0.2, 1.0, true)
                .retrieve("安全帽有什么要求", 5);
        assertEquals(2, hits.size(), "开关打开后应恢复两路各自取候选，实际：" + hits.size());
    }

    @Test
    void demoMode_allowsBm25OnlyRecall() {
        // 演示模式的哈希向量无语义判别力，向量路必然为空，必须由 BM25 兜底
        List<RetrievedChunk> hits = retriever(true, false, 0.2, 1.0, null)
                .retrieve("安全帽有什么要求", 5);
        assertFalse(hits.isEmpty(), "演示模式不应把所有问题都判成无召回");
    }

    @Test
    void vectorFailure_degradesToBm25() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new ResourceAccessException("connection reset"));
        List<RetrievedChunk> hits = retriever(false, false, 0.2, 1.0, null)
                .retrieve("安全帽有什么要求", 5);
        assertFalse(hits.isEmpty(), "向量库不可用时应降级为纯 BM25，而不是全盘拒答");
    }

    @Test
    void vectorHitBelowMinScore_isDropped_evenWithHybridOn() {
        stubVector(scoredChunk("c1", 0.05));
        List<RetrievedChunk> hits = retriever(false, false, 0.2, 1.0, null)
                .retrieve("安全帽有什么要求", 5);
        assertTrue(hits.isEmpty(), "向量分低于 min-score 即无召回，BM25 不得救场");
    }

    // ---------------- 辅助 ----------------

    /**
     * @param demoMode    是否演示模式（决定 BM25 能否单独召回）
     * @param hybridOff   是否关闭混合召回
     * @param minScore    向量最低相似度
     * @param minBm25     BM25 原始分下限
     * @param introduceNew 是否允许 BM25 引入新块（null = 用默认值 false）
     */
    private HybridRetriever retriever(boolean demoMode, boolean hybridOff, double minScore,
                                      Double minBm25, Boolean introduceNew) {
        AppProperties properties = new AppProperties(demoMode, "./target/test-data",
                new AppProperties.Chunking(500, 60),
                // 单元测试固定用 weighted 融合 + margin=0：这些用例断言的是
                // "阈值过滤 / BM25 能否救场"这类行为，不该随融合策略的默认值漂移。
                new AppProperties.Retrieval(minScore, 0.0, !hybridOff, "weighted", 60,
                        0.7, 0.3, minBm25, introduceNew));
        return new HybridRetriever(vectorStore, bm25Index, properties, registry);
    }

    private void stubVector(Document... docs) {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(docs));
    }

    private static Document chunkOf(String id) {
        return CORPUS.containsKey(id) ? new Document(id, CORPUS.get(id), metadata()) : null;
    }

    private static Document scoredChunk(String id, double score) {
        return Document.builder()
                .id(id)
                .text(CORPUS.get(id))
                .metadata(metadata())
                .score(score)
                .build();
    }

    private static Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("docName", "测试规范.txt");
        metadata.put("clause", "第X条");
        return metadata;
    }
}
