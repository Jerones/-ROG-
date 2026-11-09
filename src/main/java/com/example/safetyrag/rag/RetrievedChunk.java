package com.example.safetyrag.rag;

import com.example.safetyrag.util.DocNames;
import org.springframework.ai.document.Document;

/**
 * 检索命中的一个文本块 + 用于排序/展示的分数。
 *
 * <p>为什么要区分三个分数：{@code score} 是排序依据，在 RRF 融合下它的量级是
 * 1/(k+rank)（约 0.01），完全不适合展示给用户看，也不适合拿来判"像不像"；
 * {@code vectorScore} 才是语义相似度本身（0~1），引用卡片上的"相似度"必须用它，
 * 否则用户会看到 0.016 这种莫名其妙的值。</p>
 *
 * @param document    Spring AI 的 Document（正文 + metadata：docName/clause/chunkIndex/page）
 * @param score       融合后的最终分数，仅用于排序
 * @param vectorScore 向量路的原始余弦相似度；BM25 单独引入的块为 0
 * @param bm25Score   BM25 归一化分（0~1）；未命中为 0
 */
public record RetrievedChunk(Document document, double score, double vectorScore, double bm25Score) {

    /** 兼容只关心排序分的调用方（纯向量模式下三者一致）。 */
    public RetrievedChunk(Document document, double score) {
        this(document, score, score, 0.0);
    }

    /**
     * 来源文档名（已去掉 {@code docId__} 磁盘前缀）。
     *
     * <p>注意 metadata 里的 docName 是入库那一刻写死的，历史数据里带着前缀；
     * 这里统一清洗，向量命中（读向量库 metadata）与 BM25 命中（读注册表）
     * 两条路径的展示才会一致。</p>
     */
    public String docName() {
        Object v = document.getMetadata().get("docName");
        return v == null ? "未知文档" : DocNames.stripDocIdPrefix(String.valueOf(v));
    }

    public String clause() {
        Object v = document.getMetadata().get("clause");
        return v == null ? "正文片段" : String.valueOf(v);
    }

    public Integer page() {
        Object v = document.getMetadata().get("page");
        if (v instanceof Number num) {
            return num.intValue();
        }
        return null;
    }

    public String text() {
        return document.getText() == null ? "" : document.getText();
    }
}
