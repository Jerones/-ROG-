package com.example.safetyrag.web;

import com.example.safetyrag.rag.RetrievedChunk;
import com.example.safetyrag.service.ChatService;
import com.example.safetyrag.web.dto.ChatRequest;
import com.example.safetyrag.web.dto.ChatResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 对话接口（前端"对话菜单"调用）。
 *
 * <ul>
 *   <li>POST /api/chat —— 普通问答，一次性返回答案 + 引用列表；</li>
 *   <li>POST /api/chat/stream —— 流式问答（SSE）：先逐段推送 token 事件，
 *       正文结束后推送一个 citations 事件（引用列表 JSON）和 done 事件，
 *       前端据此在回答下方渲染引用溯源卡片。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    public ChatController(ChatService chatService, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        validate(request);
        return chatService.ask(request);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest request) {
        validate(request);
        // 先做检索：引用列表在流开始前就已确定，正文流完后一次性下发
        ChatService.RetrievalOutcome outcome = chatService.retrieve(request);
        List<RetrievedChunk> hits = outcome.hits();

        Flux<ServerSentEvent<String>> tokens = chatService.streamTokens(request, hits)
                .map(token -> ServerSentEvent.<String>builder(token).event("token").build());

        String citationsJson;
        try {
            citationsJson = objectMapper.writeValueAsString(outcome.citations());
        } catch (JsonProcessingException e) {
            citationsJson = "[]";
        }
        Flux<ServerSentEvent<String>> tail = Flux.just(
                ServerSentEvent.<String>builder(citationsJson).event("citations").build(),
                ServerSentEvent.<String>builder("").event("done").build());
        return Flux.concat(tokens, tail);
    }

    /** 空问题直接 400：否则会带着 null 去拼检索请求，最后变成一句无关的 500。 */
    private static void validate(ChatRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题不能为空");
        }
    }
}
