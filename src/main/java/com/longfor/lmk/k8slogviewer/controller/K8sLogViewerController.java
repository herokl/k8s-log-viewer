package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import com.longfor.lmk.k8slogviewer.config.AppPreferences;
import com.longfor.lmk.k8slogviewer.config.K8sQuery;
import com.longfor.lmk.k8slogviewer.service.ClusterTreeService;
import com.longfor.lmk.k8slogviewer.service.LogFetchService;
import com.longfor.lmk.k8slogviewer.service.PodLogFileManager;
import com.longfor.lmk.k8slogviewer.utils.CommonUtils;
import com.longfor.lmk.k8slogviewer.utils.ExecutorManager;
import com.longfor.lmk.k8slogviewer.utils.LogStyleUtil;
import io.kubernetes.client.openapi.ApiException;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.longfor.lmk.k8slogviewer.utils.CommonUtils.showAlert;
import static com.longfor.lmk.k8slogviewer.utils.CommonUtils.showConfirm;
import static com.longfor.lmk.k8slogviewer.utils.LogStyleUtil.SEPARATOR_LINE;

/**
 * 主界面控制器。
 * 职责：UI 事件绑定 + 委托 Service/Manager 层处理业务逻辑。
 * 线程管理统一由 {@link ExecutorManager} 负责。
 */
public class K8sLogViewerController {

    private static final Logger log = LoggerFactory.getLogger(K8sLogViewerController.class);

    // ==================== FXML 组件 ====================

    @FXML public ProgressIndicator loadingIndicator;
    @FXML public AnchorPane logAreaContainer;
    @FXML public CodeArea headerArea;
    @FXML private TreeView<String> treeView;
    @FXML private CodeArea logArea;
    @FXML private TextField searchField;
    @FXML private Button refreshButton;
    @FXML private Button settingsButton;
    @FXML private TextField logSearchField;
    @FXML private TextField contextField;
    @FXML private TextField tailField;
    @FXML private Button searchToggleButton;
    @FXML private Button timeRangeButton;
    @FXML private Button searchButton;
    @FXML private VBox treePane;
    @FXML private Label collapseArrow;
    @FXML private SplitPane splitPane;
    @FXML public Button downLogFile;

    @FXML private HBox searchBar;
    @FXML private TextField inlineSearchField;
    @FXML private Label matchCountLabel;

    // ==================== 服务与状态 ====================

    private final ClusterTreeService clusterTreeService = new ClusterTreeService();
    private final PodLogFileManager fileManager = new PodLogFileManager();
    private boolean isTreePaneVisible = true;

    // 内联搜索状态
    private String lastKeyword = "";
    private final List<int[]> matchPositions = new ArrayList<>();
    private int currentMatchIndex = 0;

    // 日志缓冲
    private final Queue<String> logQueue = new ArrayDeque<>();
    private final Object logQueueLock = new Object();
    private static final int LOG_FLUSH_INTERVAL_MS = 50;

    // ==================== 初始化 ====================

    @FXML
    public void initialize() throws IOException {
        log.info("K8s 日志查看器初始化...");

        Platform.runLater(() -> {
            boolean ok = AppPreferences.initializeEnvironment();
            if (!ok) {
                log.warn("自动检测失败，需要手动配置");
                try {
                    new SettingsController().openSettingsDialog();
                } catch (IOException e) {
                    showAlert("初始化失败",
                            "请联系邮箱 <1272837619@qq.com> 解决，或自己调试解决！");
                }
            }
        });

        initSettingsIcon();
        initQueryDefaults();
        initCodeArea();
        initSearchBar();
        initFieldListeners();
        initTreeView();
    }

    private void initSettingsIcon() {
        try {
            ImageView icon = new ImageView(new Image(Objects.requireNonNull(
                    getClass().getResourceAsStream("/com/longfor/lmk/k8slogviewer/icons/settings.png"))));
            icon.setFitHeight(20);
            icon.setFitWidth(20);
            settingsButton.setGraphic(icon);
        } catch (Exception e) {
            Platform.runLater(() -> showAlert("错误", "无法加载设置图标: " + e.getMessage()));
        }
    }

