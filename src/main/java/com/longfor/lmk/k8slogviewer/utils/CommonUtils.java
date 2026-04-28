package com.longfor.lmk.k8slogviewer.utils;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TreeItem;

import java.util.ArrayList;
import java.util.List;

public class CommonUtils {
    private CommonUtils() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * 解析搜索关键字。
     * 支持两种分隔符：
     * <ul>
     *   <li>{@code \0}（标签式 UI 传入，每个标签作为一个完整关键字）</li>
     *   <li>空格（兼容直接输入的简单场景）</li>
     * </ul>
     *
     * @param input 搜索字符串
     * @return 解析后的关键字列表
     */
    public static List<String> parseSearchKeywords(String input) {
        List<String> keywords = new ArrayList<>();
        if (input == null || input.isBlank()) return keywords;

        // 优先按 \0 分隔（标签式 UI）
        if (input.indexOf('\0') >= 0) {
            for (String part : input.split("\0")) {
                if (!part.isBlank()) keywords.add(part);
            }
        } else {
            // 兼容：按空格分隔
            for (String part : input.trim().split("\\s+")) {
                if (!part.isBlank()) keywords.add(part);
            }
        }
        return keywords;
    }
    // 显示警告对话框
    public static void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    /**
     * 显示确认弹窗，点击“确定”时执行回调逻辑
     *
     * @param title     弹窗标题
     * @param content   弹窗内容
     * @param onConfirm 用户点击“确定”后执行的逻辑
     */
    public static void showConfirm(String title, String content, Runnable onConfirm, Runnable onCancel) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);

        ButtonType confirmButton = new ButtonType("继续", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(confirmButton, cancelButton);

        alert.showAndWait().ifPresent(response -> {
            if (response == confirmButton) {
                if (onConfirm != null) onConfirm.run();
            } else {
                if (onCancel != null) onCancel.run();
            }
        });
    }
    public static TreeItem<String> filterTree(TreeItem<String> node, String filter) {
        // 叶子节点：直接判断是否匹配
        if (node.isLeaf()) {
            String value = node.getValue();
            boolean matches = value != null && value.toLowerCase().contains(filter.toLowerCase());
            if (matches) {
                // 保留原始节点（含 graphic 和 userData）
                return node;
            }
            return null;
        }

        // 非叶子节点：处理子节点
        TreeItem<String> newParent = new TreeItem<>(node.getValue());
        for (TreeItem<String> child : node.getChildren()) {
            TreeItem<String> filteredChild = filterTree(child, filter);
            if (filteredChild != null) {
                newParent.getChildren().add(filteredChild);
            }
        }

        // 检查父节点是否需要保留
        if (!newParent.getChildren().isEmpty()) {
            newParent.setExpanded(node.isExpanded() || (filter != null && !filter.isEmpty()));
            return newParent;
        } else {
            if (newParent.getValue() != null &&
                    newParent.getValue().toLowerCase().contains(filter.toLowerCase())) {
                return newParent;
            }
            return null;
        }
    }
}
