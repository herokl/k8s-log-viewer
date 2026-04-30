package com.longfor.lmk.k8slogviewer.utils;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.util.Duration;

import java.util.*;
import java.util.function.Consumer;

public class CommonUtils {
    private CommonUtils() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * TreeItem 没有 userData API，用外部 WeakHashMap 存储附加数据（如 Pod 状态 phase）。
     * WeakHashMap 确保 TreeItem 被 GC 后条目自动清除，不会内存泄漏。
     */
    private static final Map<TreeItem<String>, Object> TREE_ITEM_DATA =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 存储 TreeItem 附加数据 */
    public static void putTreeItemData(TreeItem<String> item, Object data) {
        if (item != null) {
            TREE_ITEM_DATA.put(item, data);
        }
    }

    /** 获取 TreeItem 附加数据 */
    public static Object getTreeItemData(TreeItem<String> item) {
        return item != null ? TREE_ITEM_DATA.get(item) : null;
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

    /**
     * 显示删除 Pod 确认弹窗，提供"优雅删除"和"强制删除"两个选项。
     * 使用自定义样式，与应用设置弹窗风格统一。
     *
     * @param podName   Pod 名称
     * @param namespace 命名空间
     * @param onConfirm 回调，参数 true 表示强制删除，false 表示优雅删除
     */
    public static void showDeletePodConfirm(String podName, String namespace, Consumer<Boolean> onConfirm) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("删除 Pod");
        dialog.setHeaderText(null);

        // 内容区域
        VBox content = new VBox(12);
        content.setStyle("-fx-padding: 16 20 4 20; -fx-background-color: #ffffff;");

        // 警告图标 + 提示文字
        HBox topRow = new HBox(10);
        topRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label iconLabel = new Label("\u26A0");
        iconLabel.setStyle("-fx-font-size: 22px; -fx-text-fill: #F39C12;");
        Label msgLabel = new Label("确定要删除以下 Pod 吗？此操作不可撤销。");
        msgLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #333;");
        msgLabel.setWrapText(true);
        topRow.getChildren().addAll(iconLabel, msgLabel);

        // Pod 信息卡片
        VBox infoBox = new VBox(6);
        infoBox.setStyle("-fx-background-color: #F5F6FA; -fx-background-radius: 8; -fx-padding: 10 14;");

        HBox nsRow = new HBox(8);
        nsRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label nsKey = new Label("命名空间");
        nsKey.setStyle("-fx-font-size: 12px; -fx-text-fill: #888; -fx-font-weight: bold;");
        Label nsVal = new Label(namespace);
        nsVal.setStyle("-fx-font-size: 12px; -fx-text-fill: #333;");
        nsRow.getChildren().addAll(nsKey, nsVal);

        HBox podRow = new HBox(8);
        podRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label podKey = new Label("Pod 名称");
        podKey.setStyle("-fx-font-size: 12px; -fx-text-fill: #888; -fx-font-weight: bold;");
        Label podVal = new Label(podName);
        podVal.setStyle("-fx-font-size: 13px; -fx-text-fill: #326CE5; -fx-font-weight: bold;");
        podRow.getChildren().addAll(podKey, podVal);

        infoBox.getChildren().addAll(nsRow, podRow);
        content.getChildren().addAll(topRow, infoBox);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getStylesheets().add(CommonUtils.class.getResource("/styles.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("dialog-pane");
        dialog.getDialogPane().setPrefSize(420, 220);

        ButtonType forceButton = new ButtonType("强制删除", javafx.scene.control.ButtonBar.ButtonData.LEFT);
        ButtonType graceButton = new ButtonType("优雅删除", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("取消", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(forceButton, graceButton, cancelButton);

        dialog.setResultConverter(btn -> {
            if (btn == forceButton || btn == graceButton) {
                return btn;
            }
            return null;
        });

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent()) {
            if (result.get() == forceButton) onConfirm.accept(true);
            else if (result.get() == graceButton) onConfirm.accept(false);
        }
    }
    public static TreeItem<String> filterTree(TreeItem<String> node, String filter) {
        return filterTreeInternal(node, filter.toLowerCase());
    }

    /**
     * 搜索过滤树：仅匹配 Pod 名称（叶子节点），命名空间不参与文本匹配。
     * 命名空间仅在有匹配子 Pod 时才保留，不会因为自身名称匹配而被显示。
     */
    private static TreeItem<String> filterTreeInternal(TreeItem<String> node, String filterLower) {
        // 叶子节点（Pod）：直接判断是否匹配
        if (node.isLeaf()) {
            String value = node.getValue();
            boolean matches = value != null && value.toLowerCase().contains(filterLower);
            if (matches) {
                return copyLeafNode(node);
            }
            return null;
        }

        // 非叶子节点（根节点/命名空间）：递归处理子节点，自身不参与文本匹配
        TreeItem<String> newParent = new TreeItem<>(node.getValue());
        for (TreeItem<String> child : node.getChildren()) {
            TreeItem<String> filteredChild = filterTreeInternal(child, filterLower);
            if (filteredChild != null) {
                newParent.getChildren().add(filteredChild);
            }
        }

        // 有子节点时保留并展开（搜索时自动展开到匹配的 Pod）
        if (!newParent.getChildren().isEmpty()) {
            newParent.setExpanded(true);
            return newParent;
        }
        // 根节点始终返回（避免整棵树变 null）
        if (node.getParent() == null) {
            return newParent;
        }
        return null;
    }

    /** 复制叶子节点（Pod），保留附加数据（Pod 状态） */
    public static TreeItem<String> copyLeafNode(TreeItem<String> original) {
        TreeItem<String> copy = new TreeItem<>(original.getValue());
        Object data = getTreeItemData(original);
        if (data != null) {
            putTreeItemData(copy, data);
        }
        return copy;
    }

    /**
     * 显示自动消失的 Toast 提示。
     *
     * @param anchor 锚点节点，用于获取所在 Stage
     * @param icon   图标文字（如 "✓"、"↻"）
     * @param text   提示文本
     * @param color  图标背景色（如 "#4caf50"）
     */
    public static void showToast(Node anchor, String icon, String text, String color) {
        Popup toast = new Popup();
        toast.setAutoFix(true);

        Label iconLabel = new Label(icon);
        iconLabel.setStyle("-fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold; -fx-background-color: " + color + "; -fx-background-radius: 10; -fx-alignment: center; -fx-pref-width: 20; -fx-pref-height: 20;");
        Label textLabel = new Label(text);
        textLabel.setStyle("-fx-text-fill: #333; -fx-font-size: 13px;");
        HBox box = new HBox(8, iconLabel, textLabel);
        box.setStyle("-fx-background-color: white; -fx-padding: 8 18; -fx-background-radius: 6; -fx-alignment: center; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 8, 0, 0, 2);");
        toast.getContent().add(box);

        var stage = anchor.getScene().getWindow();
        toast.show(stage);
        Platform.runLater(() -> {
            toast.setX(stage.getX() + (stage.getWidth() - box.getWidth()) / 2);
            toast.setY(stage.getY() + (stage.getHeight() - box.getHeight()) / 2);
        });

        PauseTransition wait = new PauseTransition(Duration.millis(500));
        wait.setOnFinished(e -> {
            FadeTransition fade = new FadeTransition(Duration.millis(500), box);
            fade.setFromValue(1.0);
            fade.setToValue(0.0);
            fade.setOnFinished(ev -> toast.hide());
            fade.play();
        });
        wait.play();
    }
}
