package com.example.safetyrag.web.dto;

/** 知识库文档列表项。 */
public record DocumentDto(String docId, String docName, long fileSize,
                          int chunkCount, String ingestedAt, String status) {
}
