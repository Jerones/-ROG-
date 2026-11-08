package com.example.safetyrag.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「自研」纯 Java 实现的 BM25 关键词检索索引（无第三方依赖）。
 *
 * <p>用途：与向量检索做混合召回。向量检索擅长语义相近，但对"安全帽""塔吊"
 * 这类专有名词的字面匹配反而不如关键词检索稳定；BM25 按词频/逆文档频率
 * 打分，正好补齐这一块。两路结果在 {@link HybridRetriever} 中合并去重。</p>
 *
 * <p>分词：中文没有空格分词，这里用"字符 bigram + 英文/数字整词"的轻量方案
 * ——实现简单、对规范条文这种术语密集文本效果足够，答辩时也好讲解。
 * 生产环境可替换为 IK / HanLP 等专业分词器，索引结构不变。</p>
 *
 * <p>公式（Okapi BM25）：
 * score(q,d) = Σ IDF(t) · f(t,d)·(k1+1) / ( f(t,d) + k1·(1 − b + b·|d|/avgdl))</p>
 *
 * <p>注意：BM25 只认字面、不认语义，只要问题里有一个字出现在块里它就能给出正分。
 * 因此它不适合单独判定"是否该回答"，只适合给已召回的候选加权排序
 * （具体取舍见 {@link HybridRetriever}）。</p>
 */
@org.springframework.stereotype.Component
public class Bm25Index {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    /** docId -> 分词后的词项列表（保留词频顺序无要求，统计用）。 */
    private final Map<String, List<String>> docTerms = new LinkedHashMap<>();
    /** docId -> 原文（调试/扩展用）。 */
    private final Map<String, String> docTexts = new HashMap<>();
    /** 词项 -> 包含该词的文档数（算 IDF 用）。 */
    private final Map<String, Integer> docFrequency = new HashMap<>();
    private long totalLength = 0L;

    /** 检索结果：文档 id + BM25 原始分（未归一化）。 */
    public record ScoredDoc(String docId, double score) {
    }

    public synchronized void clear() {
        docTerms.clear();
        docTexts.clear();
        docFrequency.clear();
        totalLength = 0L;
    }

    public synchronized void addDocument(String docId, String text) {
        removeDocument(docId);
        List<String> terms = tokenize(text);
        docTerms.put(docId, terms);
        docTexts.put(docId, text);
        totalLength += terms.size();
        for (String term : new LinkedHashSet<>(terms)) {
            docFrequency.merge(term, 1, Integer::sum);
        }
    }

    public synchronized void removeDocument(String docId) {
        List<String> old = docTerms.remove(docId);
        if (old == null) {
            return;
        }
        docTexts.remove(docId);
        totalLength -= old.size();
        for (String term : new LinkedHashSet<>(old)) {
            docFrequency.computeIfPresent(term, (k, v) -> v > 1 ? v - 1 : null);
        }
    }

    public synchronized int size() {
        return docTerms.size();
    }

    /** 按 BM25 分数降序返回 topN 个文档。 */
    public synchronized List<ScoredDoc> search(String query, int topN) {
        if (docTerms.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> queryTerms = new LinkedHashSet<>(tokenize(query));
        int n = docTerms.size();
        double avgdl = n == 0 ? 0.0 : (double) totalLength / n;

        List<ScoredDoc> results = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : docTerms.entrySet()) {
            List<String> terms = entry.getValue();
            if (terms.isEmpty()) {
                continue;
            }
            Map<String, Integer> tf = new HashMap<>();
            for (String t : terms) {
                tf.merge(t, 1, Integer::sum);
            }
            double score = 0.0;
            for (String qt : queryTerms) {
                Integer f = tf.get(qt);
                if (f == null) {
                    continue;
                }
                int df = docFrequency.getOrDefault(qt, 0);
                // 带平滑的 IDF，避免 df=0 或全文档命中时出现负值
                double idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
                double denom = f + K1 * (1 - B + B * terms.size() / Math.max(avgdl, 1.0));
                score += idf * (f * (K1 + 1)) / denom;
            }
            if (score > 0) {
                results.add(new ScoredDoc(entry.getKey(), score));
            }
        }
        results.sort(Comparator.comparingDouble(ScoredDoc::score).reversed());
        return results.size() > topN ? results.subList(0, topN) : results;
    }

    public String getText(String docId) {
        return docTexts.get(docId);
    }

    /**
     * 轻量分词：英文/数字按整词保留（小写），中文按字符 bigram 切分，
     * 另外把中文单字也加入，提高短查询的召回。
     */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        String lower = text.toLowerCase();
        StringBuilder word = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                word.append(c);
                flushCjk(cjk, tokens);
            } else if (c >= 0x4E00 && c <= 0x9FFF) {
                flushWord(word, tokens);
                cjk.append(c);
            } else {
                flushWord(word, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushWord(word, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    private static void flushWord(StringBuilder word, List<String> tokens) {
        if (word.length() > 0) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }

    private static void flushCjk(StringBuilder cjk, List<String> tokens) {
        if (cjk.length() == 0) {
            return;
        }
        for (int i = 0; i < cjk.length(); i++) {
            tokens.add(String.valueOf(cjk.charAt(i)));
            if (i + 1 < cjk.length()) {
                tokens.add(cjk.substring(i, i + 2));
            }
        }
        cjk.setLength(0);
    }
}
