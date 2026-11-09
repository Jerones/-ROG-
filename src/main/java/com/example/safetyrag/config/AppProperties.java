package com.example.safetyrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本项目自定义配置（对应 application.yml 中的 app.* 节点）。
 *
 * @param demoMode  演示模式：true 时使用本地兜底 Embedding + 抽取式回答，不调外部 API
 * @param dataDir   数据目录（原始文档、注册表 JSON；向量不在本地，存 Chroma）
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
     * <p>{@code fusion} 决定两路怎么合并：{@code rrf} 用排名（默认），
     * {@code weighted} 用分数的线性加权（旧行为，保留下来做对照实验）。
     * 选 RRF 是因为两路分数的量纲根本不可比——BM25 归一化后 top1 恒为 1.0，
     * 而向量分在真实语料上相邻两名往往只差 0.02，线性加权下 BM25 一个权重项
     * 就能把语义更准的块挤下去。</p>
     *
     * @param minScore          向量召回的最低相似度，低于此值视为"无召回"
     * @param margin            相似度尖峰门限：top1 与候选均值的差小于此值时，判定整批都是背景噪声
     * @param hybridEnabled     是否启用 BM25 混合召回
     * @param fusion            融合策略：rrf（按排名）或 weighted（按分数）
     * @param rrfK              RRF 平滑常数，越大则各名次之间的分差越平缓
     * @param vectorWeight      融合权重：向量路
     * @param bm25Weight        融合权重：BM25 路
     * @param minBm25Score      BM25 原始分下限（绝对值），低于它的块不参与融合
     * @param bm25IntroduceNew  向量路<b>已有召回</b>时，是否允许 BM25 引入新块（默认是）。
     *                          向量路无召回时无论本项如何都不许 BM25 救场——拒答优先。
     */
    public record Retrieval(double minScore, Double margin, boolean hybridEnabled,
                            String fusion, Integer rrfK,
                            double vectorWeight, double bm25Weight,
                            Double minBm25Score, Boolean bm25IntroduceNew) {

        /** BM25 原始分下限；配置缺省时取 1.0，显式填 0 表示不设下限。 */
        public double minBm25ScoreOrDefault() {
            return minBm25Score == null ? 1.0 : minBm25Score;
        }

        /** BM25 是否有条件引入新块；配置缺省时按是处理（仅限向量路已有召回时）。 */
        public boolean bm25IntroduceNewOrDefault() {
            return bm25IntroduceNew == null || bm25IntroduceNew;
        }

        /** 是否使用 RRF 融合；配置缺省时按是处理（这是我们验证过的更优策略）。 */
        public boolean useRrf() {
            return fusion == null || fusion.isBlank() || "rrf".equalsIgnoreCase(fusion);
        }

        /** RRF 平滑常数，缺省 60（业界常用值，来源是 Cormack 等人的原始论文）。 */
        public int rrfKOrDefault() {
            return rrfK == null || rrfK < 1 ? 60 : rrfK;
        }

        /**
         * 相似度尖峰门限；缺省 0 表示不启用。
         *
         * <p>为什么需要它：中文 embedding 的相似度基线偏高，两句毫不相干的话也能
         * 拿到 0.4~0.6。单纯抬高 min-score 会把"问得口语化但确实有答案"的正样本
         * 一起挡掉。改成看"有没有尖峰"更稳——真命中时 top1 会明显跳起来，
         * 闲聊时整批分数则平摊成一片。</p>
         */
        public double marginOrDefault() {
            return margin == null || margin < 0 ? 0.0 : margin;
        }
    }

    /** 数据目录的默认值兜底。 */
    public String dataDirOrDefault() {
        return (dataDir == null || dataDir.isBlank()) ? "./data" : dataDir;
    }
}