    private void initQueryDefaults() {
        K8sQuery k8sQuery = AppConfig.getK8sQuery();
        k8sQuery.setContextLines(0);
        tailField.setPromptText("尾行数");
        tailField.setText("1000");
        k8sQuery.setTailLines(1000);
        searchButton.setText("搜索");
        searchButton.getStyleClass().add("action-button");
    }

    private void initCodeArea() {
        headerArea.setEditable(false);
        logArea.setEditable(false);

        IntFunction<Node> numberFactory = line -> {
            Label label = new Label(String.valueOf(line + 1));
            label.getStyleClass().add("line-number");
            return label;
        };
        logArea.setParagraphGraphicFactory(numberFactory);

        headerArea.setWrapText(true);
        logArea.setWrapText(true);

        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(logArea);
        AnchorPane.setTopAnchor(scrollPane, 30.0);
        AnchorPane.setBottomAnchor(scrollPane, 0.0);
        AnchorPane.setLeftAnchor(scrollPane, 0.0);
        AnchorPane.setRightAnchor(scrollPane, 0.0);
        logAreaContainer.getChildren().add(scrollPane);
    }

    private void initSearchBar() {
        inlineSearchField = new TextField();
        inlineSearchField.setPromptText("查找...");
        HBox.setHgrow(inlineSearchField, Priority.ALWAYS);

        Button prevBtn = new Button("上一处");
        prevBtn.setOnAction(e -> findPrev());

        Button nextBtn = new Button("下一处");
        nextBtn.setOnAction(e -> findNext());

        Button closeBtn = new Button("关闭");
        closeBtn.setOnAction(e -> closeSearch());

        matchCountLabel = new Label("0/0");
        matchCountLabel.setStyle("-fx-font-weight: bold;");

        searchBar = new HBox(5, inlineSearchField, prevBtn, nextBtn, matchCountLabel, closeBtn);
        searchBar.setPadding(new Insets(5));
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.setVisible(false);
        searchBar.setStyle("-fx-background-color: #e8e8e8;");

        AnchorPane.setTopAnchor(searchBar, 0.0);
        AnchorPane.setLeftAnchor(searchBar, 0.0);
        AnchorPane.setRightAnchor(searchBar, 0.0);

        logAreaContainer.getChildren().add(searchBar);

        logArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN).match(event)) {
                toggleSearchBar(true);
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE && searchBar.isVisible()) {
                toggleSearchBar(false);
                event.consume();
            }
        });

        inlineSearchField.setOnAction(e -> findNext());
    }

    private void initFieldListeners() {
        K8sQuery k8sQuery = AppConfig.getK8sQuery();

        contextField.textProperty().addListener((obs, oldVal, newVal) -> {
            newVal = newVal.isEmpty() ? "0" : newVal;
            if (!newVal.matches("\\d*") || Integer.parseInt(newVal) < 0) {
                contextField.setText(oldVal);
                Platform.runLater(() -> showAlert("错误", "请输入大于等于0的数字"));
            } else {
                int num = Integer.parseInt(newVal);
                k8sQuery.setFollow(num == 0);
                k8sQuery.setContextLines(num);
            }
        });

        tailField.textProperty().addListener((obs, oldVal, newVal) -> {
            newVal = newVal.isEmpty() ? "0" : newVal;
            if (!newVal.matches("\\d*") || Integer.parseInt(newVal) < 0) {
                tailField.setText(oldVal);
                Platform.runLater(() -> showAlert("错误", "请输入大于等于0的数字"));
            } else {
                int num = Integer.parseInt(newVal);
                if (num != 0) {
                    k8sQuery.setTailLines(num);
                    return;
                }
                Platform.runLater(() -> showConfirm("提示", "尾行数为 0|null 会非常卡，是否继续？",
                        () -> k8sQuery.setTailLines(0),
                        () -> tailField.setText(oldVal)));
            }
        });

        searchField.textProperty().addListener((obs, oldVal, newVal) -> refreshTree(newVal));
        timeRangeButton.setOnAction(e -> showTimeRangeDialog());

        inlineSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (searchBar.isVisible() && !newVal.equals(oldVal)) {
                ExecutorManager.debounce(
                        () -> computeAllMatchesInBackground(newVal),
                        300, TimeUnit.MILLISECONDS
                );
            }
        });
    }

    private void initTreeView() {
        treeView.setOnMouseClicked(this::handleTreeViewClick);

        treeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getParent() != null) {
                TreeItem<String> parent = newVal.getParent();
                if (parent.getParent() != null) {
                    String namespace = parent.getValue();
                    String podName = newVal.getValue();
                    log.info("选择命名空间: {}, Pod: {}", namespace, podName);

                    K8sQuery k8sQuery = AppConfig.getK8sQuery();
                    k8sQuery.setNamespace(namespace);
                    k8sQuery.setPodName(podName);

                    String text = logSearchField.getText();
                    if (text != null && !text.isEmpty()) {
                        k8sQuery.setKeyword(text);
                    }
                    try {
                        fileManager.switchPod(podName);
                    } catch (IOException e) {
                        log.warn("日志写入失败: {}", e.getMessage());
                    }
                    showLogs();
                }
            }
        });

        refreshTree(null);
    }

    // ==================== 树视图 ====================

    private void refreshTree(String filter) {
        TreeItem<String> rootItem = clusterTreeService.getRootItem();
        if (filter == null || filter.isEmpty()) {
            Platform.runLater(() -> treeView.setRoot(rootItem));
            return;
        }
        TreeItem<String> filtered = CommonUtils.filterTree(rootItem, filter);
        Platform.runLater(() -> treeView.setRoot(filtered));
    }

    private void handleTreeViewClick(MouseEvent event) {
        if (event.getClickCount() == 1) {
            TreeItem<String> selected = treeView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                selected.setExpanded(!selected.isExpanded());
            }
        }
    }

    @FXML
    public void refreshOnClick(MouseEvent mouseEvent) {
        refreshButton.setDisable(true);
        loadingIndicator.setVisible(true);

        ExecutorManager.submit(() -> {
            try {
                clusterTreeService.clearCache();
                refreshTree(searchField.getText());
            } catch (Exception e) {
                log.error("刷新失败: {}", e.getMessage());
            }
            Platform.runLater(() -> {
                refreshButton.setDisable(false);
                loadingIndicator.setVisible(false);
            });
        });
    }

    // ==================== 日志获取与缓冲 ====================

    @FXML
    public void searchButtonClick(MouseEvent mouseEvent) {
        AppConfig.getK8sQuery().setKeyword(logSearchField.getText());
        showLogs();
    }

    private void showLogs() {
        try {
            K8sQuery query = AppConfig.getK8sQuery();
            query.resetRuntimeState();

            Platform.runLater(() -> {
                LogStyleUtil.clear(logArea);
                LogStyleUtil.clear(headerArea);
            });

            ExecutorManager.stopLogFlushExecutor();
            ScheduledExecutorService flushExecutor = ExecutorManager.restartLogFlushExecutor();
            flushExecutor.scheduleAtFixedRate(
                    this::flushLogsToUI, 0, LOG_FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS
            );

            LogFetchService.fetchStreaming(line -> {
                        synchronized (logQueueLock) {
                            fileManager.append(line);
                            logQueue.offer(line);
                        }
                    }
            );
        } catch (IOException e) {
            log.error("获取日志失败: {}", e.getMessage());
            Platform.runLater(() -> showAlert("错误", "无法获取日志: " + e.getMessage()));
        }
    }

    private void flushLogsToUI() {
        List<String> batch = new ArrayList<>();
        synchronized (logQueueLock) {
            while (!logQueue.isEmpty()) {
                batch.add(logQueue.poll());
            }
        }
        if (batch.isEmpty()) return;
        Platform.runLater(() -> processLogBatch(batch));
    }

    private void processLogBatch(List<String> lines) {
        K8sQuery k8sQuery = AppConfig.getK8sQuery();
        boolean searchRunning = k8sQuery.isSearchRunning();
        boolean headerCaptured = k8sQuery.isHeaderCaptured();
        String logKeyword = k8sQuery.getKeyword();

        String searchKeyword = searchBar.isVisible() ? inlineSearchField.getText() : null;
        if (searchKeyword != null && searchKeyword.isBlank()) {
            searchKeyword = null;
        }

        for (String line : lines) {
            String lineWithNewline = line + "\n";
            if (headerCaptured) {
                handleHeaderLine(k8sQuery, line, lineWithNewline, logKeyword);
            } else {
                handleLogLine(line, lineWithNewline, logKeyword, searchKeyword);
            }
        }

        if (searchRunning) {
            logArea.moveTo(logArea.getLength());
            logArea.requestFollowCaret();
        }
    }

    private void handleHeaderLine(K8sQuery k8sQuery, String line,
                                  String lineWithNewline, String logKeyword) {
        boolean isSeparator = line.trim().contains(SEPARATOR_LINE);
        k8sQuery.setHeaderCaptured(!isSeparator);
        if (!isSeparator) {
            LogStyleUtil.setLogArea(headerArea, true, line, lineWithNewline, logKeyword, null);
        }
    }

    private void handleLogLine(String line, String lineWithNewline,
                               String logKeyword, String searchKeyword) {
        int startOffset = logArea.getLength();
        LogStyleUtil.setLogArea(logArea, false, line, lineWithNewline, logKeyword, searchKeyword);
        if (searchKeyword != null) {
            refreshMatchesIncremental(startOffset, lineWithNewline, searchKeyword);
        }
    }

    // ==================== 内联搜索 ====================

    @FXML
    private void findNext() {
        String keyword = inlineSearchField.getText();
        if (keyword == null || keyword.isEmpty()) return;
        computeAllMatches(keyword);
        if (matchPositions.isEmpty()) return;
        currentMatchIndex = (currentMatchIndex + 1) % matchPositions.size();
        selectCurrentMatch();
    }

    @FXML
    private void findPrev() {
        String keyword = inlineSearchField.getText();
        if (keyword == null || keyword.isEmpty()) return;
        computeAllMatches(keyword);
        if (matchPositions.isEmpty()) return;
        currentMatchIndex = (currentMatchIndex - 1 + matchPositions.size()) % matchPositions.size();
        selectCurrentMatch();
    }

    @FXML
    private void closeSearch() {
        toggleSearchBar(false);
    }

    private void toggleSearchBar(boolean show) {
        searchBar.setVisible(show);
        if (show) {
            inlineSearchField.requestFocus();
            inlineSearchField.selectAll();
            computeAllMatchesInBackground(inlineSearchField.getText());
        } else {
            matchPositions.clear();
            currentMatchIndex = 0;
            lastKeyword = "";
            rehighlightLogArea(false);
        }
    }

    private void computeAllMatches(String keyword) {
        if (!keyword.equals(lastKeyword)) {
            lastKeyword = keyword;
            matchPositions.clear();
            String text = logArea.getText();
            Matcher matcher = Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE).matcher(text);
            while (matcher.find()) {
                matchPositions.add(new int[]{matcher.start(), matcher.end()});
            }
        }
        highlightAllMatches(keyword);
        updateMatchLabel();
    }

    private void highlightAllMatches(String keyword) {
        String text = logArea.getText();
        String logKw = AppConfig.getK8sQuery().getKeyword();
        logArea.setStyleSpans(0, LogStyleUtil.computeHighlighting(false, text, logKw, keyword));
    }

    private void selectCurrentMatch() {
        if (matchPositions.isEmpty()) return;
        int[] pos = matchPositions.get(currentMatchIndex);
        logArea.selectRange(pos[0], pos[1]);
        logArea.requestFollowCaret();
        updateMatchLabel();
    }

    private void updateMatchLabel() {
        matchCountLabel.setText(matchPositions.isEmpty()
                ? "0/0"
                : (currentMatchIndex + 1) + "/" + matchPositions.size());
    }

    private void computeAllMatchesInBackground(String keyword) {
        ExecutorManager.submit(() -> {
            List<int[]> tempMatchPositions = new ArrayList<>();
            boolean includeSearch = keyword != null && !keyword.isBlank();
            String text = logArea.getText();
            String logKw = AppConfig.getK8sQuery().getKeyword();
            String searchKw = includeSearch ? keyword : null;

            if (includeSearch) {
                String lowerText = text.toLowerCase();
                String lowerKw = keyword.toLowerCase();
                int idx = 0;
                while ((idx = lowerText.indexOf(lowerKw, idx)) != -1) {
                    tempMatchPositions.add(new int[]{idx, idx + lowerKw.length()});
                    idx += lowerKw.length();
                }
            }

            StyleSpans<Collection<String>> spans =
                    LogStyleUtil.computeHighlighting(false, text, logKw, searchKw);

            Platform.runLater(() -> {
                matchPositions.clear();
                matchPositions.addAll(tempMatchPositions);
                lastKeyword = keyword != null ? keyword : "";
                logArea.setStyleSpans(0, spans);
                updateMatchLabel();
                if (!matchPositions.isEmpty()) {
                    currentMatchIndex = 0;
                    selectCurrentMatch();
                }
            });
        });
    }

    private void rehighlightLogArea(boolean includeSearch) {
        ExecutorManager.submit(() -> {
            String text = logArea.getText();
            String logKw = AppConfig.getK8sQuery().getKeyword();
            String searchKw = includeSearch ? inlineSearchField.getText() : null;
            if (searchKw != null && searchKw.isBlank()) searchKw = null;
            StyleSpans<Collection<String>> spans =
                    LogStyleUtil.computeHighlighting(false, text, logKw, searchKw);
            Platform.runLater(() -> logArea.setStyleSpans(0, spans));
        });
    }

    private void refreshMatchesIncremental(int offset, String newText, String keyword) {
        if (keyword == null || keyword.isEmpty()) return;
        String lowerNewText = newText.toLowerCase();
        String lowerKw = keyword.toLowerCase();
        int idx = 0;
        while ((idx = lowerNewText.indexOf(lowerKw, idx)) != -1) {
            matchPositions.add(new int[]{offset + idx, offset + idx + lowerKw.length()});
            idx += lowerKw.length();
        }
        updateMatchLabel();
    }

    // ==================== UI 事件处理 ====================

    @FXML
    public void handleOpenSettings(MouseEvent mouseEvent) {
        try {
            new SettingsController().openSettingsDialog();
        } catch (IOException e) {
            Platform.runLater(() -> showAlert("错误", "无法加载设置窗口: " + e.getMessage()));
        }
    }

    @FXML
    public void searchToggleClick(MouseEvent mouseEvent) {
        K8sQuery k8sQuery = AppConfig.getK8sQuery();
        boolean searchRunning = !k8sQuery.isSearchRunning();
        k8sQuery.setSearchRunning(searchRunning);
        searchToggleButton.setText(searchRunning ? "暂停" : "恢复");
    }

    @FXML
    private void toggleTreePane() {
        if (isTreePaneVisible) {
            treePane.setVisible(false);
            treePane.setManaged(false);
            splitPane.setDividerPositions(0.0);
            collapseArrow.setText("\u2BF0");
            isTreePaneVisible = false;
        } else {
            treePane.setVisible(true);
            treePane.setManaged(true);
            splitPane.setDividerPositions(0.3);
            collapseArrow.setText("\u2BF1");
            isTreePaneVisible = true;
        }
    }

    // ==================== 时间范围对话框 ====================

    private void showTimeRangeDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("选择日志起始时间");
        dialog.getIcons().add(new Image(Objects.requireNonNull(
                getClass().getResourceAsStream("/com/longfor/lmk/k8slogviewer/icons/settings.png"))));

        VBox dialogPane = new VBox(15);
        dialogPane.setPadding(new Insets(20));
        dialogPane.getStyleClass().add("dialog-pane");

        Label instruction = new Label("从哪天开始查看日志（直到现在）:");
        instruction.getStyleClass().add("label-tab");
        DatePicker startDate = new DatePicker(LocalDate.now());

        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        Button cancelButton = new Button("取消");
        cancelButton.getStyleClass().add("action-button");
        cancelButton.setOnAction(e -> dialog.close());

        Button applyButton = new Button("应用");
        applyButton.getStyleClass().add("action-button");
        applyButton.setOnAction(e -> {
            LocalDate date = startDate.getValue();
            if (date != null) {
                LocalDateTime start = date.atStartOfDay();
                long seconds = Duration.between(start, LocalDateTime.now()).getSeconds();
                if (seconds < 0) {
                    showAlert("错误", "选择的时间不能晚于现在");
                    return;
                }
                AppConfig.getK8sQuery().setSinceSeconds(seconds);
                showLogs();
                dialog.close();
            } else {
                showAlert("错误", "请选择开始日期");
            }
        });

        buttonBox.getChildren().addAll(applyButton, cancelButton);
        dialogPane.getChildren().addAll(instruction, startDate, buttonBox);

        Scene dialogScene = new Scene(dialogPane, Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        dialogScene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/styles.css")).toExternalForm());

        dialog.setScene(dialogScene);
        dialog.setResizable(false);
        dialog.showAndWait();
    }

    // ==================== 日志下载 ====================

    @FXML
    public void downLogFile(MouseEvent mouseEvent) {
        K8sQuery k8sQuery = AppConfig.getK8sQuery();
        String namespace = k8sQuery.getNamespace();
        String podName = k8sQuery.getPodName();

        if (namespace == null || podName == null) {
            Platform.runLater(() -> showAlert("错误", "请先选择一个 Pod"));
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("保存日志文件");
        String timestamp = LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        fileChooser.setInitialFileName(
                String.format("logs_%s_%s_%s.txt", namespace, podName, timestamp));
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("文本文件", "*.txt"));

        File file = fileChooser.showSaveDialog(downLogFile.getScene().getWindow());
        if (file == null) return;

        String filePath = file.getAbsolutePath();
        downLogFile.setDisable(true);
        loadingIndicator.setVisible(true);

        ExecutorManager.submit(() -> {
            try {
                String logs = LogFetchService.fetchFullLogs(namespace, podName);

                Files.createDirectories(file.toPath().getParent());
                Files.writeString(Paths.get(filePath), logs, StandardCharsets.UTF_8);
                log.info("日志已保存到: {}", filePath);

                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(new File(filePath));
                    Platform.runLater(() -> showAlert("成功", "日志文件已保存并打开: " + filePath));
                } else {
                    Platform.runLater(() -> showAlert("警告",
                            "日志文件已保存到 " + filePath + "，但系统不支持自动打开"));
                }
            } catch (ApiException e) {
                log.error("获取 Kubernetes 日志失败: {}", e.getResponseBody(), e);
                Platform.runLater(() -> showAlert("错误", "无法获取日志: " + e.getMessage()));
            } catch (IOException e) {
                log.error("保存或打开日志文件失败: {}", e.getMessage());
                Platform.runLater(() -> showAlert("错误", "无法保存或打开日志文件: " + e.getMessage()));
            } finally {
                Platform.runLater(() -> {
                    downLogFile.setDisable(false);
                    loadingIndicator.setVisible(false);
                });
            }
        });
    }
}
