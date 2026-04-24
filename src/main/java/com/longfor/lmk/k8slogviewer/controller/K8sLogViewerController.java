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
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

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
    @FXML private Button scrollToTopButton;
    @FXML private Button scrollToBottomButton;
    @FXML private Button openLogFileButton;
    @FXML private Button searchButton;
    @FXML private VBox treePane;
    @FXML private Label collapseArrow;
    @FXML private SplitPane splitPane;

    @FXML private HBox searchBar;
    @FXML private TextField inlineSearchField;
    @FXML private Label matchCountLabel;

    // ==================== 服务与状态 ====================

    private final ClusterTreeService clusterTreeService = new ClusterTreeService();
    private final PodLogFileManager fileManager = new PodLogFileManager();
    private boolean isTreePaneVisible = true;
    private VirtualizedScrollPane<CodeArea> logScrollPane;

    // 内联搜索状态
    private String lastKeyword = "";

    // 日志缓冲
    private final Queue<String> logQueue = new ArrayDeque<>();
    private final Object logQueueLock = new Object();
    private static final int LOG_FLUSH_INTERVAL_MS = 50;

    // 日志行数上限，始终保持在 1000 行以内，保证 UI 丝滑
    private static final int MAX_LOG_LINES = 1000;

    // 历史日志加载：每次从磁盘加载的行数
    private static final int HISTORY_LOAD_LINES = 1000;
    // 是否正在加载历史（防止重复触发）
    private volatile boolean loadingHistory = false;

    // ===== 视图行号追踪 =====
    // 当前视图第一行对应的磁盘文件行号（0-based）
    private int viewStartLine = 0;
    // 当前视图末尾行号之后对应的磁盘文件行号（0-based，exclusive）
    // 仅在内容实际追加到 UI 时更新，始终等于 viewStartLine + logArea.getParagraphs().size()
    private int viewEndLine = 0;
    // 磁盘文件写入进度行号（0-based，exclusive），即使暂停也持续追踪
    private int diskEndLine = 0;

    // ===== 磁盘搜索状态 =====
    // 磁盘文件中所有匹配的行号（0-based，从文件开头算起）
    private final List<Integer> diskMatchLineNumbers = new ArrayList<>();
    private int currentDiskMatchIndex = -1;

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
            Label label = new Label(String.valueOf(viewStartLine + line + 1));
            label.getStyleClass().add("line-number");
            return label;
        };
        logArea.setParagraphGraphicFactory(numberFactory);

        headerArea.setWrapText(true);
        logArea.setWrapText(true);

        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(logArea);
        logScrollPane = scrollPane;
        AnchorPane.setTopAnchor(scrollPane, 30.0);
        AnchorPane.setBottomAnchor(scrollPane, 0.0);
        AnchorPane.setLeftAnchor(scrollPane, 0.0);
        AnchorPane.setRightAnchor(scrollPane, 0.0);
        logAreaContainer.getChildren().add(scrollPane);

        // 滚动到顶部时自动从磁盘加载更多历史日志
        scrollPane.estimatedScrollYProperty().addListener((obs, oldVal, newVal) -> {
            if (!loadingHistory && newVal != null && newVal.doubleValue() <= 5.0) {
                loadHistoryFromDisk();
            }
            // 滚动到底部时自动从磁盘加载更多后续日志
            if (!loadingHistory && newVal != null) {
                double totalHeight = logArea.getTotalHeightEstimate();
                double viewportHeight = scrollPane.getHeight();
                double maxScroll = totalHeight - viewportHeight;
                if (maxScroll > 0 && (maxScroll - newVal.doubleValue()) <= 30.0) {
                    loadForwardFromDisk();
                }
            }
        });
    }

    /**
     * 刷新行号显示（viewStartLine 变化后调用）。
     */
    private void refreshLineNumbers() {
        IntFunction<Node> numberFactory = line -> {
            Label label = new Label(String.valueOf(viewStartLine + line + 1));
            label.getStyleClass().add("line-number");
            return label;
        };
        logArea.setParagraphGraphicFactory(numberFactory);
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

        inlineSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (searchBar.isVisible() && !newVal.equals(oldVal)) {
                ExecutorManager.debounce(
                        () -> Platform.runLater(() -> searchDiskInBackground(newVal)),
                        300, TimeUnit.MILLISECONDS
                );
            }
        });
    }

    private void initTreeView() {
        treeView.setOnMouseClicked(this::handleTreeViewClick);

        // 注册磁盘文件截断回调，截断时重置行号映射
        fileManager.setOnFileTruncated(removedLines -> Platform.runLater(() -> {
            // 文件被截断后，磁盘行号整体前移 removedLines，需同步调整视图行号映射
            viewStartLine = Math.max(0, viewStartLine - removedLines);
            viewEndLine = Math.max(0, viewEndLine - removedLines);
            diskEndLine = Math.max(0, diskEndLine - removedLines);
            refreshLineNumbers();
            // 搜索结果已失效，清除
            diskMatchLineNumbers.clear();
            currentDiskMatchIndex = -1;
            lastKeyword = "";
        }));

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
            viewStartLine = 0;
            viewEndLine = 0;
            diskEndLine = 0;
            loadingHistory = false;

            Platform.runLater(() -> {
                LogStyleUtil.clear(logArea);
                LogStyleUtil.clear(headerArea);
                refreshLineNumbers();
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
        String logKeyword = k8sQuery.getKeyword();

        // 分离 header 行和 log 行
        List<String> headerLines = new ArrayList<>();
        List<String> logLines = new ArrayList<>();

        for (String line : lines) {
            if (k8sQuery.isHeaderCaptured()) {
                boolean isSeparator = line.trim().contains(SEPARATOR_LINE);
                if (isSeparator) {
                    k8sQuery.setHeaderCaptured(false);
                } else {
                    headerLines.add(line);
                }
            } else {
                logLines.add(line);
            }
        }

        // 批量追加 header 行（通常很少，逐行即可）
        for (String line : headerLines) {
            LogStyleUtil.appendHeaderLine(headerArea, line, logKeyword);
        }

        // 暂停时不追加日志到 UI，但磁盘写入继续（logFetchService 回调中已写入）
        // 恢复时通过 resumeAndCatchUp 补齐
        if (!searchRunning || logLines.isEmpty()) {
            // 即使暂停，也需要更新 diskEndLine 以追踪磁盘进度
            if (!logLines.isEmpty()) {
                diskEndLine += logLines.size();
            }
            return;
        }

        // 批量追加 log 行：一次 appendText + 一次 setStyleSpans
        LogStyleUtil.appendBatch(logArea, logLines, logKeyword, null);
        viewEndLine += logLines.size();
        diskEndLine += logLines.size();

        // 内联搜索关键字高亮与匹配更新
        String searchKw = (searchBar != null && searchBar.isVisible()) ? inlineSearchField.getText() : null;
        if (searchKw != null && !searchKw.isBlank()) {
            rehighlightLogArea(searchKw);
            // 重置 lastKeyword 以触发后台重新搜索（新日志可能产生新匹配）
            lastKeyword = "";
            ExecutorManager.debounce(
                    () -> Platform.runLater(() -> searchDiskInBackground(searchKw, false)),
                    500, TimeUnit.MILLISECONDS
            );
        }

        // 裁剪旧行，始终保持在 MAX_LOG_LINES 以内
        trimLogArea();

        logArea.moveTo(logArea.getLength());
        logArea.requestFollowCaret();
    }

    /**
     * 裁剪旧行，始终保持 UI 在 MAX_LOG_LINES 以内。
     * 同步更新 viewStartLine / viewEndLine 跟踪当前视图对应的磁盘行号。
     */
    private void trimLogArea() {
        int paragraphCount = logArea.getParagraphs().size();
        if (paragraphCount <= MAX_LOG_LINES) {
            // viewEndLine 已经在 processLogBatch 中更新，viewStartLine 从 viewEndLine 推算
            viewStartLine = viewEndLine - paragraphCount;
            if (viewStartLine < 0) viewStartLine = 0;
            refreshLineNumbers();
            return;
        }

        int removeCount = Math.min(paragraphCount - MAX_LOG_LINES + (MAX_LOG_LINES / 10), paragraphCount);
        int endPos = 0;
        for (int i = 0; i < removeCount; i++) {
            endPos += logArea.getParagraphs().get(i).length() + 1;
        }
        endPos = Math.min(endPos, logArea.getLength());
        logArea.deleteText(0, endPos);

        viewStartLine += removeCount;

        refreshLineNumbers();

        // 裁剪后搜索偏移已失效，清除
        lastKeyword = "";
    }

    /**
     * 从磁盘加载更早的历史日志，在用户滚动到顶部时触发。
     * 加载后保持当前可视位置不变（避免跳动），只在前方插入历史内容。
     */
    private void loadHistoryFromDisk() {
        String podName = AppConfig.getK8sQuery().getPodName();
        if (podName == null || loadingHistory) return;

        // 如果已加载到文件开头，无需再加载
        if (viewStartLine <= 0) return;

        loadingHistory = true;
        ExecutorManager.submit(() -> {
            // 读取 viewStartLine 之前的行
            int count = Math.min(HISTORY_LOAD_LINES, viewStartLine);
            int startLine = viewStartLine - count;
            List<String> historyLines = fileManager.readLogLines(podName, startLine, count);

            if (historyLines.isEmpty()) {
                loadingHistory = false;
                return;
            }

            String logKeyword = AppConfig.getK8sQuery().getKeyword();

            Platform.runLater(() -> {
                double scrollX = logScrollPane.getEstimatedScrollX();
                double scrollY = logScrollPane.getEstimatedScrollY();

                LogStyleUtil.prependBatch(logArea, historyLines, logKeyword, null);
                viewStartLine -= historyLines.size();

                refreshLineNumbers();

                logScrollPane.estimatedScrollXProperty().setValue(scrollX);
                logScrollPane.estimatedScrollYProperty().setValue(scrollY);

                loadingHistory = false;
            });
        });
    }

    /**
     * 从磁盘加载更新的日志（向后翻页），在用户滚动到底部时触发。
     * 保持当前可视位置不变，只在后方追加内容。
     */
    private void loadForwardFromDisk() {
        String podName = AppConfig.getK8sQuery().getPodName();
        if (podName == null || loadingHistory) return;

        // 如果磁盘文件没有更多内容，无需加载
        if (diskEndLine <= viewEndLine) return;

        loadingHistory = true;
        ExecutorManager.submit(() -> {
            int count = Math.min(HISTORY_LOAD_LINES, diskEndLine - viewEndLine);
            List<String> forwardLines = fileManager.readLogLines(podName, viewEndLine, count);

            if (forwardLines.isEmpty()) {
                loadingHistory = false;
                return;
            }

            String logKeyword = AppConfig.getK8sQuery().getKeyword();

            Platform.runLater(() -> {
                double scrollX = logScrollPane.getEstimatedScrollX();
                double scrollY = logScrollPane.getEstimatedScrollY();

                LogStyleUtil.appendBatch(logArea, forwardLines, logKeyword, null);
                viewEndLine += forwardLines.size();

                // 裁剪顶部旧行
                trimLogArea();

                refreshLineNumbers();

                logScrollPane.estimatedScrollXProperty().setValue(scrollX);
                logScrollPane.estimatedScrollYProperty().setValue(scrollY);

                loadingHistory = false;
            });
        });
    }

    /**
     * 从磁盘加载指定行号范围的日志，替换当前视图内容。
     * 用于 Ctrl+F 跳转到不在当前视图的匹配行。
     *
     * @param centerLine 目标中心行号（0-based）
     * @param onLoaded   加载完成后的回调（在 Platform 线程执行），可为 null
     */
    private void loadViewFromDisk(int centerLine, Runnable onLoaded) {
        String podName = AppConfig.getK8sQuery().getPodName();
        if (podName == null) return;

        // 以 centerLine 为中心，加载 MAX_LOG_LINES 行
        int half = MAX_LOG_LINES / 2;
        int startLine = Math.max(0, centerLine - half);
        int count = MAX_LOG_LINES;

        loadingHistory = true;
        ExecutorManager.submit(() -> {
            List<String> lines = fileManager.readLogLines(podName, startLine, count);
            if (lines.isEmpty()) {
                loadingHistory = false;
                return;
            }

            String logKeyword = AppConfig.getK8sQuery().getKeyword();

            Platform.runLater(() -> {
                LogStyleUtil.clear(logArea);
                LogStyleUtil.appendBatch(logArea, lines, logKeyword, null);
                viewStartLine = startLine;
                viewEndLine = startLine + lines.size();

                refreshLineNumbers();

                loadingHistory = false;

                if (onLoaded != null) {
                    onLoaded.run();
                }
            });
        });
    }

    // ==================== 内联搜索（基于磁盘文件） ====================

    @FXML
    private void findNext() {
        String keyword = inlineSearchField.getText();
        if (keyword == null || keyword.isEmpty()) return;
        if (diskMatchLineNumbers.isEmpty()) return;

        currentDiskMatchIndex = (currentDiskMatchIndex + 1) % diskMatchLineNumbers.size();
        navigateToDiskMatch();
    }

    @FXML
    private void findPrev() {
        String keyword = inlineSearchField.getText();
        if (keyword == null || keyword.isEmpty()) return;
        if (diskMatchLineNumbers.isEmpty()) return;

        currentDiskMatchIndex = (currentDiskMatchIndex - 1 + diskMatchLineNumbers.size()) % diskMatchLineNumbers.size();
        navigateToDiskMatch();
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
            searchDiskInBackground(inlineSearchField.getText());
        } else {
            diskMatchLineNumbers.clear();
            currentDiskMatchIndex = -1;
            lastKeyword = "";
            rehighlightLogArea(null);
        }
    }

    /**
     * 跳转到当前磁盘搜索匹配的行。
     * 如果匹配行不在当前视图范围内，自动从磁盘加载对应的页面。
     */
    private void navigateToDiskMatch() {
        if (currentDiskMatchIndex < 0 || currentDiskMatchIndex >= diskMatchLineNumbers.size()) return;

        int targetLine = diskMatchLineNumbers.get(currentDiskMatchIndex);

        // 判断目标行是否在当前视图范围内
        if (targetLine >= viewStartLine && targetLine < viewEndLine) {
            // 在当前视图中，直接高亮跳转
            int localLine = targetLine - viewStartLine;
            highlightAndSelectLine(localLine, inlineSearchField.getText());
            updateMatchLabel();
        } else {
            // 不在当前视图，先加载对应页面，加载完成后跳转
            loadViewFromDisk(targetLine, () -> {
                int localLine = targetLine - viewStartLine;
                if (localLine >= 0 && localLine < logArea.getParagraphs().size()) {
                    highlightAndSelectLine(localLine, inlineSearchField.getText());
                }
                updateMatchLabel();
            });
        }
    }

    /**
     * 高亮并选中指定行中的搜索关键字。
     * @param localLine 段落索引（0-based）
     * @param keyword   搜索关键字
     */
    private void highlightAndSelectLine(int localLine, String keyword) {
        if (localLine < 0 || localLine >= logArea.getParagraphs().size()) return;

        // 使用 getParagraph(index).getText() 获取段落文本，而非 getText(from, to)（后者是字符位置）
        String lineText = logArea.getParagraph(localLine).getText();
        String lowerText = lineText.toLowerCase();
        String lowerKw = keyword.toLowerCase();

        int idx = lowerText.indexOf(lowerKw);
        if (idx >= 0) {
            int start = logArea.getAbsolutePosition(localLine, idx);
            int end = start + keyword.length();
            logArea.selectRange(start, end);
            logArea.requestFollowCaret();
        } else {
            // 行中没找到（可能延迟），仅移动光标到该行
            logArea.moveTo(localLine, 0);
            logArea.requestFollowCaret();
        }

        // 高亮当前视图中的所有匹配
        rehighlightLogArea(keyword);
    }

    /**
     * 在磁盘文件中搜索关键字（后台线程），完成后更新匹配列表和 UI。
     * @param keyword      搜索关键字
     * @param navigateToFirst 是否跳转到第一个匹配（用户主动搜索时为 true，刷新搜索时为 false）
     */
    private void searchDiskInBackground(String keyword, boolean navigateToFirst) {
        if (keyword == null || keyword.isBlank()) {
            diskMatchLineNumbers.clear();
            currentDiskMatchIndex = -1;
            lastKeyword = "";
            updateMatchLabel();
            rehighlightLogArea(null);
            return;
        }

        if (keyword.equals(lastKeyword)) return;

        String podName = AppConfig.getK8sQuery().getPodName();
        if (podName == null) return;

        // 记住当前匹配行号，刷新后尽量保持位置
        int currentMatchLine = (currentDiskMatchIndex >= 0 && currentDiskMatchIndex < diskMatchLineNumbers.size())
                ? diskMatchLineNumbers.get(currentDiskMatchIndex) : -1;

        ExecutorManager.submit(() -> {
            PodLogFileManager.DiskSearchResult result = fileManager.searchInLogFile(podName, keyword);

            Platform.runLater(() -> {
                diskMatchLineNumbers.clear();
                diskMatchLineNumbers.addAll(result.matchedLineNumbers);
                lastKeyword = keyword;

                if (navigateToFirst) {
                    currentDiskMatchIndex = diskMatchLineNumbers.isEmpty() ? -1 : 0;
                } else {
                    // 刷新搜索：尽量保持当前匹配位置
                    if (currentMatchLine >= 0 && !diskMatchLineNumbers.isEmpty()) {
                        // 找到离原匹配行号最近的索引
                        int bestIdx = 0;
                        int bestDist = Integer.MAX_VALUE;
                        for (int i = 0; i < diskMatchLineNumbers.size(); i++) {
                            int dist = Math.abs(diskMatchLineNumbers.get(i) - currentMatchLine);
                            if (dist < bestDist) {
                                bestDist = dist;
                                bestIdx = i;
                            }
                        }
                        currentDiskMatchIndex = bestIdx;
                    } else {
                        currentDiskMatchIndex = diskMatchLineNumbers.isEmpty() ? -1 : 0;
                    }
                }

                updateMatchLabel();

                // 高亮当前视图中可见的匹配
                rehighlightLogArea(keyword);

                // 仅用户主动搜索时跳转到匹配
                if (navigateToFirst && !diskMatchLineNumbers.isEmpty()) {
                    navigateToDiskMatch();
                }
            });
        });
    }

    /**
     * 在磁盘文件中搜索关键字（用户主动搜索，跳转到第一个匹配）。
     */
    private void searchDiskInBackground(String keyword) {
        searchDiskInBackground(keyword, true);
    }

    private void updateMatchLabel() {
        matchCountLabel.setText(diskMatchLineNumbers.isEmpty()
                ? "0/0"
                : (currentDiskMatchIndex + 1) + "/" + diskMatchLineNumbers.size());
    }

    private void rehighlightLogArea(String searchKeyword) {
        String text = logArea.getText();
        String logKw = AppConfig.getK8sQuery().getKeyword();
        String searchKw = (searchKeyword != null && !searchKeyword.isBlank()) ? searchKeyword : null;
        StyleSpans<Collection<String>> spans =
                LogStyleUtil.computeHighlighting(false, text, logKw, searchKw);
        logArea.setStyleSpans(0, spans);
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

        // 恢复时，把暂停期间积压的日志从磁盘加载回来
        if (searchRunning) {
            resumeAndCatchUp();
        }
    }

    /**
     * 恢复滚动时，把暂停期间积压在磁盘但未显示的日志补回到 UI。
     */
    private void resumeAndCatchUp() {
        String podName = AppConfig.getK8sQuery().getPodName();
        if (podName == null) return;

        int currentVisibleLines = logArea.getParagraphs().size();
        // diskEndLine 在暂停期间持续追踪磁盘进度
        // 需要补回 [当前视图末尾, diskEndLine) 之间的行
        int gapStart = viewStartLine + currentVisibleLines;
        int gapCount = diskEndLine - gapStart;

        if (gapCount <= 0) return;

        // 一次性加载积压的日志
        ExecutorManager.submit(() -> {
            List<String> lines = fileManager.readLogLines(podName, gapStart, gapCount);
            if (lines.isEmpty()) return;

            String logKeyword = AppConfig.getK8sQuery().getKeyword();

            Platform.runLater(() -> {
                LogStyleUtil.appendBatch(logArea, lines, logKeyword, null);
                trimLogArea();
                logArea.moveTo(logArea.getLength());
                logArea.requestFollowCaret();
            });
        });
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

    // ==================== 置顶、置底与打开日志文件 ====================

    /**
     * 置顶：暂停滚动，并跳转到磁盘文件第一行（第0行）。
     */
    @FXML
    public void scrollToTopClick(MouseEvent mouseEvent) {
        String podName = AppConfig.getK8sQuery().getPodName();
        if (podName == null) return;

        // 暂停滚动，防止新日志进来后自动跳到底部
        K8sQuery k8sQuery = AppConfig.getK8sQuery();
        if (k8sQuery.isSearchRunning()) {
            k8sQuery.setSearchRunning(false);
            searchToggleButton.setText("恢复");
        }

        // 从磁盘加载第一屏日志
        loadingHistory = true;
        ExecutorManager.submit(() -> {
            List<String> lines = fileManager.readLogLines(podName, 0, MAX_LOG_LINES);
            if (lines.isEmpty()) {
                Platform.runLater(() -> {
                    loadingHistory = false;
                    logArea.moveTo(0, 0);
                });
                return;
            }

            String logKeyword = k8sQuery.getKeyword();

            Platform.runLater(() -> {
                LogStyleUtil.clear(logArea);
                LogStyleUtil.appendBatch(logArea, lines, logKeyword, null);
                viewStartLine = 0;
                viewEndLine = lines.size();
                // diskEndLine 保持当前磁盘进度不变，不因置顶而重置

                refreshLineNumbers();

                loadingHistory = false;

                logArea.moveTo(0, 0);
                logArea.requestFollowCaret();
            });
        });
    }

    /**
     * 置底：跳转到磁盘文件最后一行，并恢复自动滚动。
     */
    @FXML
    public void scrollToBottomClick(MouseEvent mouseEvent) {
        String podName = AppConfig.getK8sQuery().getPodName();
        if (podName == null) return;

        // 恢复自动滚动（如果当前是暂停状态）
        K8sQuery k8sQuery = AppConfig.getK8sQuery();
        if (!k8sQuery.isSearchRunning()) {
            k8sQuery.setSearchRunning(true);
            searchToggleButton.setText("暂停");
        }

        // 从磁盘加载最后一屏日志，确保跳到文件末尾
        loadingHistory = true;
        ExecutorManager.submit(() -> {
            int totalLines = fileManager.getLineCount(podName);
            if (totalLines <= 0) {
                Platform.runLater(() -> {
                    loadingHistory = false;
                    logArea.moveTo(logArea.getLength());
                    logArea.requestFollowCaret();
                });
                return;
            }

            int startLine = Math.max(0, totalLines - MAX_LOG_LINES);
            List<String> lines = fileManager.readLogLines(podName, startLine, MAX_LOG_LINES);
            if (lines.isEmpty()) {
                Platform.runLater(() -> loadingHistory = false);
                return;
            }

            String logKeyword = k8sQuery.getKeyword();

            Platform.runLater(() -> {
                LogStyleUtil.clear(logArea);
                LogStyleUtil.appendBatch(logArea, lines, logKeyword, null);
                viewStartLine = startLine;
                viewEndLine = totalLines;
                diskEndLine = Math.max(diskEndLine, totalLines);

                refreshLineNumbers();

                loadingHistory = false;

                logArea.moveTo(logArea.getLength());
                logArea.requestFollowCaret();
            });
        });
    }

    /**
     * 打开日志文件：用系统关联程序打开当前 Pod 的磁盘日志文件。
     * 如果没有关联程序，弹出文件选择器让用户选择打开方式。
     */
    @FXML
    public void openLogFileClick(MouseEvent mouseEvent) {
        String podName = AppConfig.getK8sQuery().getPodName();
        if (podName == null) {
            showAlert("提示", "请先选择一个 Pod");
            return;
        }

        java.nio.file.Path logFile = fileManager.getLatestLogFile(podName);
        if (logFile == null || !Files.exists(logFile)) {
            showAlert("提示", "当前没有日志文件");
            return;
        }

        File file = logFile.toFile();
        Desktop desktop = Desktop.getDesktop();

        try {
            if (desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(file);
            } else {
                // 不支持直接打开，用文件选择器让用户选择程序
                openWithChooser(file);
            }
        } catch (IOException e) {
            log.warn("无法用默认程序打开日志文件: {}", e.getMessage());
            // 默认打开失败，让用户选择打开方式
            openWithChooser(file);
        }
    }

    /**
     * 弹出文件选择器，让用户选择用哪个程序打开文件。
     */
    private void openWithChooser(File file) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择程序打开日志文件");
        chooser.setInitialDirectory(file.getParentFile());
        chooser.setInitialFileName(file.getName());
        // 不设置扩展名过滤器，让用户选择任意可执行文件
        File selected = chooser.showOpenDialog(openLogFileButton.getScene().getWindow());
        if (selected == null) return;

        try {
            new ProcessBuilder(selected.getAbsolutePath(), file.getAbsolutePath()).start();
        } catch (IOException ex) {
            log.error("用所选程序打开日志文件失败: {}", ex.getMessage());
            showAlert("错误", "无法用所选程序打开日志文件: " + ex.getMessage());
        }
    }
}
