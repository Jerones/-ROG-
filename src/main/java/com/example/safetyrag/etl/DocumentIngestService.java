package com.example.safetyrag.etl;

import com.example.safetyrag.config.AppProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文档入库的 ETL 第一段：读取（Extract）+ 切分（Transform）。
 *
 * <p>读取按文件类型分发到 DocumentReader：</p>
 * <ul>
 *   <li>PDF → {@link PagePdfDocumentReader}：逐页读取，每页一个 Document，
 *       页码会进 metadata，最终体现在引用卡片上；</li>
 *   <li>TXT / Markdown → {@link TextReader}：整文件一个 Document；</li>
 *   <li>Word / WPS / PPT / Excel / HTML → {@link TikaDocumentReader}：
 *       施工现场的规范多以 Word 下发，Tika 一并覆盖；</li>
 * </ul>
 *
 * <p>切分统一走自研 {@link ClauseTextSplitter}（条款优先）。切分后在这里
 * 统一补齐 metadata：docId、docName、clause（从块首解析）、chunkIndex，
 * 页码沿用 Reader 给出的 page_number 并改名为 page。</p>
 */
@Service
public class DocumentIngestService {

    private final ClauseTextSplitter splitter;

    public DocumentIngestService(AppProperties properties) {
        this.splitter = new ClauseTextSplitter(
                properties.chunking().chunkSize(), properties.chunking().overlap());
    }

    /** 读取内存中的上传文件并切分。 */
    public List<Document> readAndSplit(byte[] bytes, String fileName, String docId) {
        Resource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        return split(read(resource, fileName), fileName, docId);
    }

    /** 读取磁盘上的文件并切分（重建索引、示例文档初始化时用）。 */
    public List<Document> readAndSplit(Path path, String docId) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            return readAndSplit(bytes, path.getFileName().toString(), docId);
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败：" + path, e);
        }
    }

    /** 按扩展名选择 DocumentReader。 */
    private List<Document> read(Resource resource, String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            // PDF 单独走这里：PagePdfDocumentReader 会给 page_number，
            // 引用卡片上的"第 47 页"就是它带出来的，Tika 拿不到页码。
            return new PagePdfDocumentReader(resource).get();
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")) {
            TextReader reader = new TextReader(resource);
            reader.getCustomMetadata().put("charset", StandardCharsets.UTF_8.name());
            return reader.get();
        }
        if (isTikaFormat(lower)) {
            // Word / WPS / PPT / Excel / HTML：施工现场下发的规范多是 docx，
            // 只认 PDF 会让用户第一次上传就失败，这里统一交给 Tika。
            List<Document> docs = new TikaDocumentReader(resource).get();
            // Tika 只返回正文文本，没有页码；页眉页脚、目录、修订记录也常混进来，
            // 由后面的条款切分与条款号解析兜住（识别不到条款号的块会回溯沿用上一条）。
            return docs;
        }
        throw new IllegalArgumentException(DocumentIngestService.UNSUPPORTED_HINT + fileName);
    }

    /** 不支持的类型统一提示（前端 accept 与后端白名单保持同一口径）。 */
    public static final String UNSUPPORTED_HINT =
            "不支持的文件类型（支持 PDF / Word:docx,doc,wps / TXT / MD / PPTX / XLSX / HTML）：";

    /** 这个文件名能不能解析（上传前先挡掉，别等落盘了才报错）。 */
    public boolean supports(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pdf")
                || lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")
                || isTikaFormat(lower);
    }

    /** Tika 能处理的扩展名（PDF、纯文本已单独分流）。 */
    private static boolean isTikaFormat(String lowerName) {
        return lowerName.endsWith(".doc") || lowerName.endsWith(".docx")
                || lowerName.endsWith(".wps") || lowerName.endsWith(".dps")
                || lowerName.endsWith(".ppt") || lowerName.endsWith(".pptx")
                || lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx")
                || lowerName.endsWith(".rtf")
                || lowerName.endsWith(".htm") || lowerName.endsWith(".html");
    }

    /** 兜底切分把一条拆成多块时，最多向后沿用条款号几块（超过就认为已经跨条，不再乱标）。 */
    private static final int MAX_LABEL_CARRY = 3;

    /** 切分 + 补齐 metadata。 */
    private List<Document> split(List<Document> rawDocs, String fileName, String docId) {
        List<Document> allChunks = new ArrayList<>();
        int index = 0;
        for (Document raw : rawDocs) {
            // 先给原文打上 docId/docName，TextSplitter 会把 metadata 复制到每个块
            raw.getMetadata().put("docId", docId);
            raw.getMetadata().put("docName", fileName);
            // PDF 的页码字段统一改名 page（PagePdfDocumentReader 给的是 page_number）
            Object pageNumber = raw.getMetadata().get("page_number");
            if (pageNumber != null) {
                raw.getMetadata().put("page", pageNumber);
            }
            // 条款号按"顺序"补：块首认不出来的，沿用上一条的条款号。
            // 为什么必须顺序遍历：一条超长条款被兜底切成 3 块时，只有第一块带条款号，
            // 后两块若各自判成"正文片段"，引用卡片就又退化成"正文片段"了。
            String lastStrongLabel = null;
            int carry = 0;
            for (Document chunk : splitter.apply(List.of(raw))) {
                String text = chunk.getText() == null ? "" : chunk.getText();
                String firstLine = text.split("\n", 2)[0];
                // 行首就是条款号 = 强证据：这一块确实以该条开头，可以向后传；
                // 只在正文中间蹭到一个编号 = 弱证据：本块用得上，但不往后传
                // （否则评分表里"……悬挑长度的 1.25 倍"会把后面几行表格都标成 1.25）。
                String strong = ClauseLabels.lineLabel(firstLine);
                String label = strong != null ? strong : ClauseLabels.find(text);
                if (strong != null) {
                    lastStrongLabel = strong;
                    carry = 0;
                } else if (label == null) {
                    carry++;
                    label = (lastStrongLabel != null && carry <= MAX_LABEL_CARRY)
                            ? lastStrongLabel : ClauseLabels.NONE;
                }
                chunk.getMetadata().put("chunkIndex", index++);
                chunk.getMetadata().put("clause", label);
                allChunks.add(chunk);
            }
        }
        return allChunks;
    }
}
