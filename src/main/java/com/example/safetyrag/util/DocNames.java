package com.example.safetyrag.util;

import java.util.regex.Pattern;

/**
 * 文档名展示工具。
 *
 * <p>本工程为了"同名文件不互相覆盖"，磁盘上的原文件名为
 * {@code <docId>__<原始文件名>}（如 {@code 68ce8e61__施工安全规范.txt}）。
 * 这个前缀只对磁盘有意义，展示给用户时必须去掉——文档列表、检索结果、
 * 回答下方的引用卡片都要用原始文件名。</p>
 *
 * <p>注意：这个前缀有三处来源，都必须在展示前清洗——注册表（旧数据里就带着）、
 * 向量库里的 metadata（入库时写死的）、磁盘文件名本身。所以把它收成一个工具类，
 * 而不是在某一处修。</p>
 */
public final class DocNames {

    /** 磁盘文件名前缀：docId = UUID 前 8 位，见 KnowledgeBaseService#upload。 */
    private static final Pattern DOC_ID_PREFIX = Pattern.compile("^[0-9a-f]{8}__");

    private DocNames() {
    }

    /**
     * 去掉 {@code <docId>__} 前缀，还原成用户看到的原始文件名。
     *
     * <p>只认"8 位十六进制 + 双下划线"这种本工程自己加的前缀，避免把用户
     * 文件名里本来就有的 {@code __}（如 {@code 方案__v2.docx}）误删。</p>
     */
    public static String stripDocIdPrefix(String storedName) {
        if (storedName == null || storedName.isEmpty()) {
            return storedName;
        }
        return DOC_ID_PREFIX.matcher(storedName).replaceFirst("");
    }
}
