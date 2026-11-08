package com.example.safetyrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本项目自定义配置（对应 application.yml 中的 app.* 节点）。
 *
 * @param demoMode  演示模式：true 时使用本地兜底 Embedding + 抽取式回答，不调外部 API
 * @param dataDir   数据目录（原始文档、注册表 JSON、SimpleVectorStore 持久化文件）
 * @param chunking  切分参数
 * @param retrieval 检索参数
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        boolean demoMode,
        String dataDir,
        Chunking chunking,
        Retrieval retrieval
) {

    /** 文档切分参数。 */
    public record Chunking(int chunkSize, int overlap) {
    }

    /**
     * 检索参数。
     *
     * <p>{@code minScore} 只作用于向量路。BM25 是字面匹配、按本批最大值归一化，
     * 归一化后必然有 top1，因此默认不允许它把向量路未召回的块"拉进来"，否则
     * 任何闲聊问题都会命中几条条款，"无召回不编造"就形同虚设。需要用两路
     * 各自取候选的经典做法做对照实验时，把 {@code bm25IntroduceNew} 设为 true。</p>
     *
     * @param minScore          向量召回的最低相似度，低于此值视为"无召回"
     * @param hybridEnabled     是否启用 BM25 混合召回
     * @param vectorWeight      混合合并时向量分数的权重
     * @param bm25Weight        混合合并时 BM25 归一化分数的权重
     * @param minBm25Score      BM25 原始分下限（绝对值），低于它的块不参与加权
     * @param bm25IntroduceNew  是否允许 BM25 引入向量路未召回的块（默认否）
     */
    public record Retrieval(double minScore, boolean hybridEnabled,
                            double vectorWeight, double bm25Weight,
                            Double minBm25Score, Boolean bm25IntroduceNew) {

        /** BM25 原始分下限；配置缺省时取 1.0，显式填 0 表示不设下限。 */
        public double minBm25ScoreOrDefault() {
            return minBm25Score == null ? 1.0 : minBm25Score;
        }

        /** BM25 是否可单独引入新块；配置缺省时按否处理（保守优先）。 */
        public boolean bm25IntroduceNewOrDefault() {
            return bm25IntroduceNew != null && bm25IntroduceNew;
        }
    }

    /** 数据目录的默认值兜底。 */
    public String dataDirOrDefault() {
        return (dataDir == null || dataDir.isBlank()) ? "./data" : dataDir;
    }
}
