package com.example.safetyrag.web.dto;

import java.util.List;

/**
 * 对话响应。
 *
 * @param answer    回答正文
 * @param found     是否有召回（false 时 answer 为"未找到相关规范"的固定提示）
 * @param demoMode  当前是否处于演示模式（前端据此显示角标）
 * @param citations 引用溯源列表
 */
public record ChatResponse(String answer, boolean found, boolean demoMode,
                           List<CitationDto> citations) {
}
