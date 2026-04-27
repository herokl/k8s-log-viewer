package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import com.longfor.lmk.k8slogviewer.config.AppPreferences;
import com.longfor.lmk.k8slogviewer.config.K8sClientManager;
import com.longfor.lmk.k8slogviewer.config.K8sQuery;
import com.longfor.lmk.k8slogviewer.service.ClusterTreeService;
import com.longfor.lmk.k8slogviewer.service.LogFetchService;
import com.longfor.lmk.k8slogviewer.service.PodLogFileManager;
import com.longfor.lmk.k8slogviewer.utils.CommonUtils;
import com.longfor.lmk.k8slogviewer.utils.ExecutorManager;
import com.longfor.lmk.k8slogviewer.utils.LogStyleUtil;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
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

import static com.longfor.lmk.k8slogviewer.utils.CommonUtils.showAlert;
import static com.longfor.lmk.k8slogviewer.utils.LogStyleUtil.SEPARATOR_LINE;

/**
 * 主界面控制器。
 * 职责：UI 事件绑定 + 委托 Service/Manager 层处理业务逻辑。
 * 线程管理统一由 {@link ExecutorManager} 负责。
 */
public class K8sLogViewerController {

    private static final Logger log = LoggerFactory.getLogger(K8sLogViewerController.class);

    /** 搜索高亮颜色数量，与 LogStyleUtil 保持一致 */
    private static final int SEARCH_HIGHLIGHT_COLORS = LogStyleUtil.SEARCH_HIGHLIGHT_COLORS;

    // ==================== FXML 组件 ====================

    @FXML public ProgressIndicator loadingIndicator;
    @FXML public VBox logAreaContainer;
    @FXML public CodeArea headerArea;
    @FXML private TreeView<String> treeView;
    @FXML private CodeArea logArea;
    @FXML private TextField searchField;
    @FXML private Button refreshButton;
    @FXML private Button settingsButton;
    @FXML private TextField tailField;
    @FXML private Button searchToggleButton;
    @FXML private Button scrollToTopButton;
    @FXML private Button scrollToBottomButton;
    @FXML private Button openLogFileButton;
    @FXML private ToggleButton wrapButton;
    @FXML private VBox treePane;
    @FXML private HBox treePaneWrapper;
    @FXML private Label collapseArrow;
    @FXML private VBox collapseBar;
    @FXML private SplitPane splitPane;

    @FXML private VBox searchBar;
    @FXML private TextField inlineSearchField;
    @FXML private Label matchCountLabel;
    @FXML private FlowPane tagContainer;
    @FXML private ToggleButton andOrToggle;

    // ==================== 服务与状态 ====================

    private final ClusterTreeService clusterTreeService = new ClusterTreeService();
    private final PodLogFileManager fileManager = new PodLogFileManager();
    private boolean isTreePaneVisible = true;
    private VirtualizedScrollPane<CodeArea> logScrollPane;

    // 内联搜索状态
    private String lastKeyword = "";
    /** true=且(所有关键字在同一行), false=或(任一关键字匹配) */
    private boolean searchAndMode = false;

    // 日志缓冲
    private final Queue<String> logQueue = new ArrayDeque<>();
    private final Object logQueueLock = new Object();

    // 日志行数上限，始终保持在 500 行以内，保证 UI 丝滑
    private static final int MAX_LOG_LINES = 500;

