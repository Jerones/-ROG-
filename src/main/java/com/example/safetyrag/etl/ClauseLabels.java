package com.example.safetyrag.etl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 条款号识别工具：把"这一块的出处"从 {@code 正文片段} 变成 {@code 第 3.2.1 条}。
 *
 * <p>为什么需要单独一个类？因为规范文档里条款号有两套写法，而且 PDF 提取出来的
 * 文本很脏：</p>
 * <ul>
 *   <li>条文式：{@code 第十二条}（多为地方规程、企业标准、自拟示例）；</li>
 *   <li>编号式：{@code 3.2.1}、{@code 8. 2.1}、{@code 2.1. 7}（国标/行标的正文，
 *       PDF 的排版空隙会被解析成空格，编号被拆成几段）；</li>
 *   <li>附录式：{@code 附录 A}、{@code 附录B.2}。</li>
 * </ul>
 *
 * <p>坑：<b>不能把任意数字都当条款号</b>。{@code 2m}、{@code GB 3608-2008}、
 * {@code 2020 年}、页码 {@code 47} 都不是。所以数字编号要求"至少含一个点"
 * （{@code 3.2.1} 而不是 {@code 3}），且后面必须紧跟正文而不是字母单位。</p>
 */
public final class ClauseLabels {

    /** 识别不到条款号时的占位值（前端引用卡片直接展示这个字符串）。 */
    public static final String NONE = "正文片段";

    private static final String CN_NUM = "一二三四五六七八九十百零〇";

    /** 第X条 */
    private static final Pattern ARTICLE =
            Pattern.compile("第[" + CN_NUM + "\\d]+条");

    /** 第X章（只看行首，标题文字由调用方整行使用） */
    private static final Pattern CHAPTER_PREFIX =
            Pattern.compile("第[" + CN_NUM + "\\d]+章");

    /**
     * 数字编号：3.2.1 / 4.1 / 8.2.1.2（至少一个点，避免页码、年份误判）。
     * 首位只允许 1-9：{@code 0.15}、{@code 0.32} 这类是表里的允许偏差值，
     * 规范条款不会从 0 开始编号，放进来会把表格拆成一堆碎块。
     */
    private static final Pattern NUMERIC =
            Pattern.compile("[1-9]\\d?(?:\\.\\d{1,2}){1,3}");

    /** 附录 A / 附录B.2 */
    private static final Pattern APPENDIX =
            Pattern.compile("附录\\s*[A-Z](?:\\.[0-9]{1,2})?");

    /** 行首的"一级标题"：短行、以小数字开头、后接汉字（国标里的 4 临边与洞口作业） */
    private static final Pattern LEVEL_ONE_HEADING =
            Pattern.compile("^(\\d{1,2})\\s+(\\p{IsHan}.*)$");

    private ClauseLabels() {
    }

    /**
     * 压紧被排版空格拆开的编号：{@code 8. 2.1} → {@code 8.2.1}，
     * {@code 2.1. 7} → {@code 2.1.7}。只处理"点后跟空格再跟数字"的情形，
     * 不会动 {@code GB 3608- 2008}、{@code 2m} 这类正文。
     */
    public static String compactNumbers(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text
                // "8. 2.1" / "2.1. 7" → "8.2.1" / "2.1.7"
                .replaceAll("(\\.)\\s+(?=\\d)", "$1")
                // "第 4 章" / "第 十二 条" → "第4章" / "第十二条"（PDF 排版会把"第 4 章"拆开）
                .replaceAll("第\\s*(\\d+)\\s*章", "第$1章")
                .replaceAll("第\\s*(\\d+)\\s*条", "第$1条");
    }

    /** 这一行是不是新的条款起点；是则返回条款号，否则返回 {@code null}。 */
    public static String lineLabel(String line) {
        if (line == null) {
            return null;
        }
        String s = line.strip();
        if (s.isEmpty()) {
            return null;
        }
        Matcher m = ARTICLE.matcher(s);
        if (m.lookingAt()) {
            return m.group();
        }
        m = APPENDIX.matcher(s);
        if (m.lookingAt()) {
            return m.group().replaceAll("\\s+", " ");
        }
        m = NUMERIC.matcher(s);
        if (m.lookingAt() && proseFollows(s, m.end())) {
            return m.group();
        }
        return null;
    }

