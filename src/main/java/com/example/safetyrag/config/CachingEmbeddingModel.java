package com.example.safetyrag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingOptionsBuilder;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EmbeddingModel 装饰器：把"逐条请求"变成"批量请求 + 本地缓存"。
 *
 * <p>为什么需要它：Spring AI 1.0.0 的 {@code SimpleVectorStore.add()} 内部是
 * <b>对每个 Document 单独调一次</b> {@code embeddingModel.embed(document)}
 * （见其 doAdd 字节码，日志里那块 "Calling EmbeddingModel for document id = ..."
 * 就是逐条调用留下的）。一篇 171 块的规范 = 171 次 HTTPS 请求，一篇 1221 块的
 * 语料 = 1221 次。调用越多次，"某一次被中间设备/服务端重置连接"的概率越高，
 * 而任何一次失败都会让整批入库回滚 —— 这就是"上传 171 块的 PDF 必失败、
 * 重建索引要跑三分半"的真正原因。</p>
 *
 * <p>做法：入库前先 {@link #prefetch(List)}，把整篇文本按 10 条一批（百炼
 * embedding 接口单次上限）批量算好放进缓存；随后 SimpleVectorStore 逐条调用时
 * 全部命中缓存，不再产生网络请求。171 块从 171 次请求降到 18 次，1221 块从
 * 1221 次降到 123 次。</p>
 *
 * <p>缓存是"文本 → 向量"的 LRU，只影响性能不影响正确性：换模型时缓存随进程重启
 * 一起丢弃，不会把旧模型的向量混进新库。</p>
 */
public class CachingEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(CachingEmbeddingModel.class);

    /** 百炼 text-embedding 接口单次最多 10 条 input；但批越大响应体越大，越容易被中途掐断。 */
    private static final int MAX_BATCH = 5;
    /** 单批失败后的重试次数（每次失败约 19 秒才断，次数不宜多）。 */
    private static final int BATCH_ATTEMPTS = 3;
    /** 缓存上限：按 500 字/块、1024 维估算，4000 条约 20MB，够放三四篇规范。 */
    private static final int MAX_CACHE_ENTRIES = 4000;

    private final EmbeddingModel delegate;

    private final Map<String, float[]> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

    public CachingEmbeddingModel(EmbeddingModel delegate) {
        this.delegate = delegate;
    }

    public EmbeddingModel delegate() {
        return delegate;
    }

    /**
     * 批量预热：把 texts 分批发给真实模型，结果存入缓存。
     *
     * @return 实际发出的 HTTP 请求数（全部命中缓存时为 0）
     */
    public int prefetch(List<String> texts) {
        List<String> pending = new ArrayList<>();
        for (String text : texts) {
            String key = key(text);
            if (key != null && !cache.containsKey(key)) {
                pending.add(text);
            }
        }
        if (pending.isEmpty()) {
            return 0;
        }
        int requests = 0;
        for (int i = 0; i < pending.size(); i += MAX_BATCH) {
            List<String> batch = pending.subList(i, Math.min(i + MAX_BATCH, pending.size()));
            List<float[]> vectors = embedBatchWithRetry(batch);
            for (int k = 0; k < batch.size(); k++) {
                cache.put(key(batch.get(k)), vectors.get(k));
            }
            requests++;
        }
        log.info("Embedding 预取完成：{} 条文本 → {} 次请求（批大小 {}）",
                pending.size(), requests, MAX_BATCH);
        return requests;
    }

    /**
     * 单批失败重试 + 逐级二分降级。
     *
     * <p>实测（见 README「上传失败的排查」）：一次请求 1 条（响应 21KB）稳定成功，
     * 5 条（108KB）稳定成功，10 条（217KB）经常在 19 秒后被重置——
     * 响应体越大越容易在半路被中间设备掐断。所以这里不只重试，整批连续失败时
     * 还会把批拆小再试（5 → 2+3 → 1），最终一定能落到"单条"这个稳定档位。</p>
     */
    private List<float[]> embedBatchWithRetry(List<String> batch) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= BATCH_ATTEMPTS; attempt++) {
            try {
                return callDelegate(batch);
            } catch (RuntimeException e) {
                last = e;
                log.warn("Embedding 批量请求第 {} 次失败（{} 条）：{}", attempt, batch.size(), rootMessage(e));
                if (attempt < BATCH_ATTEMPTS) {
                    sleep(400L * attempt);
                }
            }
        }
        if (batch.size() > 1) {
            int mid = batch.size() / 2;
            log.warn("Embedding 批（{} 条）连续失败，拆成 {} + {} 条重试",
                    batch.size(), mid, batch.size() - mid);
            List<float[]> vectors = new ArrayList<>(batch.size());
            vectors.addAll(embedBatchWithRetry(batch.subList(0, mid)));
            vectors.addAll(embedBatchWithRetry(batch.subList(mid, batch.size())));
            return vectors;
        }
        throw new IllegalStateException("Embedding 接口连续失败（单条也失败）：" + rootMessage(last)
                + "。多半是到百炼的网络不稳定（连接被重置）或并发超限，稍后重试即可；"
                + "若长期失败请检查 LLM_BASE_URL / API key 与代理设置。", last);
    }

    private List<float[]> callDelegate(List<String> texts) {
        EmbeddingOptions options = EmbeddingOptionsBuilder.builder().build();
        EmbeddingResponse response = delegate.call(new EmbeddingRequest(texts, options));
        List<Embedding> results = response.getResults();
        float[][] ordered = new float[texts.size()][];
        for (Embedding e : results) {
            int index = e.getIndex() == null ? -1 : e.getIndex();
            if (index >= 0 && index < ordered.length) {
                ordered[index] = e.getOutput();
            }
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            if (ordered[i] == null) {
                throw new IllegalStateException("Embedding 接口返回条数与请求不一致（缺少第 " + i + " 条）");
            }
            vectors.add(ordered[i]);
        }
        return vectors;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request.getInstructions();
        List<float[]> vectors = new ArrayList<>(inputs.size());
        List<Integer> missing = new ArrayList<>();
        List<String> missingTexts = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            float[] cached = cache.get(key(inputs.get(i)));
            vectors.add(cached);
            if (cached == null) {
                missing.add(i);
                missingTexts.add(inputs.get(i));
            }
        }
        if (!missingTexts.isEmpty()) {
            List<float[]> computed = embedBatchWithRetry(missingTexts);
            for (int k = 0; k < missing.size(); k++) {
                float[] vector = computed.get(k);
                vectors.set(missing.get(k), vector);
                cache.put(key(missingTexts.get(k)), vector);
            }
        }
        List<Embedding> embeddings = new ArrayList<>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            embeddings.add(new Embedding(vectors.get(i), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        String key = key(text);
        float[] cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        float[] computed = embedBatchWithRetry(List.of(text)).get(0);
        cache.put(key, computed);
        return computed;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return call(new EmbeddingRequest(texts, EmbeddingOptionsBuilder.builder().build()))
                .getResults().stream().map(Embedding::getOutput).toList();
    }

    @Override
    public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy batchingStrategy) {
        List<String> texts = documents.stream()
                .map(d -> d.getText() == null ? "" : d.getText()).toList();
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    private static String key(String text) {
        return text == null ? "" : text.strip();
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
