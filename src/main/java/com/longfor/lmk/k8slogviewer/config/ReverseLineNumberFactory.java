package com.longfor.lmk.k8slogviewer.config;

import javafx.scene.Node;
import javafx.scene.control.Label;
import org.fxmisc.richtext.CodeArea;

import java.util.List;
import java.util.function.IntFunction;

/**
 * 自定义倒序行号工厂，用于日志倒序显示时，行号也从大到小
 */
public class ReverseLineNumberFactory implements IntFunction<Node> {

    private final CodeArea codeArea;

    public ReverseLineNumberFactory(CodeArea codeArea) {
        this.codeArea = codeArea;
    }
    public static ReverseLineNumberFactory get(CodeArea codeArea){
        return new ReverseLineNumberFactory(codeArea);
    }
    @Override
    public Node apply(int paragraphIndex) {
        List<?> paragraphs = codeArea.getParagraphs();
        int totalParagraphs = paragraphs.size();
        int lineNumber = totalParagraphs - paragraphIndex;

        Label label = new Label(String.valueOf(lineNumber));
        label.getStyleClass().add("lineno");
        return label;
    }
}
