package com.example.safetyrag.web;

import com.example.safetyrag.service.IngestJobService;
import com.example.safetyrag.service.IngestPreviewService;
import com.example.safetyrag.service.KnowledgeBaseService;
import com.example.safetyrag.web.dto.DocumentDto;
import com.example.safetyrag.web.dto.PasteRequest;
import com.example.safetyrag.web.dto.PreviewDto;
import com.example.safetyrag.web.dto.SearchHitDto;
import com.example.safetyrag.web.dto.SearchTestRequest;
import com.example.safetyrag.web.dto.StatsDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口（前端"知识库菜单"调用）。
 *
 * <ul>
 *   <li>GET    /api/kb/documents            文档列表</li>
 *   <li>POST   /api/kb/preview              入库预检：解析但不写库，返回块数/条款率/重复/版本提示</li>
 *   <li>POST   /api/kb/uploads              批量上传（字段名 files，异步入库，立刻返回 jobId）</li>
 *   <li>GET    /api/kb/uploads/{jobId}      查询批量任务进度</li>
 *   <li>POST   /api/kb/paste                粘贴文本入库（JSON：title + text）</li>
 *   <li>POST   /api/kb/documents            上传单个文档（multipart，字段名 file，同步入库）</li>
 *   <li>DELETE /api/kb/documents?docId=xxx  删除文档</li>
 *   <li>POST   /api/kb/reindex              重建索引</li>
 *   <li>GET    /api/kb/stats                索引统计</li>
 *   <li>POST   /api/kb/search-test          检索测试（只召回不生成）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final IngestJobService ingestJobService;
    private final IngestPreviewService previewService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService,
                                   IngestJobService ingestJobService,
                                   IngestPreviewService previewService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.ingestJobService = ingestJobService;
        this.previewService = previewService;
    }

    @GetMapping("/documents")
    public List<DocumentDto> listDocuments() {
        return knowledgeBaseService.listDocuments();
    }

    @PostMapping("/documents")
    public DocumentDto upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空");
        }
        // 异常统一交给 ApiExceptionHandler：类型不支持→400，写库冲突/失败→409/500，
        // 不用在这里逐层包装，前端拿到的 message 也一样可读。
        return knowledgeBaseService.upload(file);
    }

    /**
     * 入库预检：解析、切分、统计，但**不写向量库**。
     *
     * <p>前端在用户选完文件、点"入库"之前调它，用来回答三个问题：
     * 会切成多少块（要等多久）、能不能定位到条款号、库里是不是已经有这篇了。</p>
     */
    @PostMapping("/preview")
    public List<PreviewDto> preview(@RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有收到文件");
        }
        List<PreviewDto> results = new ArrayList<>();
        for (MultipartFile file : files) {
            String name = KnowledgeBaseService.normalizeFileName(file);
            try {
                results.add(previewService.preview(name, file.getBytes()));
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取文件失败：" + name);
            }
        }
        return results;
    }

    /**
     * 粘贴入库：把一段条文直接交给知识库，不经过挑文件的动作。
     *
     * <p>安全员的真实工作流常常是"从微信/QQ 里复制一段条文"，先存成文件再上传
     * 反而多绕一步。这里落成 txt 后走和上传完全相同的入库链路。</p>
     */
    @PostMapping("/paste")
    public Map<String, Object> paste(@RequestBody PasteRequest request) {
        String text = request.text();
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "粘贴内容为空");
        }
        if (text.length() > 500_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "粘贴内容过长（" + text.length() + " 字），超过 50 万字请改用上传文件");
        }
        return jobView(ingestJobService.submitText(request.title(), text));
    }

    /**
     * 批量上传：只落盘、立刻返回任务号，入库交给后台线程。
     *
     * <p>字段名用 {@code files}（浏览器 FormData 里 append 多次同名字段即可）。</p>
     */
    @PostMapping("/uploads")
    public Map<String, Object> submitUploads(@RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有收到文件");
        }
        IngestJobService.Job job = ingestJobService.submit(files);
        return jobView(job);
    }

    /** 轮询批量任务进度。 */
    @GetMapping("/uploads/{jobId}")
    public Map<String, Object> uploadStatus(@PathVariable("jobId") String jobId) {
        IngestJobService.Job job = ingestJobService.get(jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "任务不存在或已过期：" + jobId + "（服务重启后任务记录会清空）");
        }
        return jobView(job);
    }

    private static Map<String, Object> jobView(IngestJobService.Job job) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (IngestJobService.FileTask task : job.files()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", task.name());
            item.put("docId", task.docId());
            item.put("size", task.size());
            item.put("stage", task.stage());
            item.put("status", task.status());
            item.put("written", task.written());
            item.put("total", task.total());
            item.put("message", task.message());
            items.add(item);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("jobId", job.id());
        view.put("finished", job.finished());
        view.put("files", items);
        return view;
    }

    @DeleteMapping("/documents")
    public Map<String, Object> delete(@RequestParam("docId") String docId) {
        boolean removed = knowledgeBaseService.delete(docId);
        if (!removed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在：" + docId);
        }
        return Map.of("deleted", true, "docId", docId);
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        KnowledgeBaseService.ReindexResult result = knowledgeBaseService.reindex();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("reindexedDocuments", result.documents());
        view.put("skippedDuplicates", result.skipped());
        view.put("failed", result.failed());
        return view;
    }

    @GetMapping("/stats")
    public StatsDto stats() {
        return knowledgeBaseService.stats();
    }

    @PostMapping("/search-test")
    public List<SearchHitDto> searchTest(@RequestBody SearchTestRequest request) {
        int topK = request.topK() == null ? 5 : Math.max(1, Math.min(request.topK(), 20));
        return knowledgeBaseService.searchTest(request.query(), topK);
    }
}
