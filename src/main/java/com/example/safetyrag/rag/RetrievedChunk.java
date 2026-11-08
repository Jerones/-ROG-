package com.example.safetyrag.rag;

import com.example.safetyrag.util.DocNames;
import org.springframework.ai.document.Document;

/**
 * 检索命中的一个文本块 + 用于排序/展示的最终分数。
 *
 * @param document Spring AI 的 Document（正文 + metadata：docName/clause/chunkIndex/page）
 * @param score    混合合并后的最终分数（纯向量模式下即向量相似度）
 */
public record RetrievedChunk(Document document, double score) {

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
