package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.KubernetesLogViewer;
import com.longfor.lmk.k8slogviewer.util.AlertUtil;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

import java.io.File;

/**
 * 设置页面控制器，处理 kubeconfig 文件选择和保存逻辑
 */
@Slf4j
public class SettingsController {

    @FXML private TextField kubeconfigPathField;
    @FXML private Button browseButton;
    @FXML private Button saveButton;
    @FXML private Button cancelButton;

    private KubernetesLogViewer mainApp;
    private MainController mainController;

    /**
     * 设置主应用程序实例
     * @param mainApp 主应用程序实例
     */
    public void setMainApp(KubernetesLogViewer mainApp) {
        log.info("设置主应用程序实例");
        this.mainApp = mainApp;
    }

    /**
     * 设置主控制器实例
     * @param mainController 主控制器实例
     */
    public void setMainController(MainController mainController) {
        log.info("设置主控制器实例");
        this.mainController = mainController;
    }

    /**
     * 处理浏览按钮点击事件，选择 kubeconfig 文件
     */
    @FXML
    private void browseKubeconfig() {
        log.info("打开 kubeconfig 文件选择器");
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择 kubeconfig 文件");
//        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("YAML 文件", "*.yaml", "*.yml"));
        File file = fileChooser.showOpenDialog(kubeconfigPathField.getScene().getWindow());
        if (file != null) {
            kubeconfigPathField.setText(file.getAbsolutePath());
            log.info("选择 kubeconfig 文件: {}", file.getAbsolutePath());
        }
    }

    /**
     * 处理保存按钮点击事件，更新 Kubernetes API 客户端
     */
    @FXML
    private void saveSettings() {
        String kubeconfigPath = kubeconfigPathField.getText();
        if (kubeconfigPath == null || kubeconfigPath.trim().isEmpty()) {
            log.warn("kubeconfig 文件路径为空");
            AlertUtil.showError("错误", "请输入有效的 kubeconfig 文件路径");
            return;
        }

        try {
            File file = new File(kubeconfigPath);
            if (!file.exists()) {
                log.error("kubeconfig 文件不存在: {}", kubeconfigPath);
                AlertUtil.showError("错误", "指定的 kubeconfig 文件不存在");
                return;
            }

            // 更新 Kubernetes API 客户端
            log.info("更新 Kubernetes API 客户端，路径: {}", kubeconfigPath);
            mainApp.updateApi(kubeconfigPath);
            mainController.refreshNavigationTree();
            AlertUtil.showError("成功", "已成功加载 kubeconfig 文件");
            closeWindow();
        } catch (Exception e) {
            log.error("加载 kubeconfig 文件失败: {}", e.getMessage());
            AlertUtil.showError("错误", "无法加载 kubeconfig 文件: " + e.getMessage());
        }
    }

    /**
     * 处理取消按钮点击事件，关闭设置窗口
     */
    @FXML
    private void cancel() {
        log.info("取消设置，关闭窗口");
        closeWindow();
    }

    /**
     * 关闭设置窗口
     */
    private void closeWindow() {
        Stage stage = (Stage) cancelButton.getScene().getWindow();
        stage.close();
    }
}