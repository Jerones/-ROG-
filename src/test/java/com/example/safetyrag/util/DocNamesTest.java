package com.example.safetyrag.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DocNamesTest {

    @Test
    void stripsDocIdPrefixFromStoredName() {
        assertEquals("施工安全规范（节选示例）.txt",
                DocNames.stripDocIdPrefix("68ce8e61__施工安全规范（节选示例）.txt"));
        assertEquals("JGJ130-2011_建筑施工扣件式钢管脚手架安全技术规范.pdf",
                DocNames.stripDocIdPrefix("840bd261__JGJ130-2011_建筑施工扣件式钢管脚手架安全技术规范.pdf"));
    }

    @Test
    void keepsNameWithoutPrefix() {
        assertEquals("安全手册.pdf", DocNames.stripDocIdPrefix("安全手册.pdf"));
    }

    @Test
    void keepsUserUnderscoresThatAreNotDocIdPrefix() {
        // 用户文件名自带双下划线时不能被误删
        assertEquals("施工方案__v2.docx", DocNames.stripDocIdPrefix("施工方案__v2.docx"));
        // 前缀不是 8 位十六进制时也不动
        assertEquals("ABC__文件名.txt", DocNames.stripDocIdPrefix("ABC__文件名.txt"));
    }

    @Test
    void handlesNullAndEmpty() {
        assertNull(DocNames.stripDocIdPrefix(null));
        assertEquals("", DocNames.stripDocIdPrefix(""));
    }
}
