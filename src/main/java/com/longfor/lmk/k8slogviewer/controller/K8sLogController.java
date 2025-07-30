package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.utils.KubectlLogFetcher;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.util.Config;
import javafx.application.Platform;
import javafx.fxml.FXML;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class K8sLogController {

    @FXML private TreeView<String> treeView;
    @FXML private TextFlow logArea;
    @FXML private ScrollPane logScrollPane;
    @FXML private TextField searchField;
    @FXML private Button refreshButton;
    @FXML private Button settingsButton;
    @FXML private TextArea logSearchField; // 改为 TextArea 支持换行
    @FXML private ComboBox<String> searchType;
    @FXML private TextField contextField; // 改为 TextField
    @FXML private TextField tailField; // 改为 TextField
    @FXML private Button searchToggleButton; // 控制定时搜索
    @FXML private Button timeRangeButton;
    @FXML private Button searchButton;

    private CoreV1Api api;
    private boolean isAutoScroll = true;
    private boolean isSearchRunning = true; // 控制定时搜索
    private String currentPod;
    private String currentNamespace;
    private String currentContainer;
    private int tailLines = 100;
    private int contextLines = 2;
    private Integer sinceSeconds = null; // 时间范围（秒）
    private Thread logStreamThread;
    private volatile boolean isStreaming = false;
    private Process logProcess;
    private Timer searchTimer;
    private String lastSearchKeyword = "";
    private String lastSearchType = "模糊搜索";
    private static final int MAX_LOG_LINES = 10000; // 最大日志行数
    private Text loadingText; // 加载中提示

    @FXML
    public void initialize() {
        // 异步检查 Git 的 bash.exe（Windows 环境）
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            Platform.runLater(() -> {
                if (KubectlLogFetcher.findBashPath() == null) {
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("Git 未安装");
                    alert.setHeaderText(null);
                    alert.setContentText("未检测到 Git 的 bash.exe，上下文搜索需要 Git Bash 支持。\n" +
                                         "是否尝试自动安装 Git？（需要管理员权限）");
                    ButtonType installButton = new ButtonType("自动安装");
                    ButtonType manualButton = new ButtonType("手动安装");
                    ButtonType cancelButton = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
                    alert.getButtonTypes().setAll(installButton, manualButton, cancelButton);

                    alert.showAndWait().ifPresent(response -> {
                        if (response == installButton) {
                            String installResult = KubectlLogFetcher.tryInstallGit();
                            showAlert("Git 安装结果", installResult);
                        } else if (response == manualButton) {
                            showAlert("手动安装 Git", "请手动安装 Git：\n" +
                                     "1. 访问 https://git-scm.com/download/win\n" +
                                     "2. 下载并运行安装程序\n" +
                                     "3. 确保 'Git Bash' 组件已安装\n" +
                                     "4. 在 Git Bash 中安装 kubectl：\n" +
                                     "   'curl -LO https://dl.k8s.io/release/v1.29.0/bin/linux/amd64/kubectl'\n" +
                                     "   'chmod +x kubectl && mv kubectl /usr/local/bin/'\n" +
                                     "5. 重启应用程序");
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
            ImageView icon = new ImageView(new Image(getClass().getResourceAsStream("/com/longfor/lmk/k8slogviewer/icons/settings.png")));
            icon.setFitHeight(24);
            icon.setFitWidth(24);
            settingsButton.setGraphic(icon);
        } catch (Exception e) {
            Platform.runLater(() -> showAlert("错误", "无法加载设置图标: " + e.getMessage()));
        }

        // 初始化控件
        searchType.setItems(FXCollections.observableArrayList("模糊搜索", "大小写敏感", "全匹配", "正则匹配"));
        searchType.setValue("模糊搜索");
        logSearchField.setPrefHeight(50); // TextArea 高度
        logSearchField.setWrapText(true); // 支持换行
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
                    } else if (!lastSearchKeyword.isEmpty()) {
                        searchLogs(lastSearchKeyword, lastSearchType);
                    } else {
                        loadLogs();
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
                    } else {
                        loadLogs();
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
        MenuItem copyItem = new MenuItem("复制日志");
        copyItem.setOnAction(e -> copyLogs());
        contextMenu.getItems().add(copyItem);
        logArea.setOnContextMenuRequested(e -> contextMenu.show(logArea, e.getScreenX(), e.getScreenY()));

        // 设置事件监听
        searchField.textProperty().addListener((obs, oldVal, newVal) -> refreshTree(newVal));
        refreshButton.setOnAction(e -> refreshTree(searchField.getText()));
        settingsButton.setOnAction(e -> showSettingsDialog());
        searchToggleButton.setOnAction(e -> toggleSearch());
        timeRangeButton.setOnAction(e -> showTimeRangeDialog());
        searchButton.setOnAction(e -> {
            lastSearchKeyword = logSearchField.getText().trim();
            lastSearchType = searchType.getValue();
            searchLogs(lastSearchKeyword, lastSearchType);
        });

        // 树节点选择事件
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getParent() != null) {
                TreeItem<String> parent = newVal.getParent();
                if (parent.getParent() != null && parent.getParent().getParent() != null) {
                    currentContainer = newVal.getValue();
                    currentPod = parent.getValue();
                    currentNamespace = parent.getParent().getValue();
                    loadLogs();
                } else if (parent.getParent() != null) {
                    currentPod = newVal.getValue();
                    currentNamespace = parent.getValue();
                    currentContainer = null;
                    loadLogs();
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
        } else if (!lastSearchKeyword.isEmpty()) {
            startSearchTimer(lastSearchKeyword, lastSearchType);
        }
        if (isAutoScroll) {
            Platform.runLater(() -> logScrollPane.setVvalue(1.0));
        }
    }

    // 更新搜索按钮文本
    private void updateSearchButtonText() {
        searchToggleButton.setText(isSearchRunning ? "暂停搜索" : "恢复搜索");
    }

    // 复制日志到剪贴板
    private void copyLogs() {
        StringBuilder logText = new StringBuilder();
        synchronized (logArea) {
            for (var node : logArea.getChildren()) {
                if (node instanceof Text) {
                    logText.append(((Text) node).getText());
                } else if (node instanceof TextFlow) {
                    for (var child : ((TextFlow) node).getChildren()) {
                        if (child instanceof Text) {
                            logText.append(((Text) child).getText());
                        }
                    }
                }
            }
        }
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(logText.toString());
        clipboard.setContent(content);
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

                    List<V1Container> containers = pod.getSpec().getContainers();
                    for (V1Container container : containers) {
                        String containerName = container.getName();
                        if (filter.isEmpty() || containerName.toLowerCase().contains(filter.toLowerCase())) {
                            podItem.getChildren().add(new TreeItem<>(containerName));
                            podMatches = true;
                        }
                    }

                    if (!podItem.getChildren().isEmpty() || podMatches || filter.isEmpty()) {
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

        stopLogStream();
        stopSearchTimer();
        synchronized (logArea) {
            logArea.getChildren().clear(); // 全量覆盖
            logArea.getChildren().add(loadingText); // 显示加载中
        }

        // 初始加载静态日志
        Object result = KubectlLogFetcher.fetchLogs(currentNamespace, currentPod, currentContainer, tailLines, sinceSeconds, false);
        if (result instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> logs = (List<String>) result;
            Platform.runLater(() -> {
                synchronized (logArea) {
                    logArea.getChildren().clear(); // 全量覆盖
                    appendLogs(logs, false);
                    if (isAutoScroll) {
                        logScrollPane.setVvalue(1.0);
                    }
                }
            });
            if (logs.stream().anyMatch(line -> line.startsWith("❌"))) {
                Platform.runLater(() -> showAlert("错误", logs.get(logs.size() - 1)));
            }
        } else {
            Platform.runLater(() -> showAlert("错误", "无法加载日志: 意外的返回类型"));
        }

        // 启动实时日志流
        if (lastSearchKeyword.isEmpty()) {
            isStreaming = true;
            logStreamThread = new Thread(() -> streamLogs());
            logStreamThread.setDaemon(true);
            logStreamThread.start();
        }
    }

    // 实时日志流（全量覆盖）
    private void streamLogs() {
        try {
            Object result = KubectlLogFetcher.fetchLogs(currentNamespace, currentPod, currentContainer, tailLines, sinceSeconds, true);
            if (!(result instanceof Process)) {
                Platform.runLater(() -> showAlert("错误", "无法启动实时日志流: " + result));
                return;
            }

            logProcess = (Process) result;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(logProcess.getInputStream()))) {
                String line;
                List<String> logs = new ArrayList<>();
                while (isStreaming && (line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty() && lastSearchKeyword.isEmpty()) {
                        logs.add(line);
                        // 每 100 行或结束时更新 UI
                        if (logs.size() >= 100) {
                            List<String> logsToDisplay = new ArrayList<>(logs);
                            Platform.runLater(() -> {
                                synchronized (logArea) {
                                    logArea.getChildren().clear(); // 全量覆盖
                                    appendLogs(logsToDisplay, false);
                                    if (isAutoScroll) {
                                        logScrollPane.setVvalue(1.0);
                                    }
                                }
                            });
                            logs.clear();
                        }
                    }
                }
                // 显示剩余日志
                if (!logs.isEmpty()) {
                    Platform.runLater(() -> {
                        synchronized (logArea) {
                            logArea.getChildren().clear(); // 全量覆盖
                            appendLogs(logs, false);
                            if (isAutoScroll) {
                                logScrollPane.setVvalue(1.0);
                            }
                        }
                    });
                }
            }
        } catch (Exception e) {
            if (isStreaming) {
                Platform.runLater(() -> showAlert("错误", "实时日志流失败: " + e.getMessage()));
            }
        }
    }

    // 停止日志流
    private void stopLogStream() {
        isStreaming = false;
        if (logProcess != null) {
            logProcess.destroy();
            logProcess = null;
        }
        if (logStreamThread != null) {
            logStreamThread.interrupt();
            logStreamThread = null;
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
    private void startSearchTimer(String keyword, String searchType) {
        stopSearchTimer();
        searchTimer = new Timer(true);
        searchTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (isSearchRunning && !keyword.isEmpty()) {
                    Platform.runLater(() -> searchLogs(keyword, searchType));
                }
            }
        }, 2000, 2000);
    }

    // 搜索日志（全量覆盖，假设输入已通过命令行搜索）
    private void searchLogs(String keyword, String searchType) {
        stopLogStream();
        stopSearchTimer();
        lastSearchKeyword = keyword;
        lastSearchType = searchType;

        if (keyword.isEmpty()) {
            loadLogs();
            return;
        }

        synchronized (logArea) {
            logArea.getChildren().clear(); // 全量覆盖
            logArea.getChildren().add(loadingText); // 显示加载中
        }

        // 假设 keyword 是命令行搜索结果（kubectl logs | grep -C）
        List<String> logs = new ArrayList<>();
        for (String line : keyword.split("\n")) {
            if (!line.trim().isEmpty()) {
                logs.add(line);
            }
        }

        Platform.runLater(() -> {
            synchronized (logArea) {
                logArea.getChildren().clear(); // 全量覆盖
                appendLogs(logs, true);
                if (isAutoScroll) {
                    logScrollPane.setVvalue(1.0);
                }
            }
        });

        // 启动定时搜索（仅当搜索运行时）
        if (isSearchRunning) {
            startSearchTimer(keyword, searchType);
        }
    }

    // 调整关键字以匹配搜索类型
    private String adaptKeywordForSearchType(String keyword, String searchType) {
        switch (searchType) {
            case "大小写敏感":
            case "模糊搜索":
                return keyword;
            case "全匹配":
                return "\\b" + Pattern.quote(keyword) + "\\b";
            case "正则匹配":
                try {
                    Pattern.compile(keyword); // 验证正则
                    return keyword;
                } catch (PatternSyntaxException e) {
                    Platform.runLater(() -> showAlert("错误", "无效的正则表达式: " + e.getMessage()));
                    return Pattern.quote(keyword);
                }
            default:
                return keyword;
        }
    }

    // 追加日志到 TextFlow（实际为全量覆盖前的填充）
    private void appendLogs(List<String> logs, boolean highlight) {
        if (logs.isEmpty()) return;

        // 限制最大日志行数
        synchronized (logArea) {
            if (logArea.getChildren().size() + logs.size() > MAX_LOG_LINES) {
                logArea.getChildren().remove(0, logArea.getChildren().size() + logs.size() - MAX_LOG_LINES);
            }
        }

        if (!highlight) {
            for (String line : logs) {
                if (!line.trim().isEmpty()) {
                    logArea.getChildren().add(new Text(line + "\n"));
                }
            }
        } else {
            String keyword = lastSearchKeyword;
            Pattern pattern = createSearchPattern(keyword, lastSearchType);
            for (String line : logs) {
                if (line.trim().isEmpty()) continue;
                if (pattern.matcher(line).find()) {
                    TextFlow textFlow = new TextFlow();
                    Matcher matcher = pattern.matcher(line);
                    int lastEnd = 0;
                    while (matcher.find()) {
                        if (matcher.start() > lastEnd) {
                            textFlow.getChildren().add(new Text(line.substring(lastEnd, matcher.start())));
                        }
                        Text highlighted = new Text(matcher.group());
                        highlighted.getStyleClass().add("highlight");
                        textFlow.getChildren().add(highlighted);
                        lastEnd = matcher.end();
                    }
                    if (lastEnd < line.length()) {
                        textFlow.getChildren().add(new Text(line.substring(lastEnd) + "\n"));
                    } else {
                        textFlow.getChildren().add(new Text("\n"));
                    }
                    logArea.getChildren().add(textFlow);
                } else {
                    logArea.getChildren().add(new Text(line + "\n"));
                }
            }
        }
    }

    // 创建搜索模式
    private Pattern createSearchPattern(String keyword, String searchType) {
        try {
            switch (searchType) {
                case "大小写敏感":
                    return Pattern.compile(Pattern.quote(keyword));
                case "全匹配":
                    return Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b");
                case "正则匹配":
                    return Pattern.compile(keyword);
                default:
                    return Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
            }
        } catch (PatternSyntaxException e) {
            Platform.runLater(() -> showAlert("错误", "无效的正则表达式: " + e.getMessage()));
            return Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
        }
    }

    // 显示设置对话框（浏览和保存按钮同一行，支持 kubeconfig）
    private void showSettingsDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("设置 Kubernetes 配置文件");

        VBox dialogPane = new VBox(10);
        dialogPane.setPadding(new Insets(20));

        TextField configPathField = new TextField();
        configPathField.setPromptText("请输入 kubeconfig 文件路径");

        HBox buttonBox = new HBox(10);
        Button browseButton = new Button("浏览");
        browseButton.getStyleClass().add("action-button");
        Button saveButton = new Button("保存");
        saveButton.getStyleClass().add("action-button");
        buttonBox.getChildren().addAll(browseButton, saveButton);

        browseButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
//            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Kube Config", "*.yml", "*.yaml"));
            File file = fileChooser.showOpenDialog(dialog);
            if (file != null) {
                configPathField.setText(file.getAbsolutePath());
            }
        });

        saveButton.setOnAction(e -> {
            try {
                String kubeconfigPath = configPathField.getText();
                if (!kubeconfigPath.isEmpty()) {
                    // 设置 KUBECONFIG 环境变量
                    System.setProperty("KUBECONFIG", kubeconfigPath);
                    ApiClient client = Config.fromConfig(kubeconfigPath);
                    Configuration.setDefaultApiClient(client);
                    api = new CoreV1Api();
                    dialog.close();
                    refreshTree("");
                } else {
                    showAlert("错误", "请输入有效的 kubeconfig 文件路径");
                }
            } catch (IOException ex) {
                showAlert("错误", "无法加载配置文件: " + ex.getMessage());
            }
        });

        dialogPane.getChildren().addAll(
            new Label("Kubeconfig 文件路径:"),
            configPathField,
            buttonBox
        );

        Scene dialogScene = new Scene(dialogPane, 400, 150);
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
                loadLogs();
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