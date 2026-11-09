package com.example.safetyrag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 向量库配置：Chroma（唯一实现）。
 *
 * <p>为什么自己装配而不用 Spring AI 的自动配置：Spring AI 1.0.0 的
 * {@code initialize-schema} 判断"集合是否存在"的方式，是拿服务端返回的错误文案
 * 去和固定字符串 {@code Collection [xxx] does not exists} 做**精确比较**；
 * 而真实 Chroma（0.5.x / 0.6.x 都是）返回的是
 * {@code Collection xxx does not exist.}（方括号、句点都不同），比较必然不相等，
 * 于是"找不到集合"被当成真实异常抛出，应用启动即失败：
 * {@code Collection safety-regulations does not exist.}
 * 表现就是——**空的 Chroma 永远起不来，只有事先手工建过集合的库才能用**。</p>
 *
 * <p>这里改成：先尝试读集合，读不到就依次确保 tenant / database / collection 存在，
 * 然后再用 {@code initializeSchema(false)} 构建 VectorStore。这样换机器、清空
 * Chroma 数据目录之后都能直接启动，不再依赖错误文案这种脆弱约定。</p>
 */
@Configuration
public class VectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

    @Value("${spring.ai.vectorstore.chroma.client.host:http://localhost}")
    private String host;

    @Value("${spring.ai.vectorstore.chroma.client.port:8000}")
    private int port;

    @Value("${spring.ai.vectorstore.chroma.collection-name:safety-regulations}")
    private String collectionName;

    @Value("${spring.ai.vectorstore.chroma.tenant-name:SpringAiTenant}")
    private String tenantName;

    @Value("${spring.ai.vectorstore.chroma.database-name:SpringAiDatabase}")
    private String databaseName;

    @Value("${spring.ai.vectorstore.chroma.initialize-schema:true}")
    private boolean initializeSchema;

    /** 实际使用的地址，仅用于报错提示。 */
    private String baseUrl;

    /**
     * 带批量预取的 Embedding 装饰器（进程内一份）。
     *
     * <p>写入时 Chroma 会把整批文档交给 EmbeddingModel，而这条路径默认是一条
     * 文本一次请求。入库前先按 5 条/批把整篇算好放进缓存，后续逐条调用全部命中，
     * 171 块的文档从 171 次 HTTPS 降到 35 次，详见 {@link CachingEmbeddingModel}。</p>
     */
    private static volatile CachingEmbeddingModel cachedEmbeddingModel;

    /**
     * 入库前批量预热 embedding。拿不到模型时返回 0，调用方不必判空。
     */
    public static int prefetchEmbeddings(List<String> texts) {
        CachingEmbeddingModel model = cachedEmbeddingModel;
        if (model == null || texts == null || texts.isEmpty()) {
            return 0;
        }
        return model.prefetch(texts);
    }

    @Bean
    public ChromaApi chromaApi() {
        String base = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        if (!base.contains("://")) {
            base = "http://" + base;
        }
        this.baseUrl = base + ":" + port;
        log.info("Chroma 服务地址：{}，集合：{}", baseUrl, collectionName);
        return ChromaApi.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public VectorStore chromaVectorStore(ChromaApi chromaApi, EmbeddingModel embeddingModel) {
        CachingEmbeddingModel cachedModel = new CachingEmbeddingModel(embeddingModel);
        cachedEmbeddingModel = cachedModel;
        if (initializeSchema) {
            try {
                ensureCollection(chromaApi);
            } catch (RuntimeException e) {
                throw new IllegalStateException(failureHint(e), e);
            }
        }
        ChromaVectorStore store = ChromaVectorStore.builder(chromaApi, cachedModel)
                .tenantName(tenantName)
                .databaseName(databaseName)
                .collectionName(collectionName)
                // 自己负责建集合，关掉 Spring AI 那套基于错误文案的判断
                .initializeSchema(false)
                .build();
        log.info("当前 VectorStore 实现：ChromaVectorStore（远端服务，无本地向量文件）");
        return store;
    }

    /**
     * 把底层异常翻译成一句能直接照做的提示。
     *
     * <p>连不上 Chroma 是最常见的启动失败：没起服务、虚拟机没开机、地址配错。
     * 默认的几十行 Bean 创建堆栈里只有一句 Connection refused，对使用者没有指导意义。</p>
     */
    private String failureHint(RuntimeException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        boolean unreachable = msg.contains("Connection refused")
                || msg.contains("I/O error")
                || msg.contains("connect timed out")
                || msg.contains("UnknownHost")
                || msg.contains("failed to connect");
        if (!unreachable) {
            return "初始化 Chroma 集合失败：" + msg;
        }
        return """
                连不上 Chroma（%s）。向量库是这个应用的必要依赖，服务没起来就启动不了。

                处理方式：
                  1) 本机 Docker：docker compose up -d
                  2) 远程/虚拟机：确认 Chroma 在跑且端口可达，然后指定地址
                     CHROMA_HOST=http://<ip> bash restart.sh
                  3) 先自检 Chroma 是否可用：python tools/chroma_selfcheck.py --url %s
                详见 README「向量库（Chroma）」一节。""".formatted(baseUrl, baseUrl);
    }

    /**
     * 确保集合可用：已存在就直接返回，读不到就把 tenant / database / collection 补齐。
     *
     * <p>读取失败一律按"不存在"处理并尝试创建——创建是幂等的（已存在会返回 409，
     * 由 catch 吞掉并复查一次），比只认 404 更稳。</p>
     */
    private void ensureCollection(ChromaApi api) {
        try {
            api.getCollection(tenantName, databaseName, collectionName);
            log.info("Chroma 集合已就绪：{}", collectionName);
            return;
        } catch (RuntimeException e) {
            log.info("Chroma 集合 {} 读取失败（{}），尝试自动创建", collectionName, e.getMessage());
        }
        try {
            api.createTenant(tenantName);
            log.info("已创建 Chroma tenant：{}", tenantName);
        } catch (RuntimeException e) {
            log.debug("tenant {} 已存在或创建失败：{}", tenantName, e.getMessage());
        }
        try {
            api.createDatabase(tenantName, databaseName);
            log.info("已创建 Chroma database：{}", databaseName);
        } catch (RuntimeException e) {
            log.debug("database {} 已存在或创建失败：{}", databaseName, e.getMessage());
        }
        try {
            api.createCollection(tenantName, databaseName,
                    new ChromaApi.CreateCollectionRequest(collectionName));
            log.info("已创建 Chroma 集合：{}", collectionName);
        } catch (RuntimeException e) {
            // 并发启动时会撞 409，复查一次即可
            api.getCollection(tenantName, databaseName, collectionName);
            log.info("Chroma 集合已存在（另一实例已创建）：{}", collectionName);
        }
    }
}
