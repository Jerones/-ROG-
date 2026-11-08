package com.example.safetyrag.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 「自研切分器」按"条款"优先切分的 TextSplitter。
 *
 * <p>为什么不用默认的 TokenTextSplitter 一刀切？因为规范文本的自然语义单元
 * 是"条"：一条规范（如"第十二条 进入施工现场必须佩戴安全帽"）被切成两半后，
 * 召回到的片段往往缺主语或缺结论，模型容易答错。所以策略是：</p>
 * <ol>
 *   <li>先按条款边界切段（第X条 / 3.2.1 / 附录A 都算边界），一条 = 一个候选块；</li>
 *   <li>单条长度在 chunkSize 以内 → 整条成块（保留条款完整性）；</li>
 *   <li>单条超长 → 兜底交给 Spring AI 的 TokenTextSplitter 再切分；</li>
 *   <li>章标题（第X章 / "4 临边与洞口作业"）不单独成块，作为前缀拼到其后第一条上，
 *       保证块里有"属于哪一章"的上下文。</li>
 * </ol>
 *
 * <h3>为什么还要做文本规整（normalize）</h3>
 * <p>PDF 提取出来的文本是"版面"而不是"句子"：同一句话被拆到两三行，行尾塞着
 * 几十个空格，条款号还会被空格拆开（{@code 8. 2.1}、{@code 2.1. 7}）。
 * 直接在原始文本上跑正则，行首锚点几乎匹配不到任何条款号——这正是改造前
 * 586 个块里有 558 个只能落到"正文片段"的原因。所以切分前先做两件事：</p>
 * <ul>
 *   <li>把连续空白压成一个空格、去掉空行；</li>
 *   <li>把被换行切断的句子接回去（上一行以字母/数字结尾、下一行也以字母/数字开头时补一个空格），
 *       只有遇到条款号才真正断行。</li>
 * </ul>
 *
 * <p>答辩讲解点：切分策略直接决定召回质量，本类就是"领域知识指导切分"的实现；
 * 报告中可对比"纯 Token 切分"与"条款切分"的命中率差异。</p>
 */
public class ClauseTextSplitter extends TextSplitter {

    private static final Logger log = LoggerFactory.getLogger(ClauseTextSplitter.class);

    private final int chunkSize;
    /** 保留字段：条款切分不使用重叠，窗口兜底由 TokenTextSplitter 处理。 */
    private final int overlap;
    private final TokenTextSplitter fallbackSplitter;

    /**
     * @param chunkSize 单块最大字符数（条款整条不超过它就整条成块）
     * @param overlap   预留参数（与 application.yml 配置对应，便于实验对比时调整）
     */
    public ClauseTextSplitter(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        // 兜底切分器：按 token 切分，块大小与 chunkSize 对齐（近似按字数）
        this.fallbackSplitter = TokenTextSplitter.builder()
                .withChunkSize(Math.max(50, chunkSize))
                .withMinChunkSizeChars(30)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(1000)
                .withKeepSeparator(true)
                .build();
    }

