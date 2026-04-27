package com.longfor.lmk.k8slogviewer.utils;

import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LogStyleUtilTest {

    /**
     * 从 StyleSpans 中提取所有唯一的样式名称。
     */
    private static Set<String> extractAllStyles(StyleSpans<Collection<String>> spans) {
        Set<String> allStyles = new HashSet<>();
        for (Iterator<?> it = spans.iterator(); it.hasNext(); ) {
            Object span = it.next();
            try {
                @SuppressWarnings("unchecked")
                Collection<String> style = (Collection<String>) span.getClass().getMethod("getStyle").invoke(span);
                allStyles.addAll(style);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return allStyles;
    }

    @Test
    void computeHighlighting_noKeyword_shouldReturnPlainStyle() {
        StyleSpans<Collection<String>> spans =
                LogStyleUtil.computeHighlighting(false, "hello world", null, null);
        assertEquals(1, spans.getSpanCount());
        Set<String> styles = extractAllStyles(spans);
        assertTrue(styles.contains(LogStyleUtil.PLAIN_TEXT));
    }

    @Test
    void computeHighlighting_emptyKeyword_shouldReturnPlainStyle() {
        StyleSpans<Collection<String>> spans =
                LogStyleUtil.computeHighlighting(false, "hello world", "", null);
        assertEquals(1, spans.getSpanCount());
        Set<String> styles = extractAllStyles(spans);
        assertTrue(styles.contains(LogStyleUtil.PLAIN_TEXT));
    }

    @Test
    void computeHighlighting_logKeyword_shouldHighlight() {
        StyleSpans<Collection<String>> spans =
                LogStyleUtil.computeHighlighting(false, "error in line", "error", null);
        // error 高亮会产生 2 个 span: [highlight][plain]
        assertEquals(2, spans.getSpanCount());
        Set<String> styles = extractAllStyles(spans);
        assertTrue(styles.contains("log-highlight"));
    }

    @Test
    void computeHighlighting_searchKeyword_shouldHighlight() {
        StyleSpans<Collection<String>> spans =
                LogStyleUtil.computeHighlighting(false, "me", "find me here", null);
        // "me" at 5-7: 3 spans → [plain][highlight][plain]
        assertEquals(3, spans.getSpanCount());
        Set<String> styles = extractAllStyles(spans);
        assertTrue(styles.contains("search-highlight"));
    }

    @Test
    void computeHighlighting_bothKeywords_shouldHighlightBoth() {
        String text = "error: value not found";
        StyleSpans<Collection<String>> spans =
                LogStyleUtil.computeHighlighting(false, text, "error", null);
        // "error" at 0-5, "found" at 16-21 → 3 spans
        assertEquals(3, spans.getSpanCount());
        Set<String> styles = extractAllStyles(spans);
        assertTrue(styles.contains("log-highlight"), "Should contain log-highlight, got: " + styles);
        assertTrue(styles.contains("search-highlight"), "Should contain search-highlight, got: " + styles);
    }

    @Test
    void computeHighlighting_shouldBeCaseInsensitive() {
        StyleSpans<Collection<String>> spans =
                LogStyleUtil.computeHighlighting(false, "ERROR log", "error", null);
        assertEquals(2, spans.getSpanCount());
        Set<String> styles = extractAllStyles(spans);
        assertTrue(styles.contains("log-highlight"));
    }

    @Test
    void computeHighlighting_header_shouldUseHeaderStyle() {
        StyleSpans<Collection<String>> spans =
                LogStyleUtil.computeHighlighting(true, "header text", null, null);
        assertEquals(1, spans.getSpanCount());
        Set<String> styles = extractAllStyles(spans);
        assertTrue(styles.contains(LogStyleUtil.LOG_HEADER));
    }

    @Test
    void computeHighlighting_overlappingKeywords_shouldMergeStyles() {
        String text = "ERROR";
        StyleSpans<Collection<String>> spans =
                LogStyleUtil.computeHighlighting(false, text, "ERR", null);
        Set<String> styles = extractAllStyles(spans);
        // 完全重叠区域应同时包含两种高亮样式
        assertTrue(styles.contains("log-highlight"), "Should contain log-highlight, got: " + styles);
        assertTrue(styles.contains("search-highlight"), "Should contain search-highlight, got: " + styles);
    }

    @Test
    void computeHighlighting_multipleMatches_shouldHighlightAll() {
        String text = "aaa bbb aaa";
        StyleSpans<Collection<String>> spans =
                LogStyleUtil.computeHighlighting(false, text, "aaa", null);
        // 两处 "aaa" 高亮 → 3 spans: [highlight][plain][highlight]
        assertEquals(3, spans.getSpanCount());
        Set<String> styles = extractAllStyles(spans);
        assertTrue(styles.contains("log-highlight"));
    }

    @Test
    void computeHighlighting_noMatch_shouldHaveBaseStyleOnly() {
        String text = "hello world";
        StyleSpans<Collection<String>> spans =
                LogStyleUtil.computeHighlighting(false, text, "xyz", null);
        assertEquals(1, spans.getSpanCount());
        Set<String> styles = extractAllStyles(spans);
        assertFalse(styles.contains("log-highlight"));
        assertTrue(styles.contains(LogStyleUtil.PLAIN_TEXT));
    }
}