    // 搜索全量刷新间隔（基于时间，默认1000ms）
    private long lastSearchRefreshTime = 0;


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
        k8sQuery.setTailLines(0);
    }

    private void initCodeArea() {
        headerArea.setEditable(false);
        logArea.setEditable(false);

        logArea.setParagraphGraphicFactory(this::createLineNumberLabel);

        headerArea.setWrapText(true);
        logArea.setWrapText(true);

        // 换行按钮默认选中（当前是换行状态）
        wrapButton.setSelected(true);
        setWrapButtonIcon();

        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(logArea);
        logScrollPane = scrollPane;
        // 默认换行模式，隐藏水平滚动条
        logScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        logAreaContainer.getChildren().add(scrollPane);

        // 滚动到顶部时自动从磁盘加载更多历史日志
        scrollPane.estimatedScrollYProperty().addListener((obs, oldVal, newVal) -> {
            if (!loadingHistory && newVal != null && newVal <= 5.0) {
                loadHistoryFromDisk();
            }
            // 滚动到底部时自动从磁盘加载更多后续日志
            if (!loadingHistory && newVal != null) {
                double totalHeight = logArea.getTotalHeightEstimate();
                double viewportHeight = scrollPane.getHeight();
                double maxScroll = totalHeight - viewportHeight;
                if (maxScroll > 0 && (maxScroll - newVal) <= 30.0) {
                    loadForwardFromDisk();
                }
            }
        });
    }

    /**
     * 创建行号 Label，使用和 logArea 相同的字体以确保对齐。
     */
    private Node createLineNumberLabel(int line) {
        Label label = new Label(String.valueOf(viewStartLine + line + 1));
        label.setFont(javafx.scene.text.Font.font("JetBrains Mono", javafx.scene.text.FontWeight.NORMAL, 12));
        label.setTextFill(javafx.scene.paint.Color.web("#E0E0E0"));
        label.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setPadding(new javafx.geometry.Insets(0, 10, 0, 6));
        label.setStyle("-fx-background-color: #2D2D2D;");
        return label;
    }

    private void refreshLineNumbers() {
        logArea.setParagraphGraphicFactory(this::createLineNumberLabel);
    }

    private void initSearchBar() {
        // 搜索栏默认显示，不需要 setManaged(false)

        // 回车：输入框有内容则添加标签，无内容则跳转下一个匹配
        inlineSearchField.setOnAction(e -> {
            String text = inlineSearchField.getText().trim();
            if (text.isEmpty()) {
                findNext();
                return;
            }
            addSearchTag(text);
            inlineSearchField.clear();
            onSearchTagsChanged();
        });

        // 搜索框内上下方向键导航匹配
        inlineSearchField.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.DOWN) {
                findNext();
                event.consume();
            } else if (event.getCode() == KeyCode.UP) {
                findPrev();
                event.consume();
            }
        });

        // Ctrl+F / ESC 快捷键 — 注册在场景上（场景就绪后），更可靠
        Platform.runLater(() -> {
            if (logArea.getScene() != null) {
                logArea.getScene().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                    if (new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN).match(event)) {
                        // 显示搜索栏 + 聚焦输入框 + 将选中文字自动添加为标签
                        toggleSearchBar(true);
                        String selected = logArea.getSelectedText();
                        if (selected != null && !selected.isBlank()) {
                            // 取选中文字的第一行作为关键字
                            String keyword = selected.split("\n")[0].trim();
                            if (!keyword.isEmpty() && !isTagExists(keyword)) {
                                addSearchTag(keyword);
                                onSearchTagsChanged();
                            }
                        }
                        event.consume();
                    } else if (event.getCode() == KeyCode.ESCAPE && searchBar.isVisible()
                            && !tagContainer.getChildren().isEmpty()) {
                        toggleSearchBar(false);
                        event.consume();
                    } else if (event.getCode() == KeyCode.F3) {
                        if (searchBar.isVisible() && !buildSearchKeywordFromTags().isEmpty()) {
                            if (event.isShiftDown()) {
                                findPrev();
                            } else {
                                findNext();
                            }
                            event.consume();
                        }
                    }
                });
            }
        });
    }

    /**
     * 检查关键字标签是否已存在。
     */
    private boolean isTagExists(String keyword) {
        for (var node : tagContainer.getChildren()) {
            String kw = (String) ((HBox) node).getUserData();
            if (keyword.equals(kw)) return true;
        }
        return false;
    }

    /**
     * 添加一个搜索关键字标签。
     */
    private void addSearchTag(String keyword) {
        int index = tagContainer.getChildren().size();
        String colorClass = "search-tag-" + (index % SEARCH_HIGHLIGHT_COLORS);

        // 截断显示：最多显示8个字符 + "..."
        String display = keyword.length() > 8 ? keyword.substring(0, 8) + "..." : keyword;

        Label tagLabel = new Label(display);
        tagLabel.getStyleClass().addAll("search-tag", colorClass);
        tagLabel.setTooltip(new Tooltip(keyword));

        Button removeBtn = new Button("×");
        removeBtn.getStyleClass().add("search-tag-remove");
        removeBtn.setOnAction(e -> {
            tagContainer.getChildren().remove(tagLabel.getParent());
            refreshTagColors();
            onSearchTagsChanged();
        });

        HBox tagBox = new HBox(2, tagLabel, removeBtn);
        tagBox.getStyleClass().add("search-tag-box");
        tagBox.setUserData(keyword);

        tagContainer.getChildren().add(tagBox);
    }

    /**
     * 删除标签后刷新颜色索引。
     */
    private void refreshTagColors() {
        for (int i = 0; i < tagContainer.getChildren().size(); i++) {
            HBox tagBox = (HBox) tagContainer.getChildren().get(i);
            Label tagLabel = (Label) tagBox.getChildren().get(0);
            tagLabel.getStyleClass().removeIf(s -> s.startsWith("search-tag-") && !s.equals("search-tag-box"));
            tagLabel.getStyleClass().add("search-tag-" + (i % SEARCH_HIGHLIGHT_COLORS));
        }
    }

    /**
     * 从标签容器构建搜索关键字字符串，使用 \u0000 分隔各关键字（支持关键字本身含空格）。
     */
    private String buildSearchKeywordFromTags() {
        if (tagContainer.getChildren().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var node : tagContainer.getChildren()) {
            String kw = (String) ((HBox) node).getUserData();
            sb.append(kw).append('\0');
        }
        return sb.toString().trim();
    }

    /**
     * 标签变化时触发搜索。
     */
    private void onSearchTagsChanged() {
        String keyword = buildSearchKeywordFromTags();
        searchDiskInBackground(keyword);
    }

    /** 定时刷新搜索（每500ms由 flushLogsToUI 触发），强制全量搜索保证匹配列表完整 */
    private void refreshSearchIfNeeded() {
        String searchKw = (searchBar != null && searchBar.isVisible()) ? buildSearchKeywordFromTags() : null;
        if (searchKw != null && !searchKw.isBlank()) {
            searchDiskInBackground(searchKw, false, true);
        }
    }

    /** 搜索栏 + 按钮：添加当前输入为标签 */
    @FXML
    private void onSearchAdd() {
        String text = inlineSearchField.getText().trim();
        if (text.isEmpty()) return;
        addSearchTag(text);
        inlineSearchField.clear();
        onSearchTagsChanged();
    }

    /** 且/或切换 */
    @FXML
    private void onAndOrToggle() {
        searchAndMode = andOrToggle.isSelected();
        andOrToggle.setText(searchAndMode ? "且" : "或");
        // 重新搜索
        lastKeyword = "";
        onSearchTagsChanged();
    }

    private void initFieldListeners() {
        K8sQuery k8sQuery = AppConfig.getK8sQuery();

        tailField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.isEmpty()) {
                k8sQuery.setTailLines(0);
                return;
            }
            if (!newVal.matches("\\d+") || Integer.parseInt(newVal) < 0) {
                tailField.setText(oldVal);
            } else {
                k8sQuery.setTailLines(Integer.parseInt(newVal));
            }
        });

        tailField.setOnAction(e -> {
            String podName = k8sQuery.getPodName();
            if (podName != null && !podName.isEmpty()) {
                showLogs();
            }
        });

        searchField.textProperty().addListener((obs, oldVal, newVal) -> refreshTree(newVal));
    }

    private void initTreeView() {
        treeView.setOnMouseClicked(this::handleTreeViewClick);

        // 右键上下文菜单
        ContextMenu podContextMenu = new ContextMenu();
        MenuItem deletePodItem = new MenuItem("删除 Pod");
        deletePodItem.setOnAction(e -> onDeletePod());
        MenuItem monitorItem = new MenuItem("性能监控");
        monitorItem.setOnAction(e -> onPodMonitor());
        podContextMenu.getItems().addAll(deletePodItem, monitorItem);

        treeView.setContextMenu(podContextMenu);
        // 仅在 Pod 节点上显示右键菜单
        podContextMenu.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (isShowing) {
                TreeItem<String> selected = treeView.getSelectionModel().getSelectedItem();
                boolean isPod = selected != null && selected.getParent() != null
                        && selected.getParent().getParent() != null;
                deletePodItem.setVisible(isPod);
                monitorItem.setVisible(isPod);
            }
        });

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
            // 截断后重新搜索以更新匹配列表
            String kw = buildSearchKeywordFromTags();
            if (!kw.isBlank()) {
                searchDiskInBackground(kw, false);
            }
        }));

        treeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getParent() != null) {
                TreeItem<String> parent = newVal.getParent();
                if (parent.getParent() != null) {
                    String namespace = parent.getValue();
                    String podName = newVal.getValue();
                    // 避免重复选择同一个 Pod 触发多次请求
                    K8sQuery k8sQuery = AppConfig.getK8sQuery();
                    if (podName.equals(k8sQuery.getPodName()) && namespace.equals(k8sQuery.getNamespace())) {
                        return;
                    }
                    log.info("选择命名空间: {}, Pod: {}", namespace, podName);

                    k8sQuery.setNamespace(namespace);
                    k8sQuery.setPodName(podName);

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

    /**
     * 右键删除选中的 Pod。
     */
    private void onDeletePod() {
        TreeItem<String> selected = treeView.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getParent() == null || selected.getParent().getParent() == null) return;

        String podName = selected.getValue();
        String namespace = selected.getParent().getValue();

        CommonUtils.showConfirm("删除确认",
                "确定要删除 Pod \"" + podName + "\"（命名空间: " + namespace + "）吗？",
                () -> {
                    ExecutorManager.submit(() -> {
                        try {
                            CoreV1Api api = K8sClientManager.getCoreV1Api();
                            api.deleteNamespacedPod(
                                    podName, namespace,
                                    null, null, null, null, null, null
                            );
                            log.info("已删除 Pod: {}/{}", namespace, podName);
                            Platform.runLater(() -> {
                                clusterTreeService.clearCache();
                                refreshTree(searchField.getText());
                                showAlert("提示", "Pod " + podName + " 已删除");
                            });
                        } catch (Exception ex) {
                            log.error("删除 Pod 失败: {}", ex.getMessage());
                            Platform.runLater(() -> showAlert("错误", "删除 Pod 失败: " + ex.getMessage()));
                        }
                    });
                }, null);
    }

    /**
     * 右键查看选中 Pod 的性能监控（CPU/内存）。
     */
    private void onPodMonitor() {
        TreeItem<String> selected = treeView.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getParent() == null || selected.getParent().getParent() == null) return;

        String podName = selected.getValue();
        String namespace = selected.getParent().getValue();

        PodMonitorDialog.show(namespace, podName);
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

    private void showLogs() {
        K8sQuery query = AppConfig.getK8sQuery();
        query.resetRuntimeState();
        query.setSearchRunning(true);
        searchToggleButton.setText("暂停");
        viewStartLine = 0;
        viewEndLine = 0;
        diskEndLine = 0;
        lastSearchRefreshTime = 0;
        loadingHistory = false;

        // 清空旧日志缓冲，避免切换 Pod 时残留旧数据
        synchronized (logQueueLock) {
            logQueue.clear();
        }

        // 重建日志文件，清空磁盘上的旧日志
        try {
            fileManager.switchPod(query.getPodName());
        } catch (IOException e) {
            log.warn("重建日志文件失败: {}", e.getMessage());
        }

        Platform.runLater(() -> {
            LogStyleUtil.clear(logArea);
            LogStyleUtil.clear(headerArea);
            refreshLineNumbers();
        });

        ExecutorManager.stopLogFlushExecutor();
        LogFetchService.cancelCurrentCall();
        ScheduledExecutorService flushExecutor = ExecutorManager.restartLogFlushExecutor();
        int flushIntervalMs = AppPreferences.getLogFlushIntervalMs();
        flushExecutor.scheduleAtFixedRate(
                this::flushLogsToUI, 0, flushIntervalMs, TimeUnit.MILLISECONDS
        );

        ExecutorManager.submit(() -> {
            try {
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
        });
    }

    private void flushLogsToUI() {
        List<String> batch = new ArrayList<>();
        synchronized (logQueueLock) {
            while (!logQueue.isEmpty()) {
                batch.add(logQueue.poll());
            }
        }
        if (!batch.isEmpty()) {
            Platform.runLater(() -> processLogBatch(batch));
        }

        // 按配置的时间间隔全量搜索一次，保证匹配列表完整（无论是否有新日志）
        long now = System.currentTimeMillis();
        int searchRefreshMs = AppPreferences.getSearchRefreshIntervalMs();
        if (now - lastSearchRefreshTime >= searchRefreshMs) {
            lastSearchRefreshTime = now;
            Platform.runLater(this::refreshSearchIfNeeded);
        }
    }

    private void processLogBatch(List<String> lines) {
        K8sQuery k8sQuery = AppConfig.getK8sQuery();
        boolean searchRunning = k8sQuery.isSearchRunning();

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
            LogStyleUtil.appendHeaderLine(headerArea, line);
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
        LogStyleUtil.appendBatch(logArea, logLines, null);
        viewEndLine += logLines.size();
        diskEndLine += logLines.size();

        // 内联搜索关键字高亮与增量匹配更新
        String searchKw = (searchBar != null && searchBar.isVisible()) ? buildSearchKeywordFromTags() : null;
        if (searchKw != null && !searchKw.isBlank()) {
            rehighlightLogArea(searchKw);
            incrementalSearchUpdate(logLines, searchKw);
            applyCurrentMatchHighlight();
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

        int oldViewStart = viewStartLine;
        viewStartLine += removeCount;

        // 裁剪后同步清理已超出视图范围的匹配行号
        trimDiskMatches(oldViewStart, removeCount);

        refreshLineNumbers();
    }

    /**
     * 裁剪日志后同步更新磁盘搜索匹配列表。
     * 移除被裁剪行的匹配，修正当前匹配索引。
     *
     * @param removedStart  被裁剪区域起始行号（0-based）
     * @param removedCount  被裁剪的行数
     */
    private void trimDiskMatches(int removedStart, int removedCount) {
        if (diskMatchLineNumbers.isEmpty()) return;

        int removedEnd = removedStart + removedCount;

        // 记住当前匹配的行号，用于裁剪后重新定位索引
        int currentLine = (currentDiskMatchIndex >= 0 && currentDiskMatchIndex < diskMatchLineNumbers.size())
                ? diskMatchLineNumbers.get(currentDiskMatchIndex) : -1;

        // 移除被裁剪范围内的匹配行号
        diskMatchLineNumbers.removeIf(line -> line < removedEnd);

        if (diskMatchLineNumbers.isEmpty()) {
            currentDiskMatchIndex = -1;
        } else if (currentLine >= 0 && currentLine < removedEnd) {
            // 当前匹配被裁剪掉了，跳到最近的下一个
            currentDiskMatchIndex = 0;
        } else if (currentLine >= 0) {
            // 当前匹配仍存在，重新定位索引
            int idx = diskMatchLineNumbers.indexOf(currentLine);
            currentDiskMatchIndex = Math.max(idx, 0);
        } else {
            currentDiskMatchIndex = 0;
        }

        updateMatchLabel();
        applyCurrentMatchHighlight();
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

            Platform.runLater(() -> {
                double scrollX = logScrollPane.getEstimatedScrollX();
                double scrollY = logScrollPane.getEstimatedScrollY();

                LogStyleUtil.prependBatch(logArea, historyLines, null);
                viewStartLine -= historyLines.size();

                refreshLineNumbers();

                logScrollPane.estimatedScrollXProperty().setValue(scrollX);
                logScrollPane.estimatedScrollYProperty().setValue(scrollY);

                loadingHistory = false;

                // 历史日志加载后重新高亮搜索关键字
                String searchKw = (searchBar != null && searchBar.isVisible()) ? buildSearchKeywordFromTags() : null;
                if (searchKw != null && !searchKw.isBlank()) {
                    rehighlightLogArea(searchKw);
                }
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

            Platform.runLater(() -> {
                double scrollX = logScrollPane.getEstimatedScrollX();
                double scrollY = logScrollPane.getEstimatedScrollY();

                LogStyleUtil.appendBatch(logArea, forwardLines, null);
                viewEndLine += forwardLines.size();

                // 裁剪顶部旧行
                trimLogArea();

                refreshLineNumbers();

                logScrollPane.estimatedScrollXProperty().setValue(scrollX);
                logScrollPane.estimatedScrollYProperty().setValue(scrollY);

                loadingHistory = false;

                // 向后加载后重新高亮搜索关键字
                String searchKw = (searchBar != null && searchBar.isVisible()) ? buildSearchKeywordFromTags() : null;
                if (searchKw != null && !searchKw.isBlank()) {
                    rehighlightLogArea(searchKw);
                }
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

            Platform.runLater(() -> {
                LogStyleUtil.clear(logArea);
                LogStyleUtil.appendBatch(logArea, lines, null);
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
        String keyword = buildSearchKeywordFromTags();
        if (keyword == null || keyword.isEmpty()) return;
        if (diskMatchLineNumbers.isEmpty()) return;

        currentDiskMatchIndex = (currentDiskMatchIndex + 1) % diskMatchLineNumbers.size();
        navigateToDiskMatch();
    }

    @FXML
    private void findPrev() {
        String keyword = buildSearchKeywordFromTags();
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
        if (show) {
            inlineSearchField.requestFocus();
            searchDiskInBackground(buildSearchKeywordFromTags());
        } else {
            // ESC / ✕：清空搜索状态，搜索栏保持可见
            inlineSearchField.clear();
            tagContainer.getChildren().clear();
            diskMatchLineNumbers.clear();
            currentDiskMatchIndex = -1;
            lastKeyword = "";
            // 截断后重新搜索以更新匹配列表
            String kw = buildSearchKeywordFromTags();
            if (kw != null && !kw.isBlank()) {
                searchDiskInBackground(kw, false);
            }
            rehighlightLogArea(null);
            updateMatchLabel();
        }
    }

    /**
     * 跳转到当前磁盘搜索匹配的行。
     * 如果匹配行不在当前视图范围内，自动从磁盘加载对应的页面。
     */
    private void navigateToDiskMatch() {
        if (currentDiskMatchIndex < 0 || currentDiskMatchIndex >= diskMatchLineNumbers.size()) return;

        int targetLine = diskMatchLineNumbers.get(currentDiskMatchIndex);
        String keyword = buildSearchKeywordFromTags();

        // 判断目标行是否在当前视图范围内
        if (targetLine >= viewStartLine && targetLine < viewEndLine) {
            // 在当前视图中，直接高亮跳转
            int localLine = targetLine - viewStartLine;
            highlightAndSelectLine(localLine, keyword);
            updateMatchLabel();
        } else {
            // 不在当前视图，先加载对应页面，加载完成后跳转
            loadViewFromDisk(targetLine, () -> {
                int localLine = targetLine - viewStartLine;
                if (localLine >= 0 && localLine < logArea.getParagraphs().size()) {
                    highlightAndSelectLine(localLine, keyword);
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

        String lineText = logArea.getParagraph(localLine).getText();
        String lowerText = lineText.toLowerCase();

        // 先高亮所有匹配
        rehighlightLogArea(keyword);

        // 当前匹配使用橙色高亮：找到行中第一个匹配的关键字
        List<String> keywords = CommonUtils.parseSearchKeywords(keyword);
        int bestIdx = -1;
        String matchedKw = null;
        for (String kw : keywords) {
            int idx = lowerText.indexOf(kw.toLowerCase());
            if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
                bestIdx = idx;
                matchedKw = kw;
            }
        }

        if (bestIdx >= 0) {
            int start = logArea.getAbsolutePosition(localLine, bestIdx);
            Set<String> currentStyles = Set.of("plain-text", "search-highlight-current");
            StyleSpansBuilder<Collection<String>> ssb = new StyleSpansBuilder<>();
            ssb.add(currentStyles, matchedKw.length());
            logArea.setStyleSpans(start, ssb.create());
            logArea.selectRange(start, start + matchedKw.length());
        } else {
            logArea.moveTo(localLine, 0);
        }
        logArea.requestFollowCaret();

        // 基于光标实际像素位置精确计算居中偏移
        Platform.runLater(() -> {
            logArea.getCaretBounds().ifPresent(bounds -> {
                Bounds caretSceneBounds = logArea.localToScene(bounds);
                double caretCenterY = caretSceneBounds.getCenterY();
                double viewportCenterY = logScrollPane.localToScene(0, logScrollPane.getHeight() / 2).getY();
                double delta = caretCenterY - viewportCenterY;
                logScrollPane.scrollYBy(delta);
            });
        });
    }

    /**
     * 在磁盘文件中搜索关键字（后台线程），完成后更新匹配列表和 UI。
     * @param keyword      搜索关键字
     * @param navigateToFirst 是否跳转到第一个匹配（用户主动搜索时为 true，刷新搜索时为 false）
     */
    private void searchDiskInBackground(String keyword, boolean navigateToFirst) {
        searchDiskInBackground(keyword, navigateToFirst, false);
    }

    /**
     * 在磁盘文件中搜索关键字（后台线程），完成后更新匹配列表和 UI。
     * @param keyword      搜索关键字
     * @param navigateToFirst 是否跳转到第一个匹配（用户主动搜索时为 true，刷新搜索时为 false）
     * @param force        强制搜索，即使关键字未变化也执行（定时刷新时为 true）
     */
    private void searchDiskInBackground(String keyword, boolean navigateToFirst, boolean force) {
        if (keyword == null || keyword.isBlank()) {
            diskMatchLineNumbers.clear();
            currentDiskMatchIndex = -1;
            lastKeyword = "";
            updateMatchLabel();
            rehighlightLogArea(null);
            return;
        }

        if (!force && keyword.equals(lastKeyword)) return;

        String podName = AppConfig.getK8sQuery().getPodName();
        if (podName == null) return;

        // 记住当前匹配行号，刷新后尽量保持位置
        int currentMatchLine = (currentDiskMatchIndex >= 0 && currentDiskMatchIndex < diskMatchLineNumbers.size())
                ? diskMatchLineNumbers.get(currentDiskMatchIndex) : -1;

        ExecutorManager.submit(() -> {
            PodLogFileManager.DiskSearchResult result = fileManager.searchInLogFile(podName, keyword, searchAndMode);

            Platform.runLater(() -> {
                // 防御：全量搜索返回0但之前有匹配，可能是并发读到了截断中的空文件，保留现有匹配
                if (result.matchedLineNumbers.isEmpty() && !diskMatchLineNumbers.isEmpty()) {
                    return;
                }

                diskMatchLineNumbers.clear();
                diskMatchLineNumbers.addAll(result.matchedLineNumbers);
                lastKeyword = keyword;

                if (navigateToFirst) {
                    currentDiskMatchIndex = diskMatchLineNumbers.isEmpty() ? -1 : 0;
                } else if (currentMatchLine >= 0 && !diskMatchLineNumbers.isEmpty()) {
                    int idx = diskMatchLineNumbers.indexOf(currentMatchLine);
                    currentDiskMatchIndex = idx >= 0 ? idx : Math.min(currentDiskMatchIndex, diskMatchLineNumbers.size() - 1);
                } else if (!diskMatchLineNumbers.isEmpty()) {
                    currentDiskMatchIndex = 0;
                } else {
                    currentDiskMatchIndex = -1;
                }

                updateMatchLabel();
                rehighlightLogArea(keyword);

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
        if (diskMatchLineNumbers.isEmpty()) {
            matchCountLabel.setText("0/0");
        } else {
            matchCountLabel.setText(formatCompact(currentDiskMatchIndex + 1) + "/" + formatCompact(diskMatchLineNumbers.size()));
        }
    }

    /** 千分符格式化数字：1,234 / 1,234,567 */
    private static String formatCompact(int n) {
        return String.format("%,d", n);
    }

    /**
     * 为当前导航到的匹配项应用橙色高亮样式。
     * 在 rehighlightLogArea 之后调用，覆盖当前匹配的黄色为橙色。
     */
    private void applyCurrentMatchHighlight() {
        if (currentDiskMatchIndex < 0 || currentDiskMatchIndex >= diskMatchLineNumbers.size()) return;
        String keyword = buildSearchKeywordFromTags();
        if (keyword.isBlank()) return;

        int targetLine = diskMatchLineNumbers.get(currentDiskMatchIndex);
        int localLine = targetLine - viewStartLine;
        if (localLine < 0 || localLine >= logArea.getParagraphs().size()) return;

        String lineText = logArea.getParagraph(localLine).getText();
        String lowerText = lineText.toLowerCase();

        // 找到行中第一个匹配的关键字
        List<String> keywords = CommonUtils.parseSearchKeywords(keyword);
        int bestIdx = -1;
        String matchedKw = null;
        for (String kw : keywords) {
            int idx = lowerText.indexOf(kw.toLowerCase());
            if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
                bestIdx = idx;
                matchedKw = kw;
            }
        }

        if (bestIdx >= 0) {
            int start = logArea.getAbsolutePosition(localLine, bestIdx);
            Set<String> currentStyles = Set.of("plain-text", "search-highlight-current");
            StyleSpansBuilder<Collection<String>> ssb = new StyleSpansBuilder<>();
            ssb.add(currentStyles, matchedKw.length());
            logArea.setStyleSpans(start, ssb.create());
        }
    }

    /**
     * 增量搜索更新：扫描新追加的日志行，将匹配行号追加到磁盘匹配列表。
     * 避免全量磁盘搜索，提升实时搜索响应速度。
     */
    private void incrementalSearchUpdate(List<String> newLines, String keyword) {
        if (newLines == null || newLines.isEmpty()) return;
        List<String> keywords = CommonUtils.parseSearchKeywords(keyword);
        if (keywords.isEmpty()) return;

        int startDiskLine = diskEndLine - newLines.size();

        for (int i = 0; i < newLines.size(); i++) {
            String line = newLines.get(i);
            boolean matches;
            if (searchAndMode) {
                matches = keywords.stream().allMatch(kw -> line.toLowerCase().contains(kw.toLowerCase()));
            } else {
                matches = keywords.stream().anyMatch(kw -> line.toLowerCase().contains(kw.toLowerCase()));
            }
            if (matches) {
                diskMatchLineNumbers.add(startDiskLine + i);
            }
        }

        updateMatchLabel();
    }

    private void rehighlightLogArea(String searchKeyword) {
        String searchKw = (searchKeyword != null && !searchKeyword.isBlank()) ? searchKeyword : null;
        if (searchKw == null || !searchAndMode) {
            // 或模式或无关键字：对整个文本一次性计算
            String text = logArea.getText();
            StyleSpans<Collection<String>> spans =
                    LogStyleUtil.computeHighlighting(false, text, searchKw, false);
            logArea.setStyleSpans(0, spans);
        } else {
            // 且模式：按行计算，只有包含所有关键字的行才高亮
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < logArea.getParagraphs().size(); i++) {
                lines.add(logArea.getText(i, 0, i, logArea.getParagraphLength(i)));
            }
            StyleSpans<Collection<String>> spans =
                    LogStyleUtil.computeBatchHighlighting(lines, searchKw, true);
            logArea.setStyleSpans(0, spans);
        }
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

    /** 换行切换 */
    @FXML
    private void onWrapToggle() {
        boolean wrap = wrapButton.isSelected();
        logScrollPane.estimatedScrollXProperty().setValue((double) 0);
        logArea.setWrapText(wrap);
        if (wrap) {
            logScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        } else {
            logScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        }
        Platform.runLater(() -> {
            logScrollPane.requestLayout();
            logArea.requestFollowCaret();
        });
    }

    /** 为换行按钮设置 SVG 图标，图标填充色跟随按钮文字色 */
    private void setWrapButtonIcon() {
        SVGPath icon = new SVGPath();
        icon.setContent("M0 5a.75.75 0 0 1 .75-.75h11.5a3.75 3.75 0 1 1 0 7.5H9.87l.97.97a.75.75 0 1 1-1.06 1.06l-2.25-2.25L7 11l.53-.53l2.25-2.25a.75.75 0 1 1 1.06 1.06l-.97.97h2.38a2.25 2.25 0 0 0 0-4.5H.75A.75.75 0 0 1 0 5m6 6a.75.75 0 0 0-.75-.75H.75a.75.75 0 0 0 0 1.5h4.5A.75.75 0 0 0 6 11");
        icon.setScaleX(1.1);
        icon.setScaleY(1.1);
        icon.fillProperty().bind(wrapButton.textFillProperty());
        wrapButton.setGraphic(icon);
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

            Platform.runLater(() -> {
                LogStyleUtil.appendBatch(logArea, lines, null);
                trimLogArea();
                logArea.moveTo(logArea.getLength());
                logArea.requestFollowCaret();

                // 恢复后重新高亮搜索关键字并更新匹配列表
                String searchKw = (searchBar != null && searchBar.isVisible()) ? buildSearchKeywordFromTags() : null;
                if (searchKw != null && !searchKw.isBlank()) {
                    rehighlightLogArea(searchKw);
                    searchDiskInBackground(searchKw, false);
                }
            });
        });
    }

    @FXML
    private void toggleTreePane() {
        if (isTreePaneVisible) {
            treePane.setVisible(false);
            treePane.setManaged(false);
            collapseArrow.setText("▸");
            // 折叠后收缩左侧面板，让 collapseBar 紧贴分割线
            Platform.runLater(() -> splitPane.setDividerPositions(0.0));
            isTreePaneVisible = false;
        } else {
            treePane.setVisible(true);
            treePane.setManaged(true);
            collapseArrow.setText("◂");
            splitPane.setDividerPositions(0.3);
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

            Platform.runLater(() -> {
                LogStyleUtil.clear(logArea);
                LogStyleUtil.appendBatch(logArea, lines, null);
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

            Platform.runLater(() -> {
                LogStyleUtil.clear(logArea);
                LogStyleUtil.appendBatch(logArea, lines, null);
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
