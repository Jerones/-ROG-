package com.example.safetyrag.web;

import com.example.safetyrag.service.ChatService;
import com.example.safetyrag.service.EvalService;
import com.example.safetyrag.service.IngestJobService;
import com.example.safetyrag.service.IngestPreviewService;
import com.example.safetyrag.service.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口层测试：只验证"请求进来 → 状态码与错误文案"这一段，
 * 业务逻辑由各自的单元测试覆盖（不启动数据库、不调模型，秒级完成）。
 *
 * <p>重点覆盖 {@link ApiExceptionHandler} 的映射：这些正是演示现场最容易撞上的
 * 输入错误，以前一律变成 500 + 堆栈，前端只能显示"HTTP 500"。</p>
 */
@WebMvcTest(controllers = {ChatController.class, KnowledgeBaseController.class})
class ApiErrorMappingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private KnowledgeBaseService knowledgeBaseService;

    @MockitoBean
    private IngestJobService ingestJobService;

    @MockitoBean
    private IngestPreviewService ingestPreviewService;

    // 评测接口也挂在 KnowledgeBaseController 上：@WebMvcTest 只扫 web 层，
    // 没这个 mock 整个上下文都起不来（表现为 5 个用例一起报
    // "No qualifying bean of type EvalService"）。
    @MockitoBean
    private EvalService evalService;

    @Test
    void blankQuestion_returns400WithReadableMessage() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("问题不能为空"));
    }

    @Test
    void unknownJob_returns404() throws Exception {
        given(ingestJobService.get(any())).willReturn(null);

        mockMvc.perform(get("/api/kb/uploads/job-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("任务不存在")));
    }

    @Test
    void uploadWithoutFilePart_returns400() throws Exception {
        mockMvc.perform(post("/api/kb/documents").contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("file")));
    }

    @Test
    void unsupportedFileType_returns400NotServerError() throws Exception {
        given(knowledgeBaseService.upload(any()))
                .willThrow(new IllegalArgumentException("不支持的文件类型：a.png"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/kb/documents")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "a.png", "image/png", new byte[]{1, 2, 3})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不支持的文件类型")));
    }

    @Test
    void writeConflict_returns409() throws Exception {
        given(knowledgeBaseService.reindex())
                .willThrow(new IllegalStateException("注册表处于只读保护状态"));

        mockMvc.perform(post("/api/kb/reindex"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("只读保护")));
    }
}
