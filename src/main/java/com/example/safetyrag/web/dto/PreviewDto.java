package com.example.safetyrag.web.dto;

import java.util.List;

/**
 * 入库预检结果：**只看不动库**。
 *
 * <p>上传一个文件最怕的情况是——等了半分钟、写了几百条向量，最后发现
 * 文档是扫描件（一个字都没提取到）或者和库里已有的那篇一模一样。
 * 预检在写库之前把这些都说清楚：切多少块、能不能定位到条款号、
 * 要调几次 embedding、与库里哪篇重复、是不是同一部标准的另一个年号。</p>
 */
public record PreviewDto(
        String name,
        boolean supported,
        long size,
        /** 提取到的总字数。 */
        int chars,
        /** 预计切成多少块。 */
        int chunks,
        /** 能定位到具体条款号的块数。 */
        int clauseCount,
        /** 条款号覆盖率（百分比，0-100）。 */
        int clausePercent,
        /** 预计要调几次 embedding 接口（按每批 10 条估算），决定入库要等多久。 */
        int embeddingCalls,
        /** 与库中某篇文档内容完全相同时，给出那篇文档名；否则为 null。 */
        String duplicateOf,
        /** 文件名里识别到的标准编号，例如 JGJ 80-2016。 */
        String standard,
        /** 前几块的样子，让用户确认切分对不对。 */
        List<Sample> samples,
        List<String> warnings) {

    /** 一段预览样本：条款号 + 正文开头。 */
    public record Sample(String clause, String text) {
    }
}
