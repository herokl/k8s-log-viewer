package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.K8sQuery;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.text.TextFlow;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.richtext.CodeArea;

import java.io.IOException;
import java.util.Objects;

import static com.longfor.lmk.k8slogviewer.config.AppConfig.initializeEnvironment;
import static com.longfor.lmk.k8slogviewer.utils.CommonUtils.showAlert;

@Slf4j
public class K8sLogViewerController {
    private static final K8sQuery K8S_QUERY = new K8sQuery();
    @FXML
    private TreeView<String> treeView;
    @FXML
    private CodeArea logArea;
    @FXML
    private TextField searchField;
    @FXML
    private Button refreshButton;
    @FXML
    private Button settingsButton;
    @FXML
    private TextField logSearchField;
    @FXML
    private TextField contextField; // 改为 TextField
    @FXML
    private TextField tailField; // 改为 TextField
    @FXML
    private Button searchToggleButton; // 控制定时搜索
    @FXML
    private Button timeRangeButton;
    @FXML
    private Button searchButton;

    @FXML
    public void initialize() throws IOException {
        log.info("k8s 日志查看 初始化...");
        // 异步检查 bash
        Platform.runLater(() -> {
            // 初始化配置文件
            boolean b = initializeEnvironment();
            if (!b) {
                log.warn("初始化失败，手动设置配置文件...");
                // 弹出设置窗口，让用户配置 Git Bash 路径与 kubeconfig 路径
                try {
                    SettingsController settingDialog = new SettingsController();
                    settingDialog.openSettingsDialog();
                } catch (IOException e) {
                    showAlert("初始化失败", "请联系邮箱 <lmk62023@outlook.com> 解决，或自己调试解决！");
                }
            }
        });
        // 设置 左侧设置图标
        try {
            ImageView icon = new ImageView(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/com/longfor/lmk/k8slogviewer/icons/settings.png"))));
            icon.setFitHeight(24);
            icon.setFitWidth(24);
            settingsButton.setGraphic(icon);
        } catch (Exception e) {
            Platform.runLater(() -> showAlert("错误", "无法加载设置图标: " + e.getMessage()));
        }
        // 设置右侧按钮跟图标
        int tailLines = 100;
        int contextLine = 2;
        K8S_QUERY.setTailLines(tailLines);
        K8S_QUERY.setContextLines(contextLine);
        contextField.setText(String.valueOf(contextLine));
        contextField.setPromptText("上下文行数");
        tailField.setText(String.valueOf(100));
        tailField.setPromptText("尾行数");
        searchToggleButton.setText("暂停");
        searchButton.setText("搜索");
        searchButton.getStyleClass().add("action-button");
        //初始化日志样式
        // 初始化 CodeArea
        logArea.setEditable(false);
        logArea.getStyleClass().add("log-area");

        // 上下文行数监听器 校验
        contextField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal.matches("\\d*") || newVal.isEmpty() || Integer.parseInt(newVal) < 0) {
                contextField.setText(oldVal);
                Platform.runLater(() -> showAlert("错误", "请输入大于0数字"));
            } else {
                K8S_QUERY.setContextLines(Integer.parseInt(newVal));
            }
        });
        // 截取日志多少行监听
        tailField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal.matches("\\d*") || newVal.isEmpty() || Integer.parseInt(newVal) <= 0) {
                tailField.setText(oldVal);
                Platform.runLater(() -> showAlert("错误", "请输入大于0数字"));
            } else {
                K8S_QUERY.setTailLines(Integer.parseInt(newVal));
            }
        });
        // 设置树视图单击事件
        treeView.setOnMouseClicked(this::handleTreeViewClick);
    }

    // 处理树视图单击事件
    private void handleTreeViewClick(MouseEvent event) {
        if (event.getClickCount() == 1) {
            TreeItem<String> selectedItem = treeView.getSelectionModel().getSelectedItem();
            if (selectedItem != null) {
                selectedItem.setExpanded(!selectedItem.isExpanded());
            }
        }
    }

    public void searchButtonClick(MouseEvent mouseEvent) {
        // TODO document why this method is empty
    }
}
