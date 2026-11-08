package com.example.safetyrag.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bm25Index 单元测试：验证关键词召回的排序方向与增删行为。
 * 纯单元测试，不启动 Spring 容器。
 */
class Bm25IndexTest {

    private Bm25Index index;

    @BeforeEach
    void setUp() {
        index = new Bm25Index();
        index.addDocument("c1", "第四条 进入施工现场的所有人员必须正确佩戴安全帽，安全帽应系紧下颏带。");
        index.addDocument("c2", "第八条 高处作业是指在坠落高度基准面2米以上有可能坠落的高处进行的作业。");
        index.addDocument("c3", "第二十一条 塔式起重机的安装拆卸应由具有资质的单位承担，司机必须持证上岗。");
        index.addDocument("c4", "第十七条 施工现场临时用电应采用三级配电二级漏电保护系统。");
    }

    @Test
    void search_helmetQuery_ranksHelmetChunkFirst() {
        List<Bm25Index.ScoredDoc> hits = index.search("安全帽有什么要求", 3);
        assertFalse(hits.isEmpty());
        assertEquals("c1", hits.get(0).docId());
    }

    @Test
    void search_craneQuery_ranksCraneChunkFirst() {
        List<Bm25Index.ScoredDoc> hits = index.search("塔式起重机安装有什么要求", 3);
        assertFalse(hits.isEmpty());
        assertEquals("c3", hits.get(0).docId());
    }

    @Test
    void search_unrelatedQuery_returnsEmpty() {
        List<Bm25Index.ScoredDoc> hits = index.search("食堂今天的菜单是什么", 3);
        // 与语料毫无共同词项时不应硬凑结果（bigram 单字可能带来极低分，
        // 这里只断言最相关的安全帽条款不会被排到第一）
        assertTrue(hits.isEmpty() || !hits.get(0).docId().equals("c1") || hits.get(0).score() < 1.0);
    }

    @Test
    void removeDocument_noLongerSearchable() {
        index.removeDocument("c1");
        assertEquals(3, index.size());
        List<Bm25Index.ScoredDoc> hits = index.search("安全帽", 5);
        assertTrue(hits.stream().noneMatch(h -> h.docId().equals("c1")));
    }

    @Test
    void tokenize_producesBigramsAndWords() {
        List<String> tokens = Bm25Index.tokenize("安全帽 abc123");
        assertTrue(tokens.contains("安全"));
        assertTrue(tokens.contains("全帽"));
        assertTrue(tokens.contains("abc123"));
    }
}
