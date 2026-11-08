package com.example.safetyrag.web.dto;

/** 粘贴入库请求：一段条文文本 + 一个标题（标题会成为文档名）。 */
public record PasteRequest(String title, String text) {
}