    /**
     * 编号后面跟的是不是人话。
     *
     * <p>这一步专治规范里的**参数表**：表格被 PDF 提取成一行行数字，
     * 例如 {@code 1.50 1.8 1.5 1.5 1.5 38 1.55}、{@code 1.2 0.1960 o.2162 0.2230}，
     * 它们完全符合"数字.数字"的编号形状，但紧跟其后十几个字符里几乎没有汉字。
     * 判据是"编号后 20 个字符内至少 4 个汉字"——真条款一定是"编号 + 汉字正文"
     * （{@code 3.2.1 1）架体构配件…}），而表行里最多蹭到个"三跨""三步"。</p>
     */
    private static boolean proseFollows(String line, int from) {
        int limit = Math.min(line.length(), from + 20);
        int han = 0;
        for (int i = from; i < limit; i++) {
            if (Character.isIdeographic(line.charAt(i)) && ++han >= 4) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这一行是不是**整行的**章标题（第X章 / "4 临边与洞口作业"）。
     *
     * <p>刻意只认短行：PDF 里章标题和后面的条款经常挤在同一行
     * （{@code 第 1 章 总则 1.0.1 为科学评价…}），那种行不是"标题行"，
     * 当标题处理会把后面的条款正文连同条款号一起丢掉。长行直接返回 {@code null}，
     * 交给条款号解析去处理。</p>
     *
     * @return 可展示的章标题；不是标题行返回 {@code null}
     */
    public static String chapterLabel(String line) {
        if (line == null) {
            return null;
        }
        String s = ClauseLabels.compactNumbers(line).strip();
        if (s.isEmpty() || s.length() > 40) {
            return null;
        }
        Matcher m = CHAPTER_PREFIX.matcher(s);
        if (m.lookingAt()) {
            return s;
        }
        // "4 临边与洞口作业" 这种国标一级标题：短行 + 小数字 + 汉字
        m = LEVEL_ONE_HEADING.matcher(s);
        if (m.matches() && s.length() <= 24) {
            return "第 " + m.group(1) + " 章 " + m.group(2).strip();
        }
        return null;
    }

    /**
     * 从一段文本的开头找条款号（块首解析用）。
     *
     * @param lookBehind 允许在开头多少个字符内找——块首常带着上一块的残句，
     *                   条款号可能出现在几个字之后
     * @return 条款号；找不到返回 {@code null}
     */
    public static String find(String text, int lookBehind) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String head = text.length() > lookBehind ? text.substring(0, lookBehind) : text;
        // 优先认行首（最可靠），再退化为"紧跟开头的编号"
        for (String line : head.split("\n", -1)) {
            String label = lineLabel(line);
            if (label != null) {
                return label;
            }
        }
        Matcher m = ARTICLE.matcher(head);
        if (m.find()) {
            return m.group();
        }
        m = APPENDIX.matcher(head);
        if (m.find()) {
            return m.group().replaceAll("\\s+", " ");
        }
        // 数字编号只在"第一行"里找：块首常带上一块的残句（"……应设置操作平台；3.2.1"），
        // 编号紧跟其后；但再往后翻（第二行以后的正文）就容易把尺寸、偏差值当成条款号。
        String firstLine = head.split("\n", 2)[0];
        m = NUMERIC.matcher(firstLine);
        while (m.find()) {
            int end = m.end();
            if (end < firstLine.length()) {
                char next = firstLine.charAt(end);
                if (Character.isLetter(next) && next < 128) {
                    continue; // 3.5m 之类
                }
            }
            if (!proseFollows(firstLine, end)) {
                continue; // 参数表里的数字
            }
            return m.group();
        }
        return null;
    }

    /** 带上默认的回溯窗口（80 字）找条款号。 */
    public static String find(String text) {
        return find(text, 80);
    }
}
