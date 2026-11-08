package com.example.safetyrag.service;

import com.example.safetyrag.config.AppProperties;
import com.example.safetyrag.config.VectorStoreConfig;
import com.example.safetyrag.etl.DocumentIngestService;
import com.example.safetyrag.rag.Bm25Index;
import com.example.safetyrag.rag.HybridRetriever;
import com.example.safetyrag.rag.RetrievedChunk;
import com.example.safetyrag.web.dto.DocumentDto;
import com.example.safetyrag.web.dto.SearchHitDto;
import com.example.safetyrag.web.dto.StatsDto;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 知识库管理服务：对应前端"知识库菜单"的全部能力——
 * 文档列表 / 上传入库 / 删除 / 重建索引 / 索引统计 / 检索测试。
 *
 * <p>数据分工（答辩可讲）：</p>
 * <ul>
 *   <li>向量与相似检索 → Spring AI VectorStore（Chroma 或 SimpleVectorStore）；</li>
 *   <li>文档元信息与块原文 → DocumentRegistry（data/registry.json）；</li>
 *   <li>关键词索引 → Bm25Index（内存，启动时按注册表重建）；</li>
 *   <li>原始文件 → data/docs/（重建索引时重新读取切分）。</li>
 * </ul>
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final DocumentIngestService ingestService;
    private final DocumentRegistry registry;
    private final Bm25Index bm25Index;
    private final HybridRetriever retriever;
    private final AppProperties properties;
    private final Path docsDir;
    /** Chroma 专用：删除前先查"哪些 id 真的存在"，simple profile 下为空。 */
    private final ObjectProvider<ChromaApi> chromaApiProvider;
    /** 向量维度：入库时顺带记录（避免每次查统计都调一次 Embedding 接口）。 */
    private final AtomicInteger cachedDimension = new AtomicInteger(0);

    /**
     * 写操作互斥锁：入库、删除、重建索引三者都会同时改"注册表 + 向量库"，
     * 任意两者并发就会互相踩——典型场景是用户一边批量上传，一边点了重建索引，
     * 重建把刚写进去的块当旧块删掉，随后上传线程又把注册表写回去，
     * 结果向量库与注册表对不上，之后每次重建都要再收拾一次。
     *
     * <p>用可重入锁（而不是 synchronized）：重建索引内部会调用入库逻辑，
     * 同一线程需要能重复进入。等待方阻塞而非报错，是因为这几件事都是用户
     * 主动触发的一次操作，等几十秒比"报错让用户自己猜"体验更好。</p>
     */
    private final ReentrantLock writeLock = new ReentrantLock(true);

    /** 拿写锁；已在别人手里时先记一行日志再排队等。 */
    private void acquireWrite(String action) {
        if (!writeLock.tryLock()) {
            log.info("{} 正在等待其他写操作完成（入库/删除/重建索引互斥）", action);
            writeLock.lock();
        }
    }

    @Value("${spring.ai.openai.embedding.options.model:unknown}")
    private String embeddingModelName;

    @Value("${spring.ai.vectorstore.chroma.collection-name:safety-regulations}")
    private String chromaCollection;

    @Value("${spring.ai.vectorstore.chroma.tenant-name:SpringAiTenant}")
    private String chromaTenant;

    @Value("${spring.ai.vectorstore.chroma.database-name:SpringAiDatabase}")
    private String chromaDatabase;

    public KnowledgeBaseService(VectorStore vectorStore,
                                EmbeddingModel embeddingModel,
                                DocumentIngestService ingestService,
                                DocumentRegistry registry,
                                Bm25Index bm25Index,
                                HybridRetriever retriever,
                                AppProperties properties,
                                ObjectProvider<ChromaApi> chromaApiProvider) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.ingestService = ingestService;
        this.registry = registry;
        this.bm25Index = bm25Index;
        this.retriever = retriever;
        this.properties = properties;
        this.chromaApiProvider = chromaApiProvider;
        this.docsDir = Path.of(properties.dataDirOrDefault(), "docs");
        try {
            Files.createDirectories(docsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("创建文档目录失败", e);
        }
    }

    /** 启动时按注册表重建 BM25 索引（向量在 VectorStore 中自行持久化）。 */
    @PostConstruct
    public void initBm25() {
        rebuildBm25();
    }

    // ---------------- 文档管理 ----------------

    public List<DocumentDto> listDocuments() {
        return registry.listDocuments().stream()
                .map(d -> new DocumentDto(d.docId(), d.docName(), d.fileSize(),
                        d.chunkCount(), d.ingestedAt(), d.status()))
                .toList();
    }

    /** 入库进度回调：已写入块数 / 总块数。 */
    @FunctionalInterface
    public interface ProgressListener {
        void update(int written, int total);
    }

    /**
     * 上传 → 保存原文件 → 读取切分 → 向量入库 → 登记注册表与 BM25。
     *
     * <p>同步版本，页面上的"单个文件"与旧接口用它；多文件批量走
     * {@link IngestJobService}（后台任务 + 逐文件进度）。</p>
     */
    public DocumentDto upload(MultipartFile file) {
        String originalName = normalizeFileName(file);
        String docId = newDocId();
        Path saved = docsDir.resolve(docId + "__" + originalName);
        try {
            saveUpload(file, saved, originalName);
            return ingestSavedFile(saved, docId, originalName, file.getSize(), null);
        } catch (IOException e) {
            deleteFileQuietly(saved);
            throw new UncheckedIOException("文件保存失败", e);
        } catch (RuntimeException e) {
            // 其余失败交给 ingestSavedFile 处理：它会保留源文件，供「重建索引」补录。
            // 这里不再删——否则刚留好的恢复路径立刻被抹掉。
            throw e;
        }
    }

    /** 生成 8 位 docId（与磁盘文件名前缀一致）。 */
    public static String newDocId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 上传文件名兜底：浏览器可能不带文件名；取路径最后一段，防止目录穿越。 */
    public static String normalizeFileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "unnamed";
        }
        return Path.of(name).getFileName().toString();
    }

    /** 把上传内容落到 data/docs（同步、纯 IO，很快）。 */
    public void saveUpload(MultipartFile file, Path target, String originalName) throws IOException {
        if (!ingestService.supports(originalName)) {
            throw new IllegalArgumentException(DocumentIngestService.UNSUPPORTED_HINT + originalName);
        }
        Files.write(target, file.getBytes());
    }

    /**
     * 文件已落盘后的入库流程：读取 → 切分 → 向量化（带进度） → 登记。
     *
     * <p>失败时把原文件删掉：宁可让用户重传，也不要在 data/docs 里留下
     * "有文件、没入库"的孤儿——它会在下次重建索引时被当成新文档悄悄导进去。</p>
     */
    public DocumentDto ingestSavedFile(Path saved, String docId, String originalName,
                                       long fileSize, ProgressListener progress) {
        try {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(saved);
            } catch (IOException e) {
                throw new UncheckedIOException("读取文件失败：" + originalName, e);
            }
            List<Document> chunks = ingestService.readAndSplit(bytes, originalName, docId);
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("文档未解析出任何文本内容：" + originalName
                        + "（扫描版 PDF 没有文字层，需要先做 OCR）");
            }
            addToStores(docId, originalName, fileSize, chunks, progress);
            log.info("文档入库完成：{}（{} 块）", originalName, chunks.size());
            return listDocuments().stream()
                    .filter(d -> d.docId().equals(docId)).findFirst().orElseThrow();
        } catch (RuntimeException e) {
            // 这里**故意不删源文件**：失败发生在向量写入/登记之前，注册表里没有它，
            // 磁盘上多一个文件不会造成脏数据。留着反而给了用户一条恢复路径——
            // 网络恢复后点一次「重建索引」即可补录（重建会扫描 data/docs 下全部文件）。
            log.warn("入库失败，源文件已保留：{}（可稍后点『重建索引』重试）", saved.getFileName());
            throw new IllegalStateException(e.getMessage()
                    + "。源文件已保留在知识库目录，稍后点一次『重建索引』即可补录，不必重新上传。", e);
        }
    }

    /** 从磁盘路径入库（示例文档初始化、重建索引共用）。 */
    public void ingestFromPath(Path path, String docId) {
        String docName = stripDocIdPrefix(path.getFileName().toString());
        byte[] bytes;
        long size;
        try {
            bytes = Files.readAllBytes(path);
            size = bytes.length;
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败：" + path, e);
        }
        // 用去掉 "docId__" 前缀的名字做块元数据，否则引用卡片会显示 "68ce8e61__xxx.pdf"
        List<Document> chunks = ingestService.readAndSplit(bytes, docName, docId);
        addToStores(docId, docName, size, chunks, null);
    }

    /**
     * 每批交给 VectorStore 的块数。<b>注意它不等于 HTTP 请求数</b>：SimpleVectorStore
     * 内部仍然是逐块调 embedding 的，真正的请求合并由 {@code CachingEmbeddingModel}
     * 负责（5 条/请求）。这里分批的意义是控制单次写入的内存与回滚粒度。
     */
    private static final int EMBED_BATCH_SIZE = 10;

    /**
     * 向量库增删的连接重试：Chroma 的 uvicorn 服务默认只保留空闲连接约 5 秒，
     * 而两次向量调用之间隔着 embedding 接口耗时，连接池很容易复用到已被服务端
     * 关闭的连接，报 Connection reset / received no bytes。增删都是幂等操作，
     * 失败后连接池会换新连接，重试即可恢复。
     */
    private void withVectorStoreRetry(String action, Runnable op) {
        ResourceAccessException last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                op.run();
                return;
            } catch (ResourceAccessException e) {
                last = e;
                log.warn("{}第 {} 次失败（疑为空闲连接已被服务端关闭），重试：{}",
                        action, attempt, e.getMessage());
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    /**
     * 向量写入失败的定位提示。默认的 SimpleVectorStore 下"写入失败"实际上是
     * embedding（HTTP）失败，给 Chroma 的提示只会误导，因此按异常内容区分。
     */
    private String writeFailureHint(Throwable e) {
        String message = String.valueOf(rootMessage(e)).toLowerCase(Locale.ROOT);
        boolean network = message.contains("connection reset") || message.contains("i/o error")
                || message.contains("timed out") || message.contains("connection refused");
        if (network && !isChromaInUse()) {
            return "这是调用 Embedding 接口时的网络故障（百炼侧连接被重置），不是文档格式问题。"
                    + "已自动重试多次仍失败，通常稍后重传即可；若持续出现，请检查到 dashscope.aliyuncs.com 的网络/代理是否稳定。";
        }
        return VECTOR_STORE_HINT;
    }

    private boolean isChromaInUse() {
        return chromaApiProvider.getIfAvailable() != null;
    }

    private void addToStores(String docId, String docName, long fileSize, List<Document> chunks,
                             ProgressListener progress) {
        acquireWrite("入库");
        try {
            doAddToStores(docId, docName, fileSize, chunks, progress);
        } finally {
            writeLock.unlock();
        }
    }

    private void doAddToStores(String docId, String docName, long fileSize, List<Document> chunks,
                               ProgressListener progress) {
        // 1) 向量入库（Embedding 在 VectorStore 内部调用 EmbeddingModel 完成），按批大小分批调用。
        //    写入是分批的，中途失败会留下“半篇文档”的向量（检索时能查到、注册表里却不存在），
        //    因此记录已写入的块 id，失败时按 id 删除，保证“要么全进、要么全不进”。
        List<String> writtenIds = new ArrayList<>();
        int total = chunks.size();
        if (progress != null) {
            progress.update(0, total);
        }
        // 0) 先批量预取 embedding，再写向量库。
        //    SimpleVectorStore.add() 内部是**逐块**调 embedding 的（171 块 = 171 次 HTTPS），
        //    任何一次连接被重置都会让整批失败——这正是"这篇 PDF 总入不进去"的根因。
        //    这里一次性按 10 条/请求批量算好放进缓存，之后逐块调用全部命中缓存。
        //    放在写向量之前还有个好处：失败时向量库干净，不需要回滚。
        VectorStoreConfig.prefetchEmbeddings(chunks.stream()
                .map(c -> c.getText() == null ? "" : c.getText())
                .toList());
        try {
            for (int i = 0; i < chunks.size(); i += EMBED_BATCH_SIZE) {
                List<Document> batch = chunks.subList(i, Math.min(i + EMBED_BATCH_SIZE, chunks.size()));
                try {
                    withVectorStoreRetry("向量写入", () -> vectorStore.add(batch));
                } catch (RuntimeException e) {
                    throw new IllegalStateException("向量库写入失败：" + rootMessage(e) + "。" + writeFailureHint(e), e);
                }
                for (Document chunk : batch) {
                    writtenIds.add(chunk.getId());
                }
                if (progress != null) {
                    // 进度按"已写入块数"报：前端拿它算进度条，比按文件数报准得多
                    progress.update(writtenIds.size(), total);
                }
            }
        } catch (RuntimeException e) {
            deleteVectorsQuietly(writtenIds);
            throw e;
        }
        VectorStoreConfig.persistIfSimple(vectorStore);

        // 2) 登记注册表 + BM25（登记失败则回滚刚写入的向量，避免向量库里的孤儿数据）
        try {
            registry.register(docId, docName, fileSize, chunks);
        } catch (RuntimeException e) {
            deleteVectorsQuietly(writtenIds);
            throw e;
        }
        for (Document chunk : chunks) {
            bm25Index.addDocument(chunk.getId(), chunk.getText() == null ? "" : chunk.getText());
        }
        // 3) 顺带记录向量维度（只取第一块试探一次）
        if (cachedDimension.get() == 0 && !chunks.isEmpty()) {
            try {
                cachedDimension.set(embeddingModel.embed(chunks.get(0).getText()).length);
            } catch (Exception e) {
                log.debug("记录向量维度失败（不影响主流程）：{}", e.getMessage());
            }
        }
    }

    /**
     * 向量库不可用时给用户的定位提示。上传报错的根因几乎都在环境侧，
     * 但原始异常（Connection reset / 500）看不出该改什么，这里直接给出可操作项。
     */
    private static final String VECTOR_STORE_HINT = "请检查 Chroma 是否在运行、版本是否与工程一致："
            + "docker compose 用的是 0.5.23 镜像，用 pip 起服务请固定 chromadb==0.5.23；"
            + "若曾用 0.6.x 打开过 0.5.x 写的数据目录，写入时服务端会直接崩溃退出，需换回 0.5.23 或清空 data/chroma-server 重建。";

    /** 回滚/清理用：删除已写入的向量，失败只记日志，不掩盖原始异常。 */
    private void deleteVectorsQuietly(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        try {
            List<String> deletable = existingOnly(ids);
            if (deletable.isEmpty()) {
                return;
            }
            withVectorStoreRetry("向量回滚", () -> vectorStore.delete(deletable));
            VectorStoreConfig.persistIfSimple(vectorStore);
            log.info("已回滚本次写入的 {} 条向量", deletable.size());
        } catch (Exception e) {
            log.warn("回滚 {} 条向量失败，向量库中可能残留脏数据（可在知识库菜单点『重建索引』清理）：{}",
                    ids.size(), e.getMessage());
        }
    }

    /**
     * 查询向量库中**真实存在**的块 id。
     *
     * <p>为什么必须查：Chroma（0.5.23 与 0.6.3 表现一致）在删除**不存在的 id** 时会在原生
     * 层段错误，整个服务进程直接退出，客户端只看到 {@code Connection reset}；
     * 而删除存在的 id 完全正常。可"注册表里的 id"和"向量库里的 id"一旦不一致
     * （清空过 data/chroma-server、上一次入库中途失败、换了向量模型重建），
     * 直接按注册表删就必然踩中，表现就是"点重建索引 / 删完文档再上传就报错"。</p>
     *
     * @return 存在 id 的集合；拿不到（simple profile、Chroma 不可用）时返回 {@code null}，
     *         调用方应据此**跳过删除**，宁可留下旧向量也不要打崩服务
     */
    private Set<String> existingVectorIds() {
        ChromaApi api = chromaApiProvider.getIfAvailable();
        if (api == null) {
            return null;
        }
        try {
            ChromaApi.Collection collection = api.getCollection(chromaTenant, chromaDatabase, chromaCollection);
            Set<String> ids = new HashSet<>();
            int offset = 0;
            int page = 500;
            while (true) {
                ChromaApi.GetEmbeddingResponse response = api.getEmbeddings(
                        chromaTenant, chromaDatabase, collection.id(),
                        new ChromaApi.GetEmbeddingsRequest(null, null, page, offset));
                List<String> got = response.ids();
                if (got == null || got.isEmpty()) {
                    break;
                }
                ids.addAll(got);
                if (got.size() < page) {
                    break;
                }
                offset += page;
            }
            return ids;
        } catch (Exception e) {
            log.warn("读取向量库现有 id 失败（{}），本次跳过向量删除以免触发服务端崩溃", e.getMessage());
            return null;
        }
    }

    /**
     * 从给定 id 中筛出向量库里真实存在的那些。
     *
     * <p>默认（SimpleVectorStore）不需要筛：它就是一个内存 Map，删不存在的 key 是
     * 幂等空操作，直接删才能真的把块删掉。<b>反过来若不筛而一律跳过，删除文档
     * 就只销了注册表、向量还在库里</b>，检索时照样命中已删文档的块（引用卡片上
     * 会显示一篇"列表里已经没有了"的文档）。</p>
     *
     * <p>只有 Chroma 需要先求交集（删不存在的 id 会让服务端段错误），
     * 且拿不到现有 id 时宁可返回空、跳过删除。</p>
     */
    private List<String> existingOnly(List<String> ids) {
        if (chromaApiProvider.getIfAvailable() == null) {
            return ids; // 非 Chroma：删除幂等，全量交给 VectorStore
        }
        Set<String> existing = existingVectorIds();
        if (existing == null || existing.isEmpty()) {
            return List.of();
        }
        return ids.stream().filter(existing::contains).toList();
    }

    private static void deleteFileQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }

    /** 取异常链最内层的原因，拼进给用户看的消息里。 */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }

    /** 删除一篇文档：向量库删块、注册表销记、BM25 摘除、原文件删除。 */
    public boolean delete(String docId) {
        if (!registry.containsDocument(docId)) {
            return false;
        }
        acquireWrite("删除文档");
        try {
            List<String> chunkIds = registry.chunksOf(docId).stream()
                    .map(DocumentRegistry.ChunkRecord::chunkId).toList();
            // 顺序很重要：先删向量，再销注册表。反过来（先销注册表）的话，向量删除一旦
            // 失败，块 id 就随着登记一起消失了，那些向量会永远留在库里却没人认识它们——
            // 检索仍会命中一篇"列表里已经不存在"的文档，页面也再删不掉。
            if (!chunkIds.isEmpty()) {
                // 先求交集，避免删除不存在的 id 把 Chroma 打崩
                List<String> deletable = existingOnly(chunkIds);
                if (!deletable.isEmpty()) {
                    withVectorStoreRetry("向量删除", () -> vectorStore.delete(deletable));
                    VectorStoreConfig.persistIfSimple(vectorStore);
                } else {
                    log.info("向量库中未找到该文档的块，跳过向量删除：docId={}", docId);
                }
            }
            registry.unregister(docId);
            for (String id : chunkIds) {
                bm25Index.removeDocument(id);
            }
            try (var stream = Files.list(docsDir)) {
                stream.filter(p -> p.getFileName().toString().startsWith(docId + "__"))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                                // 原文件删除失败不阻塞主流程
                            }
                        });
            } catch (IOException ignored) {
                // 同上
            }
            log.info("文档已删除：docId={}，共 {} 块", docId, chunkIds.size());
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 重建索引：把 data/docs 下的全部原文件重新读取→切分→入库。
     * 旧块先按注册表整体删除，再逐篇重建，保证切分参数改动后能全量生效。
     *
     * <p>单篇失败不再中断整批：以前是异常直接往外抛，结果重建到一半停下，
     * 旧向量已经删了、新向量只建了一部分，页面只看到一句 500。现在按篇兜住，
     * 成功的照常登记，失败的把原因带回页面（这个文件仍在 data/docs，修好可重跑）。</p>
     */
    public ReindexResult reindex() {
        acquireWrite("重建索引");
        try {
            return doReindex();
        } finally {
            writeLock.unlock();
        }
    }

    /** 重建结果：成功篇数、被跳过的重复文件、失败清单。 */
    public record ReindexResult(int documents, int skipped, List<Failure> failed) {
        public record Failure(String name, String reason) {
        }
    }

    private ReindexResult doReindex() {
        // 1) 清空旧索引：一次性销记（只在末尾落一次盘），向量按批删除
        List<String> docIds = registry.listDocuments().stream()
                .map(DocumentRegistry.DocRecord::docId).toList();
        List<String> oldChunkIds = registry.unregisterAll(docIds);
        // 只删向量库里真实存在的 id：删除不存在的 id 会让 Chroma 原生层段错误、服务端直接退出
        List<String> deletableIds = existingOnly(oldChunkIds);
        if (deletableIds.size() < oldChunkIds.size()) {
            log.info("注册表登记 {} 块，向量库中实际存在 {} 块；只删除存在的部分（多出的登记是无向量的僵尸记录）",
                    oldChunkIds.size(), deletableIds.size());
        }
        final int deleteBatch = 500;
        for (int i = 0; i < deletableIds.size(); i += deleteBatch) {
            List<String> batch = deletableIds.subList(i,
                    Math.min(i + deleteBatch, deletableIds.size()));
            withVectorStoreRetry("向量删除", () -> vectorStore.delete(batch));
        }
        bm25Index.clear();
        // 2) 逐篇重新入库（docId 沿用文件名里的前缀，保持稳定）
        int count = 0;
        int skipped = 0;
        List<ReindexResult.Failure> failed = new ArrayList<>();
        try (var stream = Files.list(docsDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
            java.util.Set<String> seenHashes = new java.util.HashSet<>();
            for (Path file : files) {
                // 内容去重：data/docs 里若有同内容的两份文件（例如示例文档被
                // 重复导入过），只入库第一份，避免向量库里出现整篇重复块。
                try {
                    String hash = sha256(file);
                    if (!seenHashes.add(hash)) {
                        log.warn("重建索引跳过内容重复的文件：{}", file.getFileName());
                        skipped++;
                        continue;
                    }
                } catch (Exception e) {
                    log.debug("计算文件哈希失败（不影响入库）：{}", e.getMessage());
                }
                String name = file.getFileName().toString();
                String docId = name.contains("__") ? name.substring(0, name.indexOf("__"))
                        : UUID.randomUUID().toString().substring(0, 8);
                try {
                    ingestFromPath(file, docId);
                    count++;
                } catch (RuntimeException e) {
                    log.warn("重建索引时该文件入库失败，已跳过：{}（{}）", name, rootMessage(e));
                    failed.add(new ReindexResult.Failure(name, rootMessage(e)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("重建索引失败", e);
        }
        VectorStoreConfig.persistIfSimple(vectorStore);
        log.info("索引重建完成：成功 {} 篇，跳过重复 {} 篇，失败 {} 篇", count, skipped, failed.size());
        return new ReindexResult(count, skipped, failed);
    }

    private static String sha256(Path file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(file));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ---------------- 统计与检索测试 ----------------

    public StatsDto stats() {
        List<DocumentRegistry.DocRecord> docs = registry.listDocuments();
        int chunkTotal = docs.stream().mapToInt(DocumentRegistry.DocRecord::chunkCount).sum();
        int dimension = cachedDimension.get();
        if (dimension == 0 && !docs.isEmpty()) {
            // 进程刚起来时缓存是空的（维度只在入库时顺手记过），这里补一次。
            // 对真实模型来说 dimensions() 会发一次极小的 embedding 请求，结果缓存住，不会重复调。
            try {
                dimension = embeddingModel.dimensions();
                cachedDimension.set(dimension);
            } catch (Exception ignored) {
                dimension = 0;
            }
        }
        String modelLabel = properties.demoMode()
                ? "HashNgramEmbeddingModel（演示兜底）"
                : embeddingModelName;
        return new StatsDto(docs.size(), chunkTotal, dimension, modelLabel,
                vectorStore.getClass().getSimpleName(), properties.demoMode());
    }

    /** 检索测试小工具：只做召回不生成回答，答辩演示用。 */
    public List<SearchHitDto> searchTest(String query, int topK) {
        return retriever.retrieve(query, topK).stream()
                .map(hit -> new SearchHitDto(
                        hit.docName(),
                        hit.clause(),
                        hit.page(),
                        truncate(hit.text(), 220),
                        Math.round(hit.score() * 10000.0) / 10000.0))
                .toList();
    }

    // ---------------- 内部 ----------------

    private void rebuildBm25() {
        bm25Index.clear();
        for (DocumentRegistry.ChunkRecord chunk : registry.listChunks()) {
            bm25Index.addDocument(chunk.chunkId(), chunk.text());
        }
        if (!registry.listChunks().isEmpty()) {
            log.info("BM25 索引已按注册表重建：{} 块", registry.listChunks().size());
        }
    }

    private static String stripDocIdPrefix(String storedName) {
        return com.example.safetyrag.util.DocNames.stripDocIdPrefix(storedName);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
