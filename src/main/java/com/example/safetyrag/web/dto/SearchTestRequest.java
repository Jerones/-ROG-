package com.example.safetyrag.web.dto;

/** 检索测试请求。 */
public record SearchTestRequest(String query, Integer topK) {
}
