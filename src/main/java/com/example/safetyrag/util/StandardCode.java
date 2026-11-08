package com.example.safetyrag.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从文件名里抽工程建设标准的编号与年号，例如
 * {@code JGJ 80-2016}、{@code GB/T 3608-2008}、{@code GB50026—2020}。
 *
 * <p>用途只有一个：入库前提醒"库里已经有同一部标准的另一年了"。
 * 现场最怕的不是没有规范，而是照着已经废止的版本施工，
 * 所以 {@code JGJ 80-2011} 与 {@code JGJ 80-2016} 同时存在时应该给出提示。</p>
 */
public final class StandardCode {

    /** 标准代号 + 编号 + 年号；连字符兼容 -, –, —, ＿ 这几种排版写法。 */
    private static final Pattern PATTERN = Pattern.compile(
            "([A-Z]{2,5}(?:[/／][A-Z])?)\\s*(\\d{1,5})\\s*[-–—－_]\\s*(\\d{4})");

    private final String code;
    private final String year;

    private StandardCode(String code, String year) {
        this.code = code;
        this.year = year;
    }

    /** 标准代号 + 编号，例如 {@code JGJ80}：判断"是不是同一部标准"用它。 */
    public String code() {
        return code;
    }

    /** 年号，例如 {@code 2016}。 */
    public String year() {
        return year;
    }

    /** 完整编号，例如 {@code JGJ 80-2016}。 */
    public String display() {
        return code + "-" + year;
    }

    /**
     * 从任意文本（通常是文件名）里抽取标准编号；抽不到返回 {@code null}。
     *
     * <p>文件名里常带书名号和中文标题（{@code 《建筑施工高处作业安全技术规范》JGJ80-2016}），
     * 所以这里用"找"而不是"整体匹配"。</p>
     */
    public static StandardCode find(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String upper = text.toUpperCase(Locale.ROOT).replace('／', '/').replace('－', '-');
        Matcher m = PATTERN.matcher(upper);
        if (!m.find()) {
            return null;
        }
        String code = (m.group(1) + m.group(2)).replace(" ", "");
        return new StandardCode(code, m.group(3));
    }

    @Override
    public String toString() {
        return display();
    }
}
