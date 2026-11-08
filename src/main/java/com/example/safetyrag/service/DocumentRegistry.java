package com.example.safetyrag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档注册表：记录"哪些文档已入库、每个文档切了多少块、每块的 id 与原文"，
 * 持久化为 data/registry.json。
 *
 * <p>为什么需要它？Spring AI 的 VectorStore 抽象只有 add/delete/按问题检索，
 * 没有"列出全部文档/按文档删除"这类管理接口（各家实现能力不一）。
 * 知识库菜单要展示文档列表、按文档删除、给 BM25 建索引，都需要一份
 * 应用自己维护的元数据。向量本身仍存在 VectorStore 里，注册表只存
 * 元信息和块原文（块原文同时供 BM25 索引和引用展示使用）。</p>
 */
@Component
public class DocumentRegistry {

    private static final Logger log = LoggerFactory.getLogger(DocumentRegistry.class);

    /** 一篇已入库文档的元信息。 */
    public record DocRecord(String docId, String docName, long fileSize,
                            int chunkCount, String ingestedAt, String status) {
    }

    /** 一个文本块的登记信息（id 与 VectorStore 中的 Document id 一致）。 */
    public record ChunkRecord(String chunkId, String docId, String docName, String clause,
                              int chunkIndex, Integer page, String text) {
    }

    /** registry.json 的落盘结构。 */
    private record RegistryFile(List<DocRecord> documents, List<ChunkRecord> chunks) {
    }

    private final Path registryPath;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, DocRecord> documents = new LinkedHashMap<>();
    private final Map<String, ChunkRecord> chunks = new LinkedHashMap<>();
    /** 注册表损坏且无法备份时置 true：禁止回写，避免把仅存的数据彻底覆盖。 */
    private boolean readOnly;

    public DocumentRegistry(AppPropertiesHolder holder) {
        this.registryPath = Path.of(holder.dataDir(), "registry.json");
        load();
    }

    /**
     * 小小的桥接类：避免 DocumentRegistry 与 AppProperties 的构造顺序问题，
     * 同时把"数据目录"这一件事收口到一处。
     */
    @Component
    public static class AppPropertiesHolder {
        private final String dataDir;

        public AppPropertiesHolder(com.example.safetyrag.config.AppProperties props) {
            this.dataDir = props.dataDirOrDefault();
        }

        public String dataDir() {
            return dataDir;
        }
    }

    // ---------------- 查询 ----------------

    public synchronized List<DocRecord> listDocuments() {
        return new ArrayList<>(documents.values());
    }

    public synchronized List<ChunkRecord> listChunks() {
        return new ArrayList<>(chunks.values());
    }

    public synchronized List<ChunkRecord> chunksOf(String docId) {
        List<ChunkRecord> result = new ArrayList<>();
        for (ChunkRecord c : chunks.values()) {
            if (c.docId().equals(docId)) {
                result.add(c);
            }
        }
        result.sort((a, b) -> Integer.compare(a.chunkIndex(), b.chunkIndex()));
        return result;
    }

    /** 按块 id 还原 Spring AI Document（供 BM25 命中后合并 metadata）。 */
    public synchronized Document toDocument(String chunkId) {
        ChunkRecord c = chunks.get(chunkId);
        if (c == null) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("docId", c.docId());
        metadata.put("docName", c.docName());
        metadata.put("clause", c.clause());
        metadata.put("chunkIndex", c.chunkIndex());
        if (c.page() != null) {
            metadata.put("page", c.page());
        }
        return new Document(c.chunkId(), c.text(), metadata);
    }

    public synchronized boolean containsDocument(String docId) {
        return documents.containsKey(docId);
    }

    // ---------------- 变更 ----------------

    /** 登记一篇新文档及其全部块（调用前应已完成向量入库）。 */
    public synchronized void register(String docId, String docName, long fileSize,
                                      List<Document> chunkDocs) {
        List<ChunkRecord> records = new ArrayList<>();
        for (Document d : chunkDocs) {
            Map<String, Object> md = d.getMetadata();
            Integer page = md.get("page") instanceof Number num ? num.intValue() : null;
            ChunkRecord rec = new ChunkRecord(
                    d.getId(),
                    docId,
                    docName,
                    String.valueOf(md.getOrDefault("clause", "正文片段")),
                    md.get("chunkIndex") instanceof Number num ? num.intValue() : records.size(),
                    page,
                    d.getText() == null ? "" : d.getText());
            records.add(rec);
            chunks.put(rec.chunkId(), rec);
        }
        documents.put(docId, new DocRecord(docId, docName, fileSize, records.size(),
                LocalDateTime.now().toString(), "已入库"));
        save();
    }

