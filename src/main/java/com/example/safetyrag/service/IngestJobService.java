package com.example.safetyrag.service;

import com.example.safetyrag.etl.DocumentIngestService;
import com.example.safetyrag.web.dto.DocumentDto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 批量入库任务：把"上传"从一次 HTTP 请求改成一个后台作业。
 *
 * <p>为什么需要它？入库不是毫秒级操作——586 个块的重新向量化要 1 分半钟，
 * 一篇 PDF 规范也要几千字的切分 + 十几次 embedding 调用。如果全部压在一个
 * POST 请求里，用户只能盯着"正在解析…"发呆，一个文件失败整批重来，
 * 网关/浏览器还可能先超时断连。</p>
 *
 * <p>改造后的流程：</p>
 * <ol>
 *   <li>{@code POST /api/kb/uploads} 只做落盘（纯 IO，毫秒级），立刻返回 jobId；</li>
 *   <li>单线程工作线程按顺序解析 → 切分 → 向量化，逐块上报进度；</li>
 *   <li>前端轮询 {@code GET /api/kb/uploads/{jobId}} 拿每个文件的状态；</li>
 *   <li>单个文件失败只影响它自己，其余继续，失败原因按文件带回页面。</li>
 * </ol>
 *
 * <p>为什么是单线程？embedding 接口有并发/配额限制，多线程同时打反而更容易
 * 触发 429；入库本身也不追求并行（用户看到的是逐条完成，而不是一起卡住）。</p>
 */
@Service
public class IngestJobService {

    private static final Logger log = LoggerFactory.getLogger(IngestJobService.class);

    /** 内存里最多保留多少个作业（超出按插入顺序淘汰，够页面翻看最近几次上传）。 */
    private static final int MAX_JOBS = 20;