    /**
     * TextSplitter 的核心扩展点：输入一整篇文档文本，输出切分后的块文本列表。
     * 基类会负责把原 Document 的 metadata 复制到每个块上。
     */
    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        for (String segment : splitByClause(text)) {
            String trimmed = segment.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (looksLikeToc(trimmed)) {
                // 目录页（"1 总则…………7"）对问答毫无价值，留着只会污染检索结果
                log.debug("跳过疑似目录的段落（{} 字）", trimmed.length());
                continue;
            }
            if (trimmed.length() <= chunkSize) {
                chunks.add(trimmed);
            } else {
                // 超长条款 → Token 切分兜底
                List<Document> subDocs = fallbackSplitter.apply(List.of(new Document(trimmed)));
                for (Document sub : subDocs) {
                    String t = sub.getText() == null ? "" : sub.getText().strip();
                    if (!t.isEmpty()) {
                        chunks.add(t);
                    }
                }
                log.debug("超长条款（{} 字）已兜底切分为 {} 块", trimmed.length(), subDocs.size());
            }
        }
        return chunks;
    }

    /**
     * 第一步：按条款边界切段。
     *
     * <p>返回的每段以条款号开头（第一段可能是封面/前言，没有条款号）。
     * 章标题会被并入紧随其后的条款段，避免产生无意义的孤立小块。</p>
     */
    List<String> splitByClause(String text) {
        List<String> lines = dropPageFurniture(normalizeLines(text));
        List<String> segments = new ArrayList<>();
        if (lines.isEmpty()) {
            return segments;
        }
        StringBuilder buf = new StringBuilder();
        String pendingChapter = "";

        for (String line : lines) {
            String chapter = ClauseLabels.chapterLabel(line);
            if (chapter != null) {
                flush(buf, segments);
                pendingChapter = pendingChapter.isEmpty() ? chapter : pendingChapter + " " + chapter;
                continue;
            }
            String label = ClauseLabels.lineLabel(line);
            if (label != null) {
                flush(buf, segments);
                if (!pendingChapter.isEmpty()) {
                    buf.append(pendingChapter).append('\n');
                    pendingChapter = "";
                }
                buf.append(line);
            } else {
                appendJoined(buf, line);
            }
        }
        flush(buf, segments);
        if (!pendingChapter.isEmpty()) {
            // 文末只有章标题、后面没跟条款：单独留一段，别把标题丢了
            segments.add(pendingChapter);
        }
        if (segments.isEmpty()) {
            segments.add(String.join(" ", lines));
        }
        return mergeMiniHeadings(segments);
    }

    /**
     * 去掉页眉页脚这类"版面垃圾"。
     *
     * <p>PDF 每一页都会重复同一行（本项目实测有 {@code 本资料限内部使用，严禁用于商业。}），
     * 文字提取后它夹在正文中间，既会被切成一个 16 字的孤儿块，又会被向量化进库。
     * 判据：**短行 + 在全文出现 5 次以上 + 本身不是条款号**，三条同时满足才丢，
     * 不会误伤"重复引用的长条文"。</p>
     */
    static List<String> dropPageFurniture(List<String> lines) {
        java.util.Map<String, Integer> lineFreq = new java.util.HashMap<>();
        for (String line : lines) {
            if (line.length() <= 40) {
                lineFreq.merge(line, 1, Integer::sum);
            }
        }
        // 反复出现的短行 = 页眉页脚水印，收集成"噪音短语"，一会儿从句首剥掉
        List<String> noise = new ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : lineFreq.entrySet()) {
            if (e.getValue() >= 5
                    && ClauseLabels.lineLabel(e.getKey()) == null
                    && ClauseLabels.chapterLabel(e.getKey()) == null) {
                noise.add(e.getKey());
            }
        }
        // 水印更常见的是"黏在正文前面"：PDF 把页脚和下一页首行并成了一行，
        // 于是整行并不重复、只有开头 18 个字重复。按"句子边界截断后的前缀"再统计一次。
        java.util.Map<String, Integer> prefixFreq = new java.util.HashMap<>();
        for (String line : lines) {
            String prefix = openingPhrase(line);
            if (prefix != null) {
                prefixFreq.merge(prefix, 1, Integer::sum);
            }
        }
        prefixFreq.forEach((prefix, count) -> {
            if (count >= 5 && ClauseLabels.lineLabel(prefix) == null) {
                noise.add(prefix);
            }
        });
        noise.sort((a, b) -> Integer.compare(b.length(), a.length()));

        List<String> kept = new ArrayList<>(lines.size());
        for (String raw : lines) {
            String line = raw;
            for (String phrase : noise) {
                if (line.length() > phrase.length() && line.startsWith(phrase)) {
                    line = line.substring(phrase.length()).strip();
                }
            }
            if (line.isEmpty() || noise.contains(line)) {
                continue;
            }
            kept.add(line);
        }
        return kept;
    }

    /**
     * 取一行开头"到第一个句号/空格为止"的短语（最多 24 字），用于识别重复的页眉页脚。
     * 短语太短（&lt; 8 字）时返回 {@code null}——「总则」这种短词不值得当水印处理。
     */
    private static String openingPhrase(String line) {
        if (line == null || line.length() < 8) {
            return null;
        }
        String window = line.length() > 24 ? line.substring(0, 24) : line;
        int cut = -1;
        for (int i = window.length() - 1; i >= 0; i--) {
            char c = window.charAt(i);
            if (c == '。' || c == '；' || c == ' ') {
                cut = i + 1;
                break;
            }
        }
        if (cut < 8) {
            return null;
        }
        String phrase = window.substring(0, cut);
        return phrase.length() >= 8 ? phrase : null;
    }

    /**
     * 把"只有小标题、没有正文"的碎片并到下一段。
     *
     * <p>例如 {@code 第 2 章 术语和符号 2.1 术语} 这种行，单独成块既没有信息量，
     * 又会让引用卡片显示一条没有任何内容的出处。判据：长度 &lt; 30 且行首没有条款号。</p>
     */
    private static List<String> mergeMiniHeadings(List<String> segments) {
        List<String> out = new ArrayList<>();
        String carry = "";
        for (String segment : segments) {
            String firstLine = segment.split("\n", 2)[0];
            boolean hasLabel = ClauseLabels.lineLabel(firstLine) != null;
            if (!hasLabel && segment.length() < 30) {
                carry = carry.isEmpty() ? segment : carry + " " + segment;
                continue;
            }
            out.add(carry.isEmpty() ? segment : carry + " " + segment);
            carry = "";
        }
        if (!carry.isEmpty()) {
            if (out.isEmpty()) {
                out.add(carry);
            } else {
                out.set(out.size() - 1, out.get(out.size() - 1) + " " + carry);
            }
        }
        return out;
    }

    /**
     * 文本规整：压缩空白、去掉空行、把被换行切断的句子接回去、压紧条款编号。
     *
     * <p>只返回"视觉行"级别的干净文本，断句交给 {@link #splitByClause}——
     * 那里才知道哪一行是条款起点。</p>
     */
    static List<String> normalizeLines(String text) {
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        for (String raw : unified.split("\n")) {
            String line = raw.replace('\t', ' ')
                    .replace('\u00A0', ' ')
                    .replace('\u3000', ' ')
                    .replaceAll(" {2,}", " ")
                    .strip();
            if (line.isEmpty()) {
                continue;
            }
            lines.add(ClauseLabels.compactNumbers(line));
        }
        return lines;
    }

    /** 把当前缓冲收成一个段，并清空缓冲。 */
    private static void flush(StringBuilder buf, List<String> segments) {
        String s = buf.toString().strip();
        buf.setLength(0);
        if (!s.isEmpty()) {
            segments.add(s);
        }
    }

    /**
     * 拼接"续行"：PDF 里一句话常被拆成多行，直接换行会让块文本散架。
     * 中英文交界处补一个空格（{@code 分级》 GB} + {@code 3608-2008}），
     * 纯中文则直接相连，避免"高处 作业"这种奇怪的断字。
     */
    private static void appendJoined(StringBuilder buf, String line) {
        if (buf.length() == 0) {
            buf.append(line);
            return;
        }
        char prev = buf.charAt(buf.length() - 1);
        char next = line.charAt(0);
        boolean asciiBoundary = prev < 128 && next < 128
                && !Character.isWhitespace(prev) && !Character.isWhitespace(next);
        if (asciiBoundary) {
            buf.append(' ');
        }
        buf.append(line);
    }

    /**
     * 目录页判定：连续的省略号/间隔点。
     *
     * <p>规范 PDF 的目录长这样：{@code 4.1 荷载分类.......…......9}，
     * 点是 ASCII 句点，一页目录能切出十几个几十字的小块，对问答毫无价值，
     * 还会抢占向量检索的 topK 位置。判据是"连续 4 个以上的点号"出现次数。</p>
     */
    private static boolean looksLikeToc(String s) {
        int runs = 0;
        int run = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.' || c == '·' || c == '…') {
                run++;
                if (run == 4) {
                    runs++;
                }
            } else {
                run = 0;
            }
        }
        return runs >= 2 || (runs == 1 && s.length() < 120);
    }

    /**
     * 从块文本开头解析条款号（如"第十二条"、"3.2.1"），解析不到返回"正文片段"。
     *
     * @deprecated 单块解析不出时应当回溯沿用上一条的条款号，见
     *             {@code DocumentIngestService#split} 里的顺序遍历；这里只为兼容旧调用保留。
     */
    @Deprecated
    public static String extractClauseLabel(String chunkText) {
        String label = ClauseLabels.find(chunkText);
        return label == null ? ClauseLabels.NONE : label;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getOverlap() {
        return overlap;
    }
}
