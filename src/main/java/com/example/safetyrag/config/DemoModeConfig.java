package com.example.safetyrag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 演示模式配置：当 app.demo-mode=true 时，注册一个本地兜底的 EmbeddingModel，
 * 并用 @Primary 让 VectorStore（Chroma / SimpleVectorStore）优先使用它，
 * 从而完全不依赖外部模型 API 也能完成"向量化 → 入库 → 相似度检索"全链路。
 *
 * <p>注意：这只是离线演示兜底实现，语义理解能力远弱于真实 Embedding 模型
 * （如 bge-large-zh-v1.5、text-embedding-v3）。正式演示/答辩时请在
 * application.yml 中配置真实 API key 并把 app.demo-mode 改为 false。</p>
 */
@Configuration
@ConditionalOnProperty(name = "app.demo-mode", havingValue = "true")
public class DemoModeConfig {

    private static final Logger log = LoggerFactory.getLogger(DemoModeConfig.class);

    @Bean
    @Primary
    public EmbeddingModel demoEmbeddingModel() {
        log.warn("==============================================================");
        log.warn("演示模式已开启（app.demo-mode=true）：使用本地兜底 EmbeddingModel");
        log.warn("（字符 n-gram 哈希向量），聊天将使用抽取式回答，不调用外部 API。");
        log.warn("配置真实 LLM_API_KEY 并设置 APP_DEMO_MODE=false 可切换为真实模型。");
        log.warn("==============================================================");
        return new HashNgramEmbeddingModel(HashNgramEmbeddingModel.DEFAULT_DIMENSIONS);
    }

    /**
     * 「离线演示兜底」基于字符 n-gram 哈希的简易 Embedding 实现。
     *
     * <p>原理：把文本切成字符 bigram/trigram，用每个 n-gram 的哈希值把计数
     * 累加到固定维度向量的对应位置，最后做 L2 归一化。语义上它只能反映
     * "字面重合程度"，但对于关键词高度重合的规范条文问答，足够把相关条款
     * 排到前面，满足无 key 时的链路演示。</p>
     *
     * <p>答辩讲解点：真实 Embedding 是把语义映射到稠密向量空间；这里用哈希
     * 向量代替，是为了让整个系统在完全离线时仍可运行，接口（EmbeddingModel）
     * 与真实模型完全一致，切换时业务代码零改动。</p>
     */
    public static class HashNgramEmbeddingModel implements EmbeddingModel {

        public static final int DEFAULT_DIMENSIONS = 1024;

        private final int dimensions;

        public HashNgramEmbeddingModel(int dimensions) {
            this.dimensions = dimensions;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            List<String> inputs = request.getInstructions();
            for (int i = 0; i < inputs.size(); i++) {
                embeddings.add(new Embedding(embed(inputs.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(String text) {
            double[] vector = new double[dimensions];
            for (String gram : ngrams(text)) {
                // 用两个不同种子的哈希做双散列，降低碰撞对结果的影响
                int h1 = Math.floorMod(gram.hashCode(), dimensions);
                int h2 = Math.floorMod(gram.hashCode() * 31 + 7, dimensions);
                vector[h1] += 1.0;
                vector[h2] += 0.5;
            }
            // L2 归一化：归一化后点积即余弦相似度
            double norm = 0.0;
            for (double v : vector) {
                norm += v * v;
            }
            norm = Math.sqrt(norm);
            float[] result = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                result[i] = norm == 0.0 ? 0f : (float) (vector[i] / norm);
            }
            return result;
        }

        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            // EmbeddingModel 接口要求实现：对 Document 取其正文再向量化
            return embed(document.getText());
        }

        @Override
        public int dimensions() {
            return dimensions;
        }

        /** 生成字符 bigram + trigram 集合（带词内顺序信息，比单字更稳）。 */
        static Set<String> ngrams(String text) {
            String cleaned = text == null ? "" : text.replaceAll("\\s+", "");
            Set<String> grams = new LinkedHashSet<>();
            for (int n = 2; n <= 3; n++) {
                for (int i = 0; i + n <= cleaned.length(); i++) {
                    grams.add(cleaned.substring(i, i + n));
                }
            }
            if (cleaned.length() == 1) {
                grams.add(cleaned);
            }
            return grams;
        }
    }
}
