package com.example.safetyrag.service;

import com.example.safetyrag.config.AppProperties;
import com.example.safetyrag.etl.ClauseLabels;
import com.example.safetyrag.etl.DocumentIngestService;
import com.example.safetyrag.util.DocNames;
import com.example.safetyrag.util.StandardCode;
import com.example.safetyrag.web.dto.PreviewDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 入库预检（dry-run）：解析一遍、算清楚会发生什么，但**不写向量库、不登记注册表**。
 *
 * <p>写在入库之前而不是之后，是因为入库的代价不对称：解析几秒钟，
 * 写 300 条向量要几十秒、还要占用 embedding 配额。先把"会切多少块、
 * 有没有重复、是不是扫描件"摆出来，用户确认了再付这个代价。</p>
 *
 * <p>预检只做四件事，都不涉及网络调用：</p>
 * <ol>
 *   <li>解析 + 切分，统计字数、块数、条款号覆盖率；</li>
 *   <li>SHA-256 与 data/docs 下的现有文件比对，找出完全相同的重复文档；</li>
 *   <li>从文件名抽标准编号，与库里已有文档比对年号，提示旧版/新版；</li>
 *   <li>给出前 3 块的样本，肉眼确认切分是否合理。</li>
 * </ol>
 */
@Service
public class IngestPreviewService {

    private static final Logger log = LoggerFactory.getLogger(IngestPreviewService.class);

    /**
     * 与 CachingEmbeddingModel.MAX_BATCH 保持一致：实际按 5 条/请求批量预取。
     * 接口上限是 10 条，但批越大响应体越大、越容易在传输中途被重置（详见类注释中的实测表）。
     */
    private static final int EMBED_BATCH_SIZE = 5;

    /** 预检时最多返回几块样本。 */
    private static final int SAMPLE_LIMIT = 3;

    /** 样本正文截断长度。 */
    private static final int SAMPLE_TEXT_LIMIT = 160;

    private final DocumentIngestService ingestService;
    private final Path docsDir;

    public IngestPreviewService(DocumentIngestService ingestService, AppProperties properties) {
        this.ingestService = ingestService;
        this.docsDir = Path.of(properties.dataDirOrDefault(), "docs");
    }

    /** 预检一个文件。解析失败也会返回一个 {@code supported=false} 的结果，而不是抛异常。 */
    public PreviewDto preview(String name, byte[] bytes) {
        List<String> warnings = new ArrayList<>();
        if (!ingestService.supports(name)) {
            warnings.add("不支持的文件类型，入库会被拒绝");
            return new PreviewDto(name, false, bytes.length, 0, 0, 0, 0, 0,
                    null, null, List.of(), warnings);
        }

        List<Document> chunks;
        try {
            chunks = ingestService.readAndSplit(bytes, name, "preview");
        } catch (RuntimeException e) {
            warnings.add("解析失败：" + rootMessage(e));
            return new PreviewDto(name, true, bytes.length, 0, 0, 0, 0, 0,
                    null, null, List.of(), warnings);
        }

        int chars = 0;
        int clauseCount = 0;
        List<PreviewDto.Sample> samples = new ArrayList<>();
        for (Document chunk : chunks) {
            String text = chunk.getText() == null ? "" : chunk.getText();
            chars += text.length();
            String clause = String.valueOf(chunk.getMetadata().getOrDefault("clause", ClauseLabels.NONE));
            if (!ClauseLabels.NONE.equals(clause)) {
                clauseCount++;
            }
            if (samples.size() < SAMPLE_LIMIT) {
                samples.add(new PreviewDto.Sample(clause, oneLine(text, SAMPLE_TEXT_LIMIT)));
            }
        }
        int percent = chunks.isEmpty() ? 0 : clauseCount * 100 / chunks.size();

        // ---- 与库内文档比对 ----
        Map<String, String> byHash = existingHashes();
        String duplicateOf = chunks.isEmpty() ? null : byHash.get(sha256(bytes));

        StandardCode mine = StandardCode.find(name);
        String versionWarning = versionWarning(mine, existingNames());

        // ---- 风险提示 ----
        if (chunks.isEmpty()) {
            warnings.add("没有提取到任何文字。扫描版 PDF、图片、加密文档都会这样，需要先做 OCR");
        } else {
            if (chars < 300) {
                warnings.add("只提取到 " + chars + " 字，可能不是正文（封面、目录页、图片型文档）");
            }
            if (percent < 30) {
                warnings.add("仅 " + percent + "% 的块能定位到条款号，可能是以表格/图片为主的文档，"
                        + "引用卡片会退化成\"正文片段 + 页码\"");
            }
            if (duplicateOf != null) {
                warnings.add("与库中《" + duplicateOf + "》内容完全相同（SHA-256 一致），"
                        + "再传一遍会产生整篇重复的块");
            }
            if (versionWarning != null) {
                warnings.add(versionWarning);
            }
        }

        return new PreviewDto(name, true, bytes.length, chars, chunks.size(), clauseCount, percent,
                chunks.isEmpty() ? 0 : (chunks.size() + EMBED_BATCH_SIZE - 1) / EMBED_BATCH_SIZE,
                duplicateOf, mine == null ? null : mine.display(), samples, warnings);
    }

    // ---------------- 内部 ----------------

    /** 同一部标准换年了：库里已有 2011，这次是 2016，要提醒用户处理旧版。 */
    private static String versionWarning(StandardCode mine, List<String> existingNames) {
        if (mine == null) {
            return null;
        }
        for (String existing : existingNames) {
            StandardCode other = StandardCode.find(existing);
            if (other == null || !other.code().equals(mine.code())) {
                continue;
            }
            if (other.year().equals(mine.year())) {
                return "库里已有同一版本（" + other.display() + "）：《" + existing + "》";
            }
            boolean newer = Integer.parseInt(mine.year()) > Integer.parseInt(other.year());
            return "库里已有 " + other.display() + "（《" + existing + "》），本文件是 "
                    + mine.display() + (newer ? "，属于新版，旧版应作废" : "，属于旧版，不建议入库");
        }
        return null;
    }

    /** data/docs 下现有文件的哈希 → 文档名。每次预检重算：文件只有几个，比维护缓存更不容易错。 */
    private Map<String, String> existingHashes() {
        Map<String, String> map = new HashMap<>();
        try (var stream = Files.list(docsDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    map.put(sha256(Files.readAllBytes(p)),
                            DocNames.stripDocIdPrefix(p.getFileName().toString()));
                } catch (Exception e) {
                    log.debug("计算已有文件哈希失败（跳过）：{}", p.getFileName());
                }
            });
        } catch (IOException e) {
            log.debug("读取 data/docs 失败，跳过重复检测：{}", e.getMessage());
        }
        return map;
    }

    private List<String> existingNames() {
        try (var stream = Files.list(docsDir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> DocNames.stripDocIdPrefix(p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String oneLine(String text, int max) {
        String t = text.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }
}
