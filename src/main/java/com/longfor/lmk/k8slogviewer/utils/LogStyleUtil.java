package com.longfor.lmk.k8slogviewer.utils;

import com.longfor.lmk.k8slogviewer.config.K8sQuery;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class LogStyleUtil {
    private LogStyleUtil() {
    }

    // 追加一行日志并高亮指定关键字
    public static void appendHighlightedLine(CodeArea codeArea, String line, K8sQuery k8sQuery) {
        String keyword = k8sQuery.getKeyword();
        boolean searchRunning = k8sQuery.isSearchRunning();
        Platform.runLater(() -> {
            int start = codeArea.getLength();
            codeArea.appendText(line + "\n");
            if (keyword != null && !keyword.isBlank()) {
                StyleSpans<Collection<String>> spans = computeHighlighting(line, keyword);
                codeArea.setStyleSpans(start, spans);
            }
            if (searchRunning) {
                codeArea.moveTo(codeArea.getLength());
                codeArea.requestFollowCaret();
            }
        });
    }

    // 高亮逻辑：将所有匹配的关键字添加 "highlight" 样式
    private static StyleSpans<Collection<String>> computeHighlighting(String text, String keyword) {
        String pattern = Pattern.quote(keyword); // 转义特殊字符
        Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text);
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();

        int lastKwEnd = 0;
        while (matcher.find()) {
            spansBuilder.add(Collections.emptyList(), matcher.start() - lastKwEnd);
            spansBuilder.add(Collections.singleton("highlight"), matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastKwEnd);
        return spansBuilder.create();
    }

    // 可选：清除样式（例如在新搜索前）
    public static void clear(CodeArea codeArea) {
        codeArea.clear();
    }
}
