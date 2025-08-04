package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import com.longfor.lmk.k8slogviewer.config.K8sQuery;
import com.longfor.lmk.k8slogviewer.utils.CommonUtils;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.richtext.CodeArea;

import java.io.IOException;
import java.util.Objects;

import static com.longfor.lmk.k8slogviewer.config.AppConfig.initializeEnvironment;
import static com.longfor.lmk.k8slogviewer.utils.CommonUtils.showAlert;

@Slf4j
public class K8sLogViewerController {
    private static final K8sQuery K8S_QUERY = new K8sQuery();
    public ProgressIndicator loadingIndicator;
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
            icon.setFitHeight(20);
            icon.setFitWidth(20);
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
        searchField.textProperty().addListener((obs, oldVal, newVal) -> refreshTree(newVal));
        // 设置树视图单击事件
        treeView.setOnMouseClicked(this::handleTreeViewClick);

        // 树节点选择事件
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getParent() != null) {
                TreeItem<String> parent = newVal.getParent();
                if (parent.getParent() != null) {
                    K8S_QUERY.setNamespace(newVal.getValue());
                    K8S_QUERY.setPodName(newVal.getValue());
                }
            }
        });

        // 初始加载树
        refreshTree(null);
    }

    private void refreshTree(String filter) {
        TreeItem<String> rootItem = AppConfig.getRootItem();
        if (filter == null || filter.isEmpty()) {
            setTreeRoot(rootItem);
            return;
        }
        TreeItem<String> treeItem = CommonUtils.filterTree(rootItem, filter);
        setTreeRoot(treeItem);
    }

    private void setTreeRoot(TreeItem<String> rootItem) {
        Platform.runLater(() -> treeView.setRoot(rootItem));
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

    @FXML
    public void searchButtonClick(MouseEvent mouseEvent) {
    }

    @FXML
    public void handleOpenSettings(MouseEvent mouseEvent) {
        SettingsController settingDialog = new SettingsController();
        try {
            settingDialog.openSettingsDialog();
        } catch (IOException e) {
            Platform.runLater(() -> showAlert("错误", "无法加载设置窗口: " + e.getMessage()));
        }
//        try {
//            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/longfor/lmk/k8slogviewer/settings_dialog.fxml"));
//            DialogPane dialogPane = loader.load();
//
//            Dialog<ButtonType> dialog = new Dialog<>();
//            dialog.setTitle("设置");
//            dialog.setDialogPane(dialogPane);
//            dialog.initOwner(AppConfig.getMainStage()); // 指定主窗口作为 owner
//            dialog.initModality(Modality.APPLICATION_MODAL);
//
//            Optional<ButtonType> result = dialog.showAndWait();
//            if (result.isPresent() && result.get().getButtonData() == ButtonBar.ButtonData.OK_DONE) {
//                // 用户点击了“保存”按钮
//                log.info("设置保存成功");
//                log.info("Git Bash: {}", AppConfig.getGitBashPath());
//                log.info("KubeConfig: {}", AppConfig.getKubeConfigPath());
//            }
//        } catch (IOException e) {
//            log.error("无法加载设置窗口: {}", e.getMessage());
//            Platform.runLater(() -> showAlert("错误", "无法加载设置窗口: " + e.getMessage()));
//        }
    }

    @FXML
    public void refreshOnClick(MouseEvent mouseEvent) {
        // 禁用按钮并显示“加载中...”
        refreshButton.setDisable(true);
        loadingIndicator.setVisible(true);

        new Thread(() -> {
            try {
                // 执行刷新逻辑
                AppConfig.clearRootItem();
                refreshTree(searchField.getText());
            } catch (Exception e) {
                log.error("刷新失败: {}", e.getMessage());
            }
            Platform.runLater(() -> {
                refreshButton.setDisable(false);
                loadingIndicator.setVisible(false);
            });
        }).start();

    }
}
