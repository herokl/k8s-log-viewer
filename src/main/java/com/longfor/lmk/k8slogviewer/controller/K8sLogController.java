package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import com.longfor.lmk.k8slogviewer.utils.KubectlLogFetcher;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.Config;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseEvent;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.*;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class K8sLogController {

    @FXML
    private TreeView<String> treeView;
    @FXML
    private TextFlow logArea;
    @FXML
    private TextField searchField;
    @FXML
    private Button refreshButton;
    @FXML
    private Button settingsButton;
    @FXML
    private TextArea logSearchField; // 改为 TextArea 支持换行
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

    private CoreV1Api api;
    private boolean isSearchRunning = true; // 控制定时搜索
    private String currentPod;
    private String currentNamespace;
    private String currentContainer;
    private int tailLines = 100;
    private int contextLines = 2;
    private Integer sinceSeconds = null; // 时间范围（秒）
    private Timer searchTimer;
    private String lastSearchKeyword = "";
    private Text loadingText; // 加载中提示

    @FXML
    public void initialize() {
        // 异步检查 Git 的 bash.exe（Windows 环境）
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            Platform.runLater(() -> {
                if (KubectlLogFetcher.findBashPath() == null) {
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("Git Bash 路径未配置");
                    alert.setHeaderText(null);
                    alert.setContentText("未检测到 Git 的 bash.exe。请设置 Git Bash 路径，以支持上下文搜索。\n" +
                            "点击 '设置' 按钮，手动配置 Git Bash 路径。");

                    ButtonType settingsButton = new ButtonType("设置");
                    ButtonType cancelButton = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
                    alert.getButtonTypes().setAll(settingsButton, cancelButton);

                    alert.showAndWait().ifPresent(response -> {
                        if (response == settingsButton) {
                            showGitBashSettingsDialog();  // 调用显示设置页面的方法
                        }
                    });
                }
            });
        }

        // 初始化 Kubernetes 客户端
        try {
            ApiClient client = Config.defaultClient();
            Configuration.setDefaultApiClient(client);
            api = new CoreV1Api();
        } catch (IOException e) {
            Platform.runLater(() -> showAlert("错误", "无法初始化 Kubernetes 客户端: " + e.getMessage()));
        }

        // 设置 settingsButton 图标
        try {
            ImageView icon = new ImageView(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/com/longfor/lmk/k8slogviewer/icons/settings.png"))));
            icon.setFitHeight(24);
            icon.setFitWidth(24);
            settingsButton.setGraphic(icon);
        } catch (Exception e) {
            Platform.runLater(() -> showAlert("错误", "无法加载设置图标: " + e.getMessage()));
        }

        logSearchField.setWrapText(false); // 支持换行
        contextField.setText(String.valueOf(contextLines));
        contextField.setPromptText("上下文行数");
        tailField.setText(String.valueOf(tailLines));
        tailField.setPromptText("尾行数");
        updateSearchButtonText();
        searchButton.setText("搜索");
        searchButton.getStyleClass().add("action-button");

        // 初始化加载中提示
        loadingText = new Text("加载中...");
        loadingText.getStyleClass().add("loading-text");

        // 输入验证（上下文行数和尾行数）
        contextField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal.matches("\\d*")) {
                contextField.setText(oldVal);
            } else if (!newVal.isEmpty()) {
                try {
                    contextLines = Integer.parseInt(newVal);
                    if (contextLines < 0) {
                        contextField.setText(oldVal);
                        Platform.runLater(() -> showAlert("错误", "上下文行数必须为非负整数"));
                    } else if (contextLines > 20) {
                        contextField.setText(oldVal);
                        Platform.runLater(() -> showAlert("错误", "上下文行数过大，不能超过20行"));
                    } else {
                        startSearchTimer(lastSearchKeyword);
                    }
                } catch (NumberFormatException e) {
                    contextField.setText(oldVal);
                    Platform.runLater(() -> showAlert("错误", "上下文行数过大"));
                }
            }
        });

        tailField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal.matches("\\d*")) {
                tailField.setText(oldVal);
            } else if (!newVal.isEmpty()) {
                try {
                    tailLines = Integer.parseInt(newVal);
                    if (tailLines < 0) {
                        tailField.setText(oldVal);
                        Platform.runLater(() -> showAlert("错误", "尾行数必须为非负整数"));
                    } else if (tailLines > 10000) {
                        tailField.setText(oldVal);
                        Platform.runLater(() -> showAlert("错误", "尾行数过大, 不能超过10000行，请使用搜索"));
                    } else {
                        startSearchTimer(lastSearchKeyword);
                    }
                } catch (NumberFormatException e) {
                    tailField.setText(oldVal);
                    Platform.runLater(() -> showAlert("错误", "尾行数过大"));
                }
            }
        });

        // 设置树视图单击事件
        treeView.setOnMouseClicked(this::handleTreeViewClick);

        // 设置日志区域上下文菜单（支持复制）
        ContextMenu contextMenu = new ContextMenu();
        logArea.setOnContextMenuRequested(e -> contextMenu.show(logArea, e.getScreenX(), e.getScreenY()));

        // 设置事件监听
        searchField.textProperty().addListener((obs, oldVal, newVal) -> refreshTree(newVal));
        refreshButton.setOnAction(e -> refreshTree(searchField.getText()));
        settingsButton.setOnAction(e -> showSettingsDialog());
        searchToggleButton.setOnAction(e -> toggleSearch());
        timeRangeButton.setOnAction(e -> showTimeRangeDialog());
        searchButton.setOnAction(e -> {
            lastSearchKeyword = logSearchField.getText();
            startSearchTimer(lastSearchKeyword);
        });

        // 树节点选择事件
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getParent() != null) {
                TreeItem<String> parent = newVal.getParent();
                if (parent.getParent() != null && parent.getParent().getParent() != null) {
                    currentContainer = newVal.getValue();
                    currentPod = parent.getValue();
                    currentNamespace = parent.getParent().getValue();
                    startSearchTimer(lastSearchKeyword);
                } else if (parent.getParent() != null) {
                    currentPod = newVal.getValue();
                    currentNamespace = parent.getValue();
                    currentContainer = null;
                    startSearchTimer(lastSearchKeyword);
                }
            }
        });

        // 初始加载树
        refreshTree("");
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

    // 切换定时搜索状态
    private void toggleSearch() {
        isSearchRunning = !isSearchRunning;
        updateSearchButtonText();
        if (!isSearchRunning) {
            stopSearchTimer();
        } else {
            startSearchTimer(lastSearchKeyword);
        }
    }

    // 更新搜索按钮文本
    private void updateSearchButtonText() {
        searchToggleButton.setText(isSearchRunning ? "暂停搜索" : "恢复搜索");
    }

    // 刷新树视图（支持子节点搜索）
    private void refreshTree(String filter) {
        TreeItem<String> root = new TreeItem<>("集群");
        root.setExpanded(true);

        try {
            List<V1Namespace> namespaces = api.listNamespace(null, null, null, null, null, null, null, null, null).getItems();
            for (V1Namespace ns : namespaces) {
                String nsName = ns.getMetadata().getName();
                TreeItem<String> nsItem = new TreeItem<>(nsName);
                boolean nsMatches = filter.isEmpty() || nsName.toLowerCase().contains(filter.toLowerCase());

                List<V1Pod> pods = api.listNamespacedPod(nsName, null, null, null, null, null, null, null, null, null).getItems();
                for (V1Pod pod : pods) {
                    String podName = pod.getMetadata().getName();
                    TreeItem<String> podItem = new TreeItem<>(podName);
                    boolean podMatches = filter.isEmpty() || podName.toLowerCase().contains(filter.toLowerCase());
                    if (!podItem.getChildren().isEmpty() || podMatches) {
                        nsItem.getChildren().add(podItem);
                        nsMatches = true;
                    }
                }

                if (!nsItem.getChildren().isEmpty() || nsMatches) {
                    root.getChildren().add(nsItem);
                }
            }
        } catch (ApiException e) {
            Platform.runLater(() -> showAlert("错误", "无法加载 Kubernetes 数据: " + e.getResponseBody()));
        }

        treeView.setRoot(root);
    }

    // 加载日志（初始加载并启动实时流，全量覆盖）
    private void loadLogs() {
        if (currentPod == null || currentNamespace == null) return;
        // 初始加载静态日志
        Object result = KubectlLogFetcher.fetchLogs(currentNamespace, currentPod, currentContainer, tailLines, sinceSeconds, false);
        if (result instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> logs = (List<String>) result;
            Platform.runLater(() -> setLogs(logs));
            if (logs.stream().anyMatch(line -> line.startsWith("❌"))) {
                Platform.runLater(() -> showAlert("错误", logs.get(logs.size() - 1)));
            }
        } else {
            Platform.runLater(() -> showAlert("错误", "无法加载日志: 意外的返回类型"));
        }
    }

    // 停止搜索定时器
    private void stopSearchTimer() {
        if (searchTimer != null) {
            searchTimer.cancel();
            searchTimer = null;
        }
    }

    // 启动搜索定时器
    private void startSearchTimer(String keyword) {
        stopSearchTimer();
        searchTimer = new Timer(true);
        long period = tailLines * 10L;
        period = Math.max(period, 3000L);
        period = Math.min(period, 60000L);
        searchTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (isSearchRunning) {
                    Platform.runLater(() -> searchLogs(keyword));
                }
            }
        }, 0, period);
    }

    // 搜索日志（全量覆盖，假设输入已通过命令行搜索）
    private void searchLogs(String keyword) {
        if (keyword.isEmpty()) {
            loadLogs();
            return;
        }
        // 假设 keyword 是命令行搜索结果（kubectl logs | grep -C）
        List<String> logs = KubectlLogFetcher.fetchLogWithContext(currentNamespace, currentPod, currentContainer, keyword, contextLines, sinceSeconds);

        Platform.runLater(() -> setLogs(logs));

        // 启动定时搜索（仅当搜索运行时）
        if (isSearchRunning) {
            startSearchTimer(keyword);
        }
    }

    // 追加日志到 TextFlow（实际为全量覆盖前的填充）
    private void setLogs(List<String> logs) {
        if (logs.isEmpty()) return;
        logArea.getChildren().clear();
        for (String line : logs) {
            if (!line.trim().isEmpty()) {
                logArea.getChildren().add(new Text(line + "\n"));
            }
        }
    }

    // 显示 Git Bash 路径设置页面
    private void showGitBashSettingsDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("设置 Git Bash 路径");

        VBox dialogPane = new VBox(10);
        dialogPane.setPadding(new Insets(20));

        TextField gitBashPathField = new TextField();
        gitBashPathField.setPromptText("请输入 Git Bash 路径");
        Button browseGitButton = new Button("浏览 Git 配置");
        browseGitButton.getStyleClass().add("action-button");

        browseGitButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            File file = fileChooser.showOpenDialog(dialog);
            if (file != null) {
                gitBashPathField.setText(file.getAbsolutePath());
            }
        });

        // 保存按钮
        Button saveButton = new Button("保存");
        saveButton.getStyleClass().add("action-button");

        saveButton.setOnAction(e -> {
            String path = gitBashPathField.getText();
            if (path != null && new File(path).exists()) {
                AppConfig.setGitBashPath(path);  // 保存路径
                showAlert("保存成功", "Git Bash 路径已保存！");
                dialog.close();
            } else {
                showAlert("错误", "路径无效，请确保 bash.exe 存在！");
            }
        });

        HBox buttonBox = new HBox(10);
        buttonBox.getChildren().addAll(browseGitButton, saveButton);

        dialogPane.getChildren().addAll(
                new Label("Git Bash 路径:"),
                gitBashPathField,
                buttonBox
        );

        Scene dialogScene = new Scene(dialogPane, 400, 150);
        dialog.setScene(dialogScene);
        dialog.show();
    }

    // 显示设置对话框（浏览和保存按钮同一行，支持 kubeconfig）
    private void showSettingsDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("设置 Kubernetes 配置文件和 Git 配置");

        // 创建对话框容器
        VBox dialogPane = new VBox(10);
        dialogPane.setPadding(new Insets(20));

        // Kubernetes 配置部分
        TextField kubeConfigPathField = new TextField();
        kubeConfigPathField.setPromptText("请输入 kubeconfig 文件路径");
        kubeConfigPathField.setPrefWidth(300);

        // Git 配置部分
        TextField gitConfigPathField = new TextField();
        gitConfigPathField.setPromptText("请输入 Git 配置文件路径");
        gitConfigPathField.setPrefWidth(300);

        // 按钮区，浏览按钮和保存按钮
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_LEFT);

        // 浏览按钮
        Button browseKubeButton = new Button("浏览 Kubeconfig");
        browseKubeButton.getStyleClass().add("action-button");

        Button browseGitButton = new Button("浏览 Git 配置");
        browseGitButton.getStyleClass().add("action-button");

        // 保存按钮
        Button saveButton = new Button("保存");
        saveButton.getStyleClass().add("action-button");

        buttonBox.getChildren().addAll(browseKubeButton, browseGitButton, saveButton);

        // 浏览按钮的事件：Kubeconfig 文件选择
        browseKubeButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            File file = fileChooser.showOpenDialog(dialog);
            if (file != null) {
                kubeConfigPathField.setText(file.getAbsolutePath());
            }
        });

        // 浏览按钮的事件：Git 配置文件选择
        browseGitButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            File file = fileChooser.showOpenDialog(dialog);
            if (file != null) {
                gitConfigPathField.setText(file.getAbsolutePath());
            }
        });

        // 保存按钮的事件：保存路径并设置 KUBECONFIG 和 Git 配置
        saveButton.setOnAction(e -> {
            String kubeconfigPath = kubeConfigPathField.getText();
            String gitconfigPath = gitConfigPathField.getText();

            if (!kubeconfigPath.isEmpty() && !gitconfigPath.isEmpty()) {
                // 验证文件路径
                File kubeFile = new File(kubeconfigPath);
                File gitFile = new File(gitconfigPath);

                if (kubeFile.exists() && kubeFile.isFile() && gitFile.exists() && gitFile.isFile()) {
                    try {
                        // 设置 KUBECONFIG 环境变量
                        System.setProperty("KUBECONFIG", kubeconfigPath);
                        ApiClient client = Config.fromConfig(kubeconfigPath);
                        Configuration.setDefaultApiClient(client);
                        api = new CoreV1Api();

                        // 设置 Git 配置（假设你有相应的 Git 配置操作）
                        AppConfig.setGitBashPath(gitconfigPath);
                        AppConfig.setKubeConfigPath(kubeconfigPath);
                        dialog.close();
                        refreshTree("");
                    } catch (IOException ex) {
                        showAlert("错误", "无法加载配置文件: " + ex.getMessage());
                    }
                } else {
                    showAlert("错误", "提供的文件路径无效，请检查文件是否存在");
                }
            } else {
                showAlert("错误", "请输入有效的 Kubeconfig 和 Git 配置文件路径");
            }
        });

        // 添加控件到对话框容器
        dialogPane.getChildren().addAll(
                new Label("Kubeconfig 文件路径:"),
                kubeConfigPathField,
                new Label("Git 配置文件路径:"),
                gitConfigPathField,
                buttonBox
        );

        // 设置对话框场景
        Scene dialogScene = new Scene(dialogPane, 500, 180);
        dialogScene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(dialogScene);
        dialog.show();
    }


    // 显示时间范围对话框
    private void showTimeRangeDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("选择时间范围");

        VBox dialogPane = new VBox(10);
        dialogPane.setPadding(new Insets(20));
        DatePicker startDate = new DatePicker();
        DatePicker endDate = new DatePicker();
        Button applyButton = new Button("应用");
        applyButton.getStyleClass().add("action-button");

        applyButton.setOnAction(e -> {
            LocalDateTime start = startDate.getValue() != null ? startDate.getValue().atStartOfDay() : null;
            LocalDateTime end = endDate.getValue() != null ? endDate.getValue().atTime(23, 59, 59) : null;
            if (start != null && end != null) {
                sinceSeconds = (int) java.time.Duration.between(start, LocalDateTime.now()).getSeconds();
                startSearchTimer(lastSearchKeyword);
                dialog.close();
            } else {
                showAlert("错误", "请选择有效的开始和结束日期");
            }
        });

        dialogPane.getChildren().addAll(
                new Label("开始日期:"),
                startDate,
                new Label("结束日期:"),
                endDate,
                applyButton
        );

        Scene dialogScene = new Scene(dialogPane, 300, 200);
        dialogScene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(dialogScene);
        dialog.show();
    }

    // 显示警告对话框
    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}