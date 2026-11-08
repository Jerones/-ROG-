package com.example.safetyrag.etl;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClauseTextSplitter 单元测试：验证"条款优先、超长兜底"的切分行为。
 * 纯单元测试，不启动 Spring 容器。
 */
class ClauseTextSplitterTest {

    @Test
    void splitByClause_keepsEachClauseIntact() {
        ClauseTextSplitter splitter = new ClauseTextSplitter(500, 60);
        String text = """
                第一条 进入施工现场必须佩戴安全帽。
                第二条 高处作业必须系挂安全带，安全带应当高挂低用。
                第三条 施工现场临时用电应采用三级配电系统。
                """;
        List<String> segments = splitter.splitByClause(text);
        assertEquals(3, segments.size());
        assertTrue(segments.get(0).startsWith("第一条"));
        assertTrue(segments.get(1).startsWith("第二条"));
        assertTrue(segments.get(2).startsWith("第三条"));
    }

    @Test
    void split_shortClauses_oneChunkPerClause() {
        ClauseTextSplitter splitter = new ClauseTextSplitter(500, 60);
        Document doc = new Document("""
                第一条 进入施工现场必须佩戴安全帽。
                第二条 高处作业必须系挂安全带。
                """);
        List<Document> chunks = splitter.apply(List.of(doc));
        assertEquals(2, chunks.size());
    }

    @Test
    void split_longClause_fallsBackToTokenSplitter() {
        //  chunkSize 很小，单条超过后必须被兜底切成多块，且每块不至于失控地长
        ClauseTextSplitter splitter = new ClauseTextSplitter(80, 10);
        String longClause = "第一条 " + "脚手架搭设应当编制专项施工方案并进行安全技术交底，".repeat(20);
        List<Document> chunks = splitter.apply(List.of(new Document(longClause)));
        assertTrue(chunks.size() > 1, "超长条款应被切成多块，实际：" + chunks.size());
        for (Document chunk : chunks) {
            assertFalse(chunk.getText().isBlank());
            assertTrue(chunk.getText().length() < 400,
                    "兜底切分后单块不应过长，实际：" + chunk.getText().length());
        }
    }

    @Test
    void chapterHeading_mergesIntoFollowingClause() {
        ClauseTextSplitter splitter = new ClauseTextSplitter(500, 60);
        String text = """
                第二章 个人防护
                第四条 进入施工现场的所有人员必须正确佩戴安全帽。
                第五条 安全帽佩戴时应系紧下颏带。
                """;
        List<String> segments = splitter.splitByClause(text);
        // 章标题不应单独成段，应拼到第四条前面
        assertEquals(2, segments.size());
        assertTrue(segments.get(0).contains("第二章"));
        assertTrue(segments.get(0).contains("第四条"));
    }

    @Test
    void extractClauseLabel_readsLeadingClause() {
        assertEquals("第十二条",
                ClauseTextSplitter.extractClauseLabel("第十二条 攀登作业用的梯子应当坚固可靠。"));
        assertEquals("正文片段",
                ClauseTextSplitter.extractClauseLabel("这是一段没有条款号的普通文字。"));
    }

    @Test
    void splitByClause_handlesDirtyPdfLayout() {
        // 取自真实 PDF 的排版噪声：编号被空格拆开、句子被换行切断、章标题单独一行
        ClauseTextSplitter splitter = new ClauseTextSplitter(500, 60);
        String text = """
                4     临边与洞口作业
                4.1    I脑边作业
                4.1.1    本条是强制性条文。国家标准《高处作业分级》 GB
                3608- 2008 对“高处作业”的规定是：距坠落高度基准面 2m 或 2m 以上。
                4.1.2    临边作业应设置防护栏杆。
                """;
        List<String> segments = splitter.splitByClause(text);
        // "第 4 章 + 4.1 小节标题"这一段只有标题没有正文，会被并到后面第一条上
        assertEquals(2, segments.size());
        assertTrue(segments.get(0).contains("第 4 章"), "章标题应作为前缀保留：" + segments.get(0));
        assertTrue(segments.get(0).contains("4.1.1"), "小标题应并入其后的第一条：" + segments.get(0));
        assertTrue(segments.get(0).contains("GB 3608- 2008"),
                "被换行切断的句子应接回去：" + segments.get(0));
        assertTrue(segments.get(1).startsWith("4.1.2"));
    }

    @Test
    void splitByClause_dropsRepeatedPageFurniture() {
        ClauseTextSplitter splitter = new ClauseTextSplitter(500, 60);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            sb.append("本资料限内部使用，严禁用于商业。\n");
            sb.append("第").append(i).append("条 施工现场应设置安全防护设施。\n");
        }
        List<String> segments = splitter.splitByClause(sb.toString());
        assertEquals(6, segments.size());
        assertFalse(segments.get(0).contains("本资料限内部使用"),
                "重复 6 次的页脚水印应被剥掉：" + segments.get(0));
    }

    @Test
    void splitByClause_dropsTableOfContentsLines() {
        ClauseTextSplitter splitter = new ClauseTextSplitter(500, 60);
        List<Document> chunks = splitter.apply(List.of(new Document("""
                4.1 荷载分类.......…......9
                5.1 基本设计规定.....................14
                第一条 施工现场应设置消防通道。
                """)));
        assertEquals(1, chunks.size(), "目录行应被过滤，只留真正的条款");
        assertTrue(chunks.get(0).getText().startsWith("第一条"));
    }
}
