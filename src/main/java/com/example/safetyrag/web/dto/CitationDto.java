package com.example.safetyrag.web.dto;

/**
 * 一条引用溯源（前端渲染为回答下方的引用卡片）。
 *
 * @param docName 来源文档名
 * @param clause  条款号（如"第十二条"），解析不到时为"正文片段"
 * @param page    PDF 页码，TXT/MD 来源为 null
 * @param snippet 条款原文片段
 * @param score   混合检索最终分数（0~1 左右，越高越相关）
 */
public record CitationDto(String docName, String clause, Integer page,
                          String snippet, double score) {
}
