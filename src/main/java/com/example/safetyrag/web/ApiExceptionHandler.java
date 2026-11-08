package com.example.safetyrag.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常映射：把后端异常翻译成"前端能直接显示的一句话 + 合适的 HTTP 状态码"。
 *
 * <p>没有这一层时，所有异常最后都变成 500 + 一大段堆栈，前端只能显示
 * "入库失败：HTTP 500"——用户既不知道是格式不支持、文件太大，还是向量库没起来。
 * 课设演示时现场最容易遇到的恰好是这些：传了个 .png、传了个 60MB 的 PDF、
 * 一边重建索引一边上传。</p>
 *
 * <p>响应体统一为 {@code {"status": 400, "message": "..."}}，字段名与 Spring 默认
 * 错误体保持一致，前端 {@code err.message} 的读取逻辑不用改。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 参数类问题：文件类型不支持、内容为空、粘贴超长…… */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onBadArgument(IllegalArgumentException e) {
        return build(400, messageOf(e));
    }

    /** 状态冲突：知识库正在被另一个写操作占用、注册表只读保护…… */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> onIllegalState(IllegalStateException e) {
        return build(409, messageOf(e));
    }

    /** 上传超过 multipart 上限（默认单文件 50MB）。不给这层会变成一句 500。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> onTooLarge(MaxUploadSizeExceededException e) {
        return build(413, "文件超过大小上限，请拆分后再上传（当前单文件上限 50MB）");
    }

    /** 请求体不是合法 JSON，或缺了 files/file 这类必填字段。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> onBadBody(HttpMessageNotReadableException e) {
        return build(400, "请求体格式错误，请检查 JSON 是否合法");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> onMissingPart(MissingServletRequestPartException e) {
        return build(400, "缺少上传字段：" + e.getRequestPartName());
    }

    /** 业务代码里主动抛出的状态码（如 404 文档不存在）原样透出。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onStatus(ResponseStatusException e) {
        String reason = e.getReason() == null ? e.getStatusCode().toString() : e.getReason();
        return build(e.getStatusCode().value(), reason);
    }

    /**
     * 兜底：Spring 自己的错误（405 方法不支持、静态资源 404 等）都实现了
     * {@link ErrorResponse}，直接沿用它的状态码与描述；真正的未知异常才记堆栈日志。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onUnexpected(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatusCode code = errorResponse.getStatusCode();
            String detail = errorResponse.getBody().getDetail();
            return build(code.value(), detail == null ? code.toString() : detail);
        }
        log.error("接口未处理异常", e);
        return build(500, "服务器内部错误：" + messageOf(e));
    }

    private static ResponseEntity<Map<String, Object>> build(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    /** 取异常链最内层的信息——Spring 常把真正原因包在三四层 cause 里。 */
    private static String messageOf(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }
}
