package com.example.safetyrag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * 默认 VectorStore 配置：SimpleVectorStore（零外部依赖）。
 *
 * <p>向量保存在内存中，并持久化到 {@code <data-dir>/simple-vector-store.json}，
 * 因此不装 Docker、不装 Chroma 也能完整跑通"上传 → 切分 → 向量化 → 检索 →
 * 引用溯源"全链路。这是本项目的**默认实现**，开箱即用。</p>
 *
 * <p>只有显式启用 {@code chroma} profile 时才改用 Chroma，
 * 由 {@link ChromaVectorStoreConfig} 装配（两者用 profile 互斥，不会冲突）。</p>
 *
 * <p>能力边界（答辩时值得讲）：SimpleVectorStore 是**全量线性扫描**（O(N) 逐条算
 * 余弦相似度），没有 ANN 索引。本项目约 600 块、1024 维，单次检索在毫秒级，
 * 完全够用；数据量上万后才需要换 Chroma/pgvector 这类带索引的向量库。
 * 换句话说，这里换的不只是实现，也是"要不要为规模付出运维成本"的取舍。</p>
 */
@Configuration
@Profile("!chroma")
public class VectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

    private static final String STORE_FILE_NAME = "simple-vector-store.json";
    /** 记录这份向量文件是哪个 Embedding 模型生成的（换了模型必须重建索引）。 */
    private static final String STORE_MODEL_FILE_NAME = "simple-vector-store.model";

    /**
     * 持久化文件位置。由 Bean 创建时按 {@code app.data-dir} 解析一次，
     * 这样 {@code --app.data-dir} 对向量文件同样生效，不会出现
     * "注册表写到了 A 目录、向量还在 B 目录"的错位。
     */
    private static volatile File storeFile = new File("./data/" + STORE_FILE_NAME);
    /** 当前生效的 Embedding 模型标识，落盘时一并写入 .model 文件（见 persistIfSimple）。 */
    private static volatile String embeddingModelTag = "unknown";

    /**
     * 带批量预取的 Embedding 装饰器。SimpleVectorStore 是逐块调 embedding 接口的
     * （171 块 = 171 次 HTTPS），入门前先批量算好能少一个数量级的请求，
     * 详见 {@link CachingEmbeddingModel}。
     */
    private static volatile CachingEmbeddingModel cachedEmbeddingModel;

    /**
     * 入库前批量预热 embedding。非默认实现（如 Chroma profile）时返回 0 并跳过，
     * 调用方不必判断当前用的是哪套向量库。
     */
    public static int prefetchEmbeddings(List<String> texts) {
        CachingEmbeddingModel model = cachedEmbeddingModel;
        if (model == null || texts == null || texts.isEmpty()) {
            return 0;
        }
        return model.prefetch(texts);
    }

    @Bean
    public VectorStore simpleVectorStore(EmbeddingModel embeddingModel,
                                         AppProperties properties,
                                         @Value("${spring.ai.openai.embedding.options.model:unknown}") String modelName) {
        storeFile = Path.of(properties.dataDirOrDefault(), STORE_FILE_NAME).toFile();
        // 逐块调用 → 批量预取（见 CachingEmbeddingModel 类注释）
        CachingEmbeddingModel cachedModel = new CachingEmbeddingModel(embeddingModel);
        cachedEmbeddingModel = cachedModel;
        // 演示模式的哈希向量与真实模型的语义向量**不能混在同一个库里**：
        // 两者维度可能一样（都是 1024），不会报错，但余弦相似度不在同一量纲上，
        // 真实向量（0.5 上下）会把哈希向量（0.0x）整体压死——表现为"老文档怎么都搜不到"。
        // 记一个模型标识，下次启动对不上就明确提示重建索引。
        embeddingModelTag = properties.demoMode() ? "hash-ngram" : modelName;
        SimpleVectorStore store = SimpleVectorStore.builder(cachedModel).build();
        if (storeFile.exists() && storeFile.length() > 0) {
            try {
                store.load(storeFile);
                log.info("SimpleVectorStore 已从 {} 恢复历史向量", storeFile.getPath());
                warnIfModelChanged();
            } catch (Exception e) {
                log.warn("SimpleVectorStore 历史文件加载失败，将以空库启动：{}", e.getMessage());
            }
        }
        log.info("当前 VectorStore 实现：SimpleVectorStore（内存 + 本地文件持久化，无需外部服务）");
        return store;
    }

    private static File modelFile() {
        return new File(storeFile.getParentFile(), STORE_MODEL_FILE_NAME);
    }

    /** 历史向量由另一个模型生成时给出可操作提示（不自动重建，避免启动时偷偷烧 embedding 配额）。 */
    private static void warnIfModelChanged() {
        File meta = modelFile();
        if (!meta.exists()) {
            return; // 老数据没有标识，无从比较，按当前模型处理
        }
        try {
            String used = java.nio.file.Files.readString(meta.toPath()).strip();
            if (!used.isEmpty() && !used.equals(embeddingModelTag)) {
                log.warn("==============================================================");
                log.warn("向量文件是「{}」生成的，当前 Embedding 模型是「{}」。", used, embeddingModelTag);
                log.warn("两者相似度不在同一量纲上，检索会明显偏向新库：请到知识库菜单点一次『重建索引』。");
                log.warn("==============================================================");
            }
        } catch (Exception e) {
            log.debug("读取向量模型标识失败（不影响运行）：{}", e.getMessage());
        }
    }

    /** 入库/删除后由 KnowledgeBaseService 调用，把内存向量落盘。 */
    public static void persistIfSimple(VectorStore vectorStore) {
        if (vectorStore instanceof SimpleVectorStore simple) {
            File target = storeFile;
            target.getParentFile().mkdirs();
            simple.save(target);
            try {
                java.nio.file.Files.writeString(modelFile().toPath(), embeddingModelTag);
            } catch (Exception e) {
                // 标识写失败只影响下次启动的提示，不应让入库失败
            }
        }
    }
}
