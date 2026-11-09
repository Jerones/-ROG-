package com.example.safetyrag.web.dto;

import java.util.List;

/**
 * 对话请求。
 *
 * @param question 本轮问题
 * @param topK     召回条数（不传默认 5，后端限幅 1~20）
 * @param history  最近若干轮对话，按时间正序（旧→新）。为空表示单轮提问。
 *                 只用于「追问改写」——把「那颜色呢」这类依赖上下文的问题
 *                 补全成可独立检索的问句，不参与最终 Prompt 的上下文拼接。
 * @param docIds   限定检索范围（文档 docId 列表）。为空/不传 = 全库检索。
 */
public record ChatRequest(String question, Integer topK, List<Turn> history, List<String> docIds) {

    /** 一轮对话。role 取 "user" 或 "assistant"。 */
    public record Turn(String role, String content) {
    }

    public List<Turn> historyOrEmpty() {
        return history == null ? List.of() : history;
    }

    public List<String> docIdsOrEmpty() {
        return docIds == null ? List.of() : docIds;
    }
}
