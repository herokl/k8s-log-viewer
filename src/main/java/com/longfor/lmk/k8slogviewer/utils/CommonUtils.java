package com.longfor.lmk.k8slogviewer.utils;

import javafx.scene.control.Alert;

public class CommonUtils {
    private CommonUtils() {
        throw new IllegalStateException("Utility class");
    }
    // 显示警告对话框
    public static void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
