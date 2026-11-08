package com.example.safetyrag.web.dto;

/** 检索测试的一条召回结果。 */
public record SearchHitDto(String docName, String clause, Integer page,
                           String snippet, double score) {
}
