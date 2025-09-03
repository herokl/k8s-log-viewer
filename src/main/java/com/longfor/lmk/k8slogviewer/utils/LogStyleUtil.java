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

    // OPTIMIZE: 修改为在Controller中调用，非静态追加，合并到单个runLater
    // 移除appendHighlightedLine的Platform.runLater，移到Controller处理

    // OPTIMIZE: 修改setLogArea签名，支持searchKeyword
    public static void setLogArea(CodeArea logArea, boolean header, String line, String lineWithNewline, String logKeyword, String searchKeyword) {
        int start = logArea.getLength();
        logArea.appendText(lineWithNewline);
        StyleSpans<Collection<String>> spans = computeHighlighting(header, line, logKeyword, searchKeyword);
        logArea.setStyleSpans(start, spans);
    }

    // OPTIMIZE: 重构computeHighlighting，支持多个关键字（logKw和searchKw），使用indexOf代替regex，提高效率
    // 返回StyleSpans，支持叠加样式（e.g., log-highlight 和 search-highlight）
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

    // 可选：清除样式
    public static void clear(CodeArea codeArea) {
        codeArea.clear();
    }
}