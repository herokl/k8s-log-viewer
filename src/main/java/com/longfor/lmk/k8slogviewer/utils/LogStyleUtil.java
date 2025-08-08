package com.longfor.lmk.k8slogviewer.utils;

import com.longfor.lmk.k8slogviewer.config.K8sQuery;
import javafx.application.Platform;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogStyleUtil {

    public static final String PLAIN_TEXT = "plain-text";

    private static final String SEPARATOR_LINE = "分割线";
    public static final String LOG_HEADER = "log-header";

    private LogStyleUtil() {
    }

    // 追加一行日志并高亮指定关键字
    public static void appendHighlightedLine(CodeArea headerArea, CodeArea logArea, String line, K8sQuery k8sQuery) {
        String keyword = k8sQuery.getKeyword();
        boolean searchRunning = k8sQuery.isSearchRunning();
        boolean headerCaptured = k8sQuery.isHeaderCaptured();
        Platform.runLater(() -> {
            // 分割线未出现前，累积 header 内容
            String lineWithNewline = line + "\n";
            if (headerCaptured) {
                boolean contains = line.trim().contains(SEPARATOR_LINE);
                k8sQuery.setHeaderCaptured(!contains);
                if (!contains) {
                    setLogArea(headerArea, true, line, lineWithNewline, null);
                }
            } else {
                setLogArea(logArea, false, line, lineWithNewline, keyword);
            }
            if (searchRunning) {
                logArea.moveTo(logArea.getLength());
                logArea.requestFollowCaret();
            }
        });
    }

    private static void setLogArea(CodeArea logArea, boolean header, String line, String lineWithNewline, String keyword) {
        int start = logArea.getLength();
        logArea.appendText(lineWithNewline);
        if (keyword != null && !keyword.isBlank()) {
            StyleSpans<Collection<String>> spans = computeHighlighting(header, line, keyword);
            logArea.setStyleSpans(start, spans);
        } else {
            // 没有关键字时，显式设置整行为 plain-text
            logArea.setStyleSpans(start, new StyleSpansBuilder<Collection<String>>()
                    .add(Collections.singleton(header ? LOG_HEADER : PLAIN_TEXT), line.length() + 1)
                    .create());
        }
    }

    // 高亮逻辑：将所有匹配的关键字添加 "highlight" 样式
    private static StyleSpans<Collection<String>> computeHighlighting(boolean header, String text, String keyword) {
        String pattern = "\\b" + Pattern.quote(keyword) + "\\b";
        Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text);
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int lastKwEnd = 0;
        while (matcher.find()) {
            spansBuilder.add(Collections.singleton(header ? LOG_HEADER : PLAIN_TEXT), matcher.start() - lastKwEnd);
            spansBuilder.add(Collections.singleton("highlight"), matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(Collections.singleton(header ? LOG_HEADER : PLAIN_TEXT), text.length() - lastKwEnd);
        return spansBuilder.create();
    }

    // 可选：清除样式（例如在新搜索前）
    public static void clear(CodeArea codeArea) {
        codeArea.clear();
    }
}
