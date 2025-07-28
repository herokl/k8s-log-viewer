package com.longfor.lmk.k8slogviewer.util;

import javafx.scene.control.Alert;

/**
 * 错误提示工具类
 */
public class AlertUtil {
    private AlertUtil() {
        throw new IllegalStateException("工具类");
    }
    /**
     * 显示错误提示对话框
     * @param title 标题
     * @param message 错误信息
     */
    public static void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

}