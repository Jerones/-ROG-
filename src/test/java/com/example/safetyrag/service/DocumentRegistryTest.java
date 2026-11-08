package com.example.safetyrag.service;

import com.example.safetyrag.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 注册表落盘 / 重载 / 损坏自愈。 */
class DocumentRegistryTest {

    private static DocumentRegistry registryAt(Path dir) {
        AppProperties props = new AppProperties(false, dir.toString(), null, null);
        return new DocumentRegistry(new DocumentRegistry.AppPropertiesHolder(props));
    }

    @Test
    void registerThenReload_keepsChunksAndStripsPrefix(@TempDir Path dir) throws Exception {
        DocumentRegistry first = registryAt(dir);
        Document doc = new Document("chunk-1", "第一条 高处作业应系挂安全带。",
                Map.of("clause", "第一条", "chunkIndex", 0));
        first.register("abc12345", "abc12345__JGJ80-2016.pdf", 1024, List.of(doc));

        // 重新构造 = 模拟重启后从 registry.json 读回
        DocumentRegistry reloaded = registryAt(dir);
        assertEquals(1, reloaded.listDocuments().size());
        assertEquals("JGJ80-2016.pdf", reloaded.listDocuments().get(0).docName(),
                "落盘的 docName 带 docId 前缀时，加载后要还原成原始文件名");
        assertEquals(1, reloaded.listChunks().size());
        assertEquals("第一条", reloaded.listChunks().get(0).clause());
    }

    @Test
    void corruptFile_isBackedUpInsteadOfSilentlyOverwritten(@TempDir Path dir) throws Exception {
        Path broken = dir.resolve("registry.json");
        Files.writeString(broken, "{ 这不是合法 JSON ");

        DocumentRegistry registry = registryAt(dir);

        assertTrue(registry.listDocuments().isEmpty(), "解析失败时以空注册表启动");
        try (var files = Files.list(dir)) {
            Path backup = files
                    .filter(p -> p.getFileName().toString().startsWith("registry.json.corrupt-"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "损坏的注册表要改名留档，否则下一次入库会把它覆盖掉、块 id 永久丢失"));
            assertEquals("{ 这不是合法 JSON ", Files.readString(backup), "留档内容必须是原来那份坏数据");
        }
    }

    @Test
    void unregisterAll_returnsChunkIds(@TempDir Path dir) {
        DocumentRegistry registry = registryAt(dir);
        registry.register("doc00001", "a.pdf", 10, List.of(
                new Document("c1", "一", Map.of("chunkIndex", 0)),
                new Document("c2", "二", Map.of("chunkIndex", 1))));

        List<String> removed = registry.unregisterAll(List.of("doc00001"));
        assertEquals(2, removed.size());
        assertTrue(registry.listDocuments().isEmpty());
        assertTrue(registry.listChunks().isEmpty());
    }
}
