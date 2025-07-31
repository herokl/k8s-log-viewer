package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

@Slf4j
public class SettingsController {

    @FXML
    private TextField gitBashPathField;
    @FXML
    private TextField kubeConfigPathField;

    @FXML
    public void initialize() {
        gitBashPathField.setText(AppConfig.getGitBashPath());
        kubeConfigPathField.setText(AppConfig.getKubeConfigPath());
    }

    public void onBrowseGitBash() {
        File file = openFileChooser("选择 Git Bash 可执行文件");
        if (file != null) {
            gitBashPathField.setText(file.getAbsolutePath());
            AppConfig.setGitBashPath(file.getAbsolutePath());
        }
    }

    public void onBrowseKubeconfig() {
        File file = openFileChooser("选择 kubeconfig 文件");
        if (file != null) {
            kubeConfigPathField.setText(file.getAbsolutePath());
            AppConfig.setKubeConfigPath(file.getAbsolutePath());
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
        // 加载 FXML 文件
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/longfor/lmk/k8slogviewer/settings_dialog.fxml"));
        DialogPane dialogPane = loader.load();

        // 创建 Dialog
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("配置设置");
        dialog.setDialogPane(dialogPane);

        // 显示并等待用户操作
        Optional<ButtonType> result = dialog.showAndWait();

        if (result.isPresent() && result.get().getButtonData() == ButtonBar.ButtonData.OK_DONE) {
            // 用户点击了“保存”
            log.info("保存配置");
        } else {
            log.info("取消配置");
        }
    }
}
