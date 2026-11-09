package com.example.safetyrag.web.dto;

import java.util.List;

/**
 * 「查看入库内容」的一页结果。
 *
 * <p>{@code total} 是这篇文档的块总数，{@code matched} 是关键字过滤后的条数——
 * 两者分开返回，页面才能区分"这篇只有 12 块"和"搜关键字只剩 2 块"，
 * 分页也才知道该按哪个数字算页数。</p>
 *
 * @param docId   文档 id
 * @param docName 原始文件名（已去掉磁盘前缀）
 * @param total   该文档的块总数
 * @param matched 关键字过滤后的条数（无关键字时等于 total）
 * @param offset  本页起始下标
 * @param limit   本页最多条数
 * @param chunks  本页的块
 */
public record ChunkPageDto(String docId, String docName, int total, int matched,
                           int offset, int limit, List<ChunkDto> chunks) {
}
