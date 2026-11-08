package com.example.safetyrag.web.dto;

/** 对话请求：问题 + 召回条数（前端侧边栏）。 */
public record ChatRequest(String question, Integer topK) {
}
