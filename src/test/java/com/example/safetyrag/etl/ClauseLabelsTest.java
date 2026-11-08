package com.example.safetyrag.etl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 条款号识别单元测试：样例取自真实的规范 PDF 文本（含排版空格、页码、表格数值等噪声）。
 */
class ClauseLabelsTest {

    @Test
    void compactNumbers_repairsBrokenNumbering() {
        // PDF 把编号拆成几段：8. 2.1 / 2.1. 7
        assertEquals("8.2.1 脚手架立杆基础", ClauseLabels.compactNumbers("8. 2.1 脚手架立杆基础"));
        assertEquals("2.1.7 移动式操作平台", ClauseLabels.compactNumbers("2.1. 7 移动式操作平台"));
        // "第 4 章" 这种被拆开的章号
        assertEquals("第4章 临边与洞口作业", ClauseLabels.compactNumbers("第 4 章 临边与洞口作业"));
    }

    @Test
    void compactNumbers_keepsRealNumbersIntact() {
        // 尺寸、标准号不能被当成编号压紧
        assertEquals("GB 3608-2008 的规定", ClauseLabels.compactNumbers("GB 3608-2008 的规定"));
        assertEquals("2m 及以上", ClauseLabels.compactNumbers("2m 及以上"));
    }

    @Test
    void lineLabel_recognisesThreeStyles() {
        assertEquals("第十二条", ClauseLabels.lineLabel("第十二条 进入施工现场必须佩戴安全帽。"));
        assertEquals("3.2.1", ClauseLabels.lineLabel("3.2.1 立杆基础应符合下列规定："));
        assertEquals("附录 A", ClauseLabels.lineLabel("附录 A 防护栏杆设计计算"));
    }

    @Test
    void lineLabel_rejectsTableNumbers() {
        // 表格里的允许偏差、页码、尺寸都不是条款号
        assertNull(ClauseLabels.lineLabel("0.15 允许偏差"));
        assertNull(ClauseLabels.lineLabel("2m 及以上有可能坠落"));
        assertNull(ClauseLabels.lineLabel("47"));
        assertNull(ClauseLabels.lineLabel("GB 3608-2008"));
        // 参数表被 PDF 提成一行行数字，形状像编号但后面没有正文
        assertNull(ClauseLabels.lineLabel("1.50 1.8 1.5 1.5 1.5 38 1.55"));
        assertNull(ClauseLabels.lineLabel("1.2 0.1960 o.2162 0.2230 0.2365"));
    }

    @Test
    void find_readsLabelAfterLeftoverSentence() {
        // 块首常带上一块的残句，编号紧跟其后
        assertEquals("3.2.1", ClauseLabels.find("应设置操作平台；3.2.1 立杆基础"));
        assertEquals("第十二条", ClauseLabels.find("（承上）第十二条 高处作业必须系挂安全带。"));
    }

    @Test
    void find_returnsNullForPlainText() {
        assertNull(ClauseLabels.find("这是一段没有条款号的普通文字，讲的是现场文明施工。"));
    }

    @Test
    void chapterLabel_onlyMatchesShortHeadingLines() {
        assertNotNull(ClauseLabels.chapterLabel("第 4 章 临边与洞口作业"));
        assertEquals("第 4 章 临边与洞口作业", ClauseLabels.chapterLabel("4 临边与洞口作业"));
        // 章标题和正文挤在一行时不能当标题，否则后面的条款号会跟着丢
        assertNull(ClauseLabels.chapterLabel(
                "第1章 总则 1.0.1 为科学评价建筑施工现场安全生产，预防生产安全事故的发生，保障施工人员的安全和健康。"));
    }
}
