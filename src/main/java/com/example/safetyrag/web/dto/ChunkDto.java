package com.example.safetyrag.web.dto;

/**
 * 知识库里的一个文本块（"查看入库内容"用）。
 *
 * <p>没有带向量：向量是 1024 个浮点数，对使用者没有意义，页面也渲染不了，
 * 真要看向量请直接查 Chroma 的 {@code get} 接口。</p>
 *
 * @param chunkId    块 id（与向量库中的 Document id 一致，可直接用于按 id 检索）
 * @param clause     条款号，定位不到时为"正文片段"
 * @param chunkIndex 该块在文档内的序号，从 0 开始
 * @param page       页码，txt/docx 这类无分页的文档为 {@code null}
 * @param chars      该块的字数
 * @param text       块的原文（BM25 索引与引用卡片用的都是这一份）
 */
public record ChunkDto(String chunkId, String clause, int chunkIndex,
                       Integer page, int chars, String text) {
}
