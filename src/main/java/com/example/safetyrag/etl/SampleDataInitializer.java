package com.example.safetyrag.etl;

import com.example.safetyrag.service.DocumentRegistry;
import com.example.safetyrag.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 首次启动时自动把 sample_docs/ 下的示例规范导入 data/docs 并入库，
 * 保证"下载下来第一次运行"就能直接提问演示，不必先手动上传。
 *
 * <p>只在注册表为空时执行；向量库不可用（如 Chroma 没启动）时只记录
 * 警告、不让应用启动失败，稍后在知识库菜单点"重建索引"即可补上。</p>
 */
@Component
public class SampleDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleDataInitializer.class);

    private static final Path SAMPLE_DIR = Path.of("./sample_docs");

    /** 匹配 "#U65bd" 这样的转义块（UTF-16 码元的 4 位十六进制）。 */
    private static final Pattern ESCAPED_CHAR = Pattern.compile("#U([0-9a-fA-F]{4})");

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentRegistry registry;
    /** 目标目录跟随 app.data-dir：之前写死 ./data/docs，换数据目录后示例文件会落到别处。 */
    private final Path docsDir;

    public SampleDataInitializer(KnowledgeBaseService knowledgeBaseService,
                                 DocumentRegistry registry,
                                 com.example.safetyrag.config.AppProperties properties) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.registry = registry;
        this.docsDir = Path.of(properties.dataDirOrDefault(), "docs");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!registry.listDocuments().isEmpty() || !Files.isDirectory(SAMPLE_DIR)) {
            return;
        }
        try (var stream = Files.list(SAMPLE_DIR)) {
            List<Path> samples = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".pdf");
                    })
                    .sorted()
                    .toList();
            // 内容去重：sample_docs 里若有同内容的重复文件（例如解压时文件名被
            // 转码成 #Uxxxx 形式多出一份），按 SHA-256 跳过，避免示例文档入库两次。
            Set<String> seenHashes = new HashSet<>();
            for (Path sample : samples) {
                String hash = sha256(sample);
                if (!seenHashes.add(hash)) {
                    log.info("跳过内容重复的示例文件：{}", sample.getFileName());
                    continue;
                }
                String docId = UUID.randomUUID().toString().substring(0, 8);
                // 有的压缩/下载工具会把中文文件名转成 "#U65bd#U5de5..." 这种形式，
                // 直接用它入库会让前端文档列表显示一串乱码，这里先还原。
                String displayName = decodeEscapedName(sample.getFileName().toString());
                Path target = docsDir.resolve(docId + "__" + displayName);
                Files.createDirectories(target.getParent());
                Files.copy(sample, target);
                knowledgeBaseService.ingestFromPath(target, docId);
                log.info("示例文档已自动入库：{}", displayName);
            }
        } catch (Exception e) {
            log.warn("示例文档自动入库失败（不影响启动，可稍后在知识库菜单点『重建索引』）：{}",
                    e.getMessage());
        }
    }

    /**
     * 还原被转义成 "#U65bd#U5de5#U5b89..." 形式的文件名。
     *
     * <p>部分压缩包/下载工具会把非 ASCII 文件名按 UTF-16 逐字符转成 {@code #U%04x}，
     * 这种名字既难读，又会在排序时排到中文名前面，导致重复文件里恰好是它被入库、
     * 前端列表显示一串乱码。这里按 UTF-16 码元还原。</p>
     */
    static String decodeEscapedName(String name) {
        if (name == null || !name.contains("#U")) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        Matcher m = ESCAPED_CHAR.matcher(name);
        int last = 0;
        while (m.find()) {
            sb.append(name, last, m.start());
            sb.append((char) Integer.parseInt(m.group(1), 16));
            last = m.end();
        }
        sb.append(name.substring(last));
        return sb.toString();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(file));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
