package com.longfor.lmk.k8slogviewer.utils;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.*;

public class LogStyleUtil {

    public static final String PLAIN_TEXT = "plain-text";

    public static final String SEPARATOR_LINE = "分割线";
    public static final String LOG_HEADER = "log-header";

    private LogStyleUtil() {
    }

    /**
     * 批量追加多行日志到 CodeArea，仅触发一次 appendText + 一次 setStyleSpans。
     * 相比逐行 appendText + setStyleSpans（2N 次文档变更），性能提升数量级。
     *
     * @param logArea       目标 CodeArea
     * @param lines         原始行内容（不含换行符）
     * @param logKeyword    日志过滤关键字
     * @param searchKeyword 内联搜索关键字
     */
    public static void appendBatch(CodeArea logArea, List<String> lines, String logKeyword, String searchKeyword) {
        if (lines.isEmpty()) return;

        int start = logArea.getLength();

        // 一次性拼接全部文本
        StringBuilder sb = new StringBuilder(lines.size() * 128);
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        logArea.appendText(sb.toString());

        // 一次性计算全部样式
        StyleSpans<Collection<String>> spans = computeBatchHighlighting(lines, logKeyword, searchKeyword);
        logArea.setStyleSpans(start, spans);
    }

    /**
     * 在文档开头批量插入历史日志行，仅触发一次 insertText + 一次 setStyleSpans。
     *
     * @param logArea       目标 CodeArea
     * @param lines         原始行内容（不含换行符），按时间从旧到新排列
     * @param logKeyword    日志过滤关键字
     * @param searchKeyword 内联搜索关键字
     */
    public static void prependBatch(CodeArea logArea, List<String> lines, String logKeyword, String searchKeyword) {
        if (lines.isEmpty()) return;

        // 一次性拼接全部文本
        StringBuilder sb = new StringBuilder(lines.size() * 128);
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        logArea.insertText(0, sb.toString());

        // insertText 后原有样式偏移已自动调整，只需为新插入部分设置样式
        StyleSpans<Collection<String>> spans = computeBatchHighlighting(lines, logKeyword, searchKeyword);
        logArea.setStyleSpans(0, spans);
    }

    /**
     * 追加单行到 headerArea（仅用于 header，量少，无需批量优化）。
     */
    public static void appendHeaderLine(CodeArea headerArea, String line, String logKeyword) {
        String lineWithNewline = line + "\n";
        int start = headerArea.getLength();
        headerArea.appendText(lineWithNewline);
        StyleSpans<Collection<String>> spans = computeHighlighting(true, line, logKeyword, null);
        headerArea.setStyleSpans(start, spans);
    }

    /**
     * 批量计算多行的高亮样式，合并为单个 StyleSpans。
     */
    public static StyleSpans<Collection<String>> computeBatchHighlighting(
            List<String> lines, String logKeyword, String searchKeyword) {
        boolean hasLogKw = logKeyword != null && !logKeyword.isBlank();
        boolean hasSearchKw = searchKeyword != null && !searchKeyword.isBlank();

        // 无关键字时，整块用 plain-text
        if (!hasLogKw && !hasSearchKw) {
            StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
            int totalLen = 0;
            for (String line : lines) {
                totalLen += line.length() + 1; // +1 for \n
            }
            builder.add(Collections.singleton(PLAIN_TEXT), totalLen);
            return builder.create();
        }

        // 按段落逐行计算样式再拼接，避免全文 indexOf 在大文本上的开销
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        for (String line : lines) {
            StyleSpans<Collection<String>> lineSpans = computeHighlighting(false, line, logKeyword, searchKeyword);
            for (var span : lineSpans) {
                builder.add(span.getStyle(), span.getLength());
            }
            // \n 需要一个样式节点
            builder.add(Collections.singleton(PLAIN_TEXT), 1);
        }
        return builder.create();
    }

    /**
     * 单行高亮计算。
     */
    public static StyleSpans<Collection<String>> computeHighlighting(boolean header, String text, String logKeyword, String searchKeyword) {
        String baseStyle = header ? LOG_HEADER : PLAIN_TEXT;
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();

        // 如果无关键字，直接返回baseStyle
        if ((logKeyword == null || logKeyword.isBlank()) && (searchKeyword == null || searchKeyword.isBlank())) {
            spansBuilder.add(Collections.singleton(baseStyle), text.length());
            return spansBuilder.create();
        }

        // 收集所有匹配：使用indexOf（忽略大小写），更快
        List<Match> matches = new ArrayList<>();
        String lowerText = text.toLowerCase();

        if (logKeyword != null && !logKeyword.isBlank()) {
            String lowerLogKw = logKeyword.toLowerCase();
            int idx = 0;
            while ((idx = lowerText.indexOf(lowerLogKw, idx)) != -1) {
                matches.add(new Match(idx, idx + lowerLogKw.length(), "log-highlight"));
                idx += lowerLogKw.length();
            }
        }

        if (searchKeyword != null && !searchKeyword.isBlank()) {
            String lowerSearchKw = searchKeyword.toLowerCase();
            int idx = 0;
            while ((idx = lowerText.indexOf(lowerSearchKw, idx)) != -1) {
                matches.add(new Match(idx, idx + lowerSearchKw.length(), "search-highlight"));
                idx += lowerSearchKw.length();
            }
        }

        // 如果无匹配，直接baseStyle
        if (matches.isEmpty()) {
            spansBuilder.add(Collections.singleton(baseStyle), text.length());
            return spansBuilder.create();
        }

        // 排序事件：start (+style), end (-style)
        List<Event> events = new ArrayList<>();
        for (Match m : matches) {
            events.add(new Event(m.start, m.style, true));  // start
            events.add(new Event(m.end, m.style, false));   // end
        }
        events.sort(Comparator.comparingInt(e -> e.pos));

        // 构建spans
        int lastPos = 0;
        Set<String> currentStyles = new HashSet<>();
        for (Event e : events) {
            if (e.pos > lastPos) {
                Set<String> styles = new HashSet<>(currentStyles);
                styles.add(baseStyle);
                spansBuilder.add(styles, e.pos - lastPos);
                lastPos = e.pos;
            }
            if (e.isStart) {
                currentStyles.add(e.style);
            } else {
                currentStyles.remove(e.style);
            }
        }
        // 剩余部分
        if (lastPos < text.length()) {
            Set<String> styles = new HashSet<>(currentStyles);
            styles.add(baseStyle);
            spansBuilder.add(styles, text.length() - lastPos);
        }

        return spansBuilder.create();
    }

    // 辅助类：匹配
    private static class Match {
        int start, end;
        String style;

        Match(int start, int end, String style) {
            this.start = start;
            this.end = end;
            this.style = style;
        }
    }

    // 辅助类：事件
    private static class Event {
        int pos;
        String style;
        boolean isStart;

        Event(int pos, String style, boolean isStart) {
            this.pos = pos;
            this.style = style;
            this.isStart = isStart;
        }
    }

    public static void clear(CodeArea codeArea) {
        codeArea.clear();
    }
}