    private final KnowledgeBaseService knowledgeBaseService;
    private final Path docsDir;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kb-ingest-worker");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Job> jobs = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Job> eldest) {
                    return size() > MAX_JOBS;
                }
            });
    private final AtomicLong seq = new AtomicLong();

    public IngestJobService(KnowledgeBaseService knowledgeBaseService,
                            com.example.safetyrag.config.AppProperties properties) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.docsDir = Path.of(properties.dataDirOrDefault(), "docs");
    }

    /** 一个文件在任务里的状态。字段都是 volatile：工作线程写、HTTP 线程读。 */
    public static class FileTask {
        private final String name;
        private final String docId;
        private final Path path;
        private final long size;
        private volatile String stage = "排队中";
        private volatile int written;
        private volatile int total;
        private volatile String message = "";
        private volatile String status = "PENDING";
        private volatile DocumentDto result;

        FileTask(String name, String docId, Path path, long size) {
            this.name = name;
            this.docId = docId;
            this.path = path;
            this.size = size;
        }

        public String name() {
            return name;
        }

        public String docId() {
            return docId;
        }

        public long size() {
            return size;
        }

        public String stage() {
            return stage;
        }

        public String status() {
            return status;
        }

        public int written() {
            return written;
        }

        public int total() {
            return total;
        }

        public String message() {
            return message;
        }

        public DocumentDto result() {
            return result;
        }
    }

    /** 一次批量上传。 */
    public static class Job {
        private final String id;
        private final long createdAt;
        private final List<FileTask> files = new ArrayList<>();
        private volatile boolean finished;

        Job(String id) {
            this.id = id;
            this.createdAt = System.currentTimeMillis();
        }

        public String id() {
            return id;
        }

        public List<FileTask> files() {
            return files;
        }

        public boolean finished() {
            return finished;
        }
    }

    /**
     * 接收一批文件：先落盘（同步、快），再交给后台线程入库。
     *
     * @return 作业对象，调用方拿 id 去轮询
     */
    public Job submit(List<MultipartFile> uploads) {
        String jobId = "job-" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
        Job job = new Job(jobId);
        for (MultipartFile file : uploads) {
            String name = KnowledgeBaseService.normalizeFileName(file);
            if (file.isEmpty()) {
                continue;
            }
            String docId = KnowledgeBaseService.newDocId();
            Path target = docsDir.resolve(docId + "__" + name);
            FileTask task = new FileTask(name, docId, target, file.getSize());
            try {
                knowledgeBaseService.saveUpload(file, target, name);
                job.files.add(task);
            } catch (IllegalArgumentException e) {
                // 类型不支持：不落盘，直接把失败原因挂在任务上，别让整批上传失败
                task.status = "FAILED";
                task.stage = "不支持";
                task.message = e.getMessage();
                job.files.add(task);
            } catch (IOException e) {
                task.status = "FAILED";
                task.stage = "保存失败";
                task.message = "文件保存失败：" + e.getMessage();
                job.files.add(task);
            }
        }
        jobs.put(jobId, job);
        if (job.files.isEmpty()) {
            job.finished = true;
            return job;
        }
        worker.submit(() -> runJob(job));
        log.info("已接收批量入库任务 {}，共 {} 个文件", jobId, job.files.size());
        return job;
    }

    /**
     * 接收一段直接粘贴进来的文本，落成 .txt 后按普通文件入库。
     *
     * <p>为什么也走文件而不是走一条"内存文档"通道：落盘是重建索引的前提——
     * 重建只扫 {@code data/docs}，粘贴进来的条文若只存在内存里，
     * 下一次点重建索引就凭空消失了。落成 txt 之后，粘贴与上传在后续流程里完全同构。</p>
     */
    public Job submitText(String title, String text) {
        String jobId = "job-" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
        Job job = new Job(jobId);
        String safeTitle = sanitizeTitle(title);
        String name = safeTitle + ".txt";
        String docId = KnowledgeBaseService.newDocId();
        Path target = docsDir.resolve(docId + "__" + name);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        FileTask task = new FileTask(name, docId, target, bytes.length);
        try {
            Files.write(target, bytes);
            job.files.add(task);
        } catch (IOException e) {
            task.status = "FAILED";
            task.stage = "保存失败";
            task.message = "文本保存失败：" + e.getMessage();
            job.files.add(task);
        }
        jobs.put(jobId, job);
        if ("FAILED".equals(task.status)) {
            job.finished = true;
            return job;
        }
        worker.submit(() -> runJob(job));
        log.info("已接收粘贴文本入库任务 {}：{}（{} 字）", jobId, name, text.length());
        return job;
    }

    /** 标题当文件名用：去掉路径分隔符等非法字符，兜底给一个默认名。 */
    private static String sanitizeTitle(String title) {
        String t = title == null ? "" : title.strip().replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
        if (t.isEmpty()) {
            t = "粘贴文本-" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMdd-HHmm"));
        }
        return t.length() > 60 ? t.substring(0, 60) : t;
    }

    /** 后台线程：顺序处理，逐文件上报进度。 */
    private void runJob(Job job) {
        try {
            for (FileTask task : job.files) {
                if ("FAILED".equals(task.status)) {
                    continue; // 落盘阶段就失败的，不用再试
                }
                task.status = "RUNNING";
                task.stage = "解析切分";
                try {
                    DocumentDto dto = knowledgeBaseService.ingestSavedFile(
                            task.path, task.docId, task.name, task.size,
                            (written, total) -> {
                                task.stage = "向量化";
                                task.written = written;
                                task.total = total;
                            });
                    task.result = dto;
                    task.written = task.total > 0 ? task.total : dto.chunkCount();
                    task.total = task.written;
                    task.stage = "已完成";
                    task.status = "DONE";
                } catch (Exception e) {
                    task.status = "FAILED";
                    task.stage = "失败";
                    // 用包装后的信息（含"源文件已保留、可重建索引补录"这类可操作提示），
                    // 而不是最底层的 "Connection reset"——那对用户没有任何指导意义。
                    task.message = friendlyMessage(e);
                    // 源文件**保留**：失败发生在向量写入/登记之前，注册表里没有它，
                    // 磁盘上留一份不会产生脏数据，网络恢复后点「重建索引」即可补录。
                    log.warn("文件入库失败：{}（{}），源文件已保留", task.name, task.message);
                }
            }
        } finally {
            job.finished = true;
            log.info("批量入库任务 {} 结束", job.id());
        }
    }

    public Job get(String jobId) {
        return jobs.get(jobId);
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }

    /** 前端展示用的失败原因：优先用包装后的完整说明，过长的截断，避免撑坏进度条那一行。 */
    private static String friendlyMessage(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return rootMessage(e);
        }
        return msg.length() > 200 ? msg.substring(0, 200) + "…" : msg;
    }
}
