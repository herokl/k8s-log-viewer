package com.longfor.lmk.k8slogviewer.pojo;

import javafx.scene.Node;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.util.List;

public class VirtualLogView {

    private final CodeArea codeArea = new CodeArea();
    private final VirtualizedScrollPane<CodeArea> scrollPane;

    public VirtualLogView() {
        codeArea.setEditable(false);
        codeArea.setWrapText(false);
        scrollPane = new VirtualizedScrollPane<>(codeArea);
    }

    public Node getNode() {
        return scrollPane;
    }

    public void setLines(List<String> lines) {
        codeArea.clear();
        for (String line : lines) {
            codeArea.appendText(line);
            codeArea.appendText("\n");
        }
    }

    public void appendLine(String line) {
        codeArea.appendText(line + "\n");
    }

    public int getLineCount() {
        return codeArea.getParagraphs().size();
    }

    public void trimTo(int maxLines) {
        int extra = getLineCount() - maxLines;
        if (extra > 0) {
            codeArea.deleteText(0, codeArea.getParagraphLength(extra - 1) + 1);
        }
    }

    public void followTail() {
        codeArea.moveTo(codeArea.getLength());
        codeArea.requestFollowCaret();
    }
}
