package com.example.safetyrag.web.dto;

/**
 * 索引统计（知识库菜单顶部卡片）。
 *
 * @param vectorDimension 向量维度，0 表示尚未入库、无从得知
 */
public record StatsDto(long documentCount, long chunkCount, int vectorDimension,
                       String embeddingModel, String vectorStore, boolean demoMode) {
}
