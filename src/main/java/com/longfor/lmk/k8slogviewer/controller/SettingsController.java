package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import com.longfor.lmk.k8slogviewer.config.AppPreferences;
import com.longfor.lmk.k8slogviewer.config.K8sClientManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Optional;

public class SettingsController {
    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    @FXML
    private TextField gitBashPathField;
    @FXML
    private TextField kubeConfigPathField;
    @FXML
    private TextField maxLogSizeField;
    @FXML
    private TextField logRetentionDaysField;

    @FXML
    public void initialize() {
        gitBashPathField.setText(AppPreferences.getGitBashPath());
        kubeConfigPathField.setText(AppPreferences.getKubeConfigPath());
        maxLogSizeField.setText(String.valueOf(AppPreferences.getMaxLogSizeMB()));
        logRetentionDaysField.setText(String.valueOf(AppPreferences.getLogRetentionDays()));
    }

    @FXML
    public void onBrowseGitBash() {
        File file = openFileChooser("选择 Git Bash 可执行文件");
        if (file != null) {
            gitBashPathField.setText(file.getAbsolutePath());
            AppPreferences.setGitBashPath(file.getAbsolutePath());
        }
    }

    @FXML
    public void onBrowseKubeconfig() {
        File file = openFileChooser("选择 kubeconfig 文件");
        if (file != null) {
            kubeConfigPathField.setText(file.getAbsolutePath());
            AppPreferences.setKubeConfigPath(file.getAbsolutePath());
            K8sClientManager.reset();
        }
    }

    private File openFileChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        return chooser.showOpenDialog(getWindow());
    }

    private Window getWindow() {
        return gitBashPathField.getScene().getWindow();
    }

    public void openSettingsDialog() throws IOException {
        URL url = getClass().getResource("/com/longfor/lmk/k8slogviewer/settings_dialog.fxml");
        FXMLLoader loader = new FXMLLoader(url);
        DialogPane dialogPane = loader.load();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("配置设置");
        dialog.setDialogPane(dialogPane);
        dialog.initOwner(AppConfig.getMainStage());

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get().getButtonData() == ButtonBar.ButtonData.OK_DONE) {
            saveSettings();
            log.info("保存配置");
        } else {
            log.info("取消配置");
        }
    }

    private void saveSettings() {
        // 保存最大日志容量
        String sizeText = maxLogSizeField.getText();
        if (sizeText != null && !sizeText.isBlank()) {
            try {
                int maxMB = Integer.parseInt(sizeText.trim());
                if (maxMB < 1) {
                    maxMB = 1;
                }
                AppPreferences.setMaxLogSizeMB(maxMB);
            } catch (NumberFormatException e) {
                log.warn("无效的日志容量值: {}", sizeText);
            }
        }

        // 保存日志保留天数
        String daysText = logRetentionDaysField.getText();
        if (daysText != null && !daysText.isBlank()) {
            try {
                int days = Integer.parseInt(daysText.trim());
                if (days < 1) {
                    days = 1;
                }
                AppPreferences.setLogRetentionDays(days);
            } catch (NumberFormatException e) {
                log.warn("无效的日志保留天数: {}", daysText);
            }
        }

        // kubeconfig 路径变化后重置 K8s 客户端
        K8sClientManager.reset();
    }
}