    /** 移除一篇文档及其块登记，返回被移除的块 id 列表（供 VectorStore 删除）。 */
    public synchronized List<String> unregister(String docId) {
        return unregisterAll(List.of(docId));
    }

    /**
     * 批量销记多篇文档，只在最后落一次盘。
     *
     * <p>重建索引时按篇调用 {@link #unregister} 会导致 registry.json 被重写
     * 文档数那么多次（本项目实测单文件 1.5MB），文档一多就是明显的 IO 开销。</p>
     *
     * @return 被移除的全部块 id（供 VectorStore 一次性删除）
     */
    public synchronized List<String> unregisterAll(Collection<String> docIds) {
        List<String> removedIds = new ArrayList<>();
        if (docIds == null || docIds.isEmpty()) {
            return removedIds;
        }
        Set<String> targets = new HashSet<>(docIds);
        targets.forEach(documents::remove);
        chunks.values().removeIf(c -> {
            if (targets.contains(c.docId())) {
                removedIds.add(c.chunkId());
                return true;
            }
            return false;
        });
        save();
        return removedIds;
    }

    // ---------------- 持久化 ----------------

    private void load() {
        File file = registryPath.toFile();
        if (!file.exists() || file.length() == 0) {
            return;
        }
        try {
            RegistryFile data = objectMapper.readValue(file, new TypeReference<>() {
            });
            if (data.documents() != null) {
                for (DocRecord d : data.documents()) {
                    // 历史数据里的 docName 带着 "68ce8e61__" 这种磁盘文件前缀
                    // （入库时为了防止同名覆盖加的），展示时要还原成原始文件名。
                    // 在加载处统一清洗，列表、引用、检索结果就都不用各自处理了。
                    documents.put(d.docId(), new DocRecord(d.docId(), stripDocIdPrefix(d.docName()),
                            d.fileSize(), d.chunkCount(), d.ingestedAt(), d.status()));
                }
            }
            if (data.chunks() != null) {
                for (ChunkRecord c : data.chunks()) {
                    chunks.put(c.chunkId(), new ChunkRecord(c.chunkId(), c.docId(),
                            stripDocIdPrefix(c.docName()), c.clause(), c.chunkIndex(),
                            c.page(), c.text()));
                }
            }
            log.info("文档注册表已加载：{} 篇文档，{} 个块", documents.size(), chunks.size());
        } catch (IOException e) {
            // 关键：不能"解析失败就当空库继续跑"。下一次入库会 register() → save()，
            // 直接把坏文件覆盖掉，全库的块 id 就永久没了（向量还在库里，但没人知道
            // 它们属于哪篇文档，删也删不掉）。先把坏文件改名留档，用户重建索引即可恢复。
            Path broken = registryPath.resolveSibling(
                    "registry.json.corrupt-" + System.currentTimeMillis());
            try {
                Files.move(registryPath, broken);
                log.error("文档注册表解析失败（{}），已备份为 {}；知识库将以空注册表启动，"
                        + "请在页面点『重建索引』按 data/docs 恢复", e.getMessage(), broken.getFileName());
            } catch (IOException moveError) {
                log.error("文档注册表解析失败（{}），且备份失败（{}），本次不回写注册表以免覆盖",
                        e.getMessage(), moveError.getMessage());
                readOnly = true;
            }
        }
    }

    /**
     * 去掉磁盘文件名前面的 {@code <docId>__} 前缀，还原成用户看到的原始文件名。
     * 实现见 {@link com.example.safetyrag.util.DocNames}。
     */
    public static String stripDocIdPrefix(String storedName) {
        return com.example.safetyrag.util.DocNames.stripDocIdPrefix(storedName);
    }

    private void save() {
        if (readOnly) {
            throw new UncheckedIOException(new IOException(
                    "注册表处于只读保护状态（启动时解析失败且无法备份），请先修复 data/registry.json"));
        }
        try {
            Files.createDirectories(registryPath.getParent());
            RegistryFile data = new RegistryFile(
                    new ArrayList<>(documents.values()), new ArrayList<>(chunks.values()));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(registryPath.toFile(), data);
        } catch (IOException e) {
            throw new UncheckedIOException("文档注册表保存失败", e);
        }
    }
}
