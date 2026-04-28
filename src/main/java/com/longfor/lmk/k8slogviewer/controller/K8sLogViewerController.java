package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import com.longfor.lmk.k8slogviewer.config.AppPreferences;
import com.longfor.lmk.k8slogviewer.config.K8sQuery;
import com.longfor.lmk.k8slogviewer.service.ClusterTreeService;
import com.longfor.lmk.k8slogviewer.service.LogFetchService;
import com.longfor.lmk.k8slogviewer.service.PodLogFileManager;
import com.longfor.lmk.k8slogviewer.utils.ExecutorManager;
import com.longfor.lmk.k8slogviewer.utils.LogStyleUtil;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.longfor.lmk.k8slogviewer.utils.CommonUtils.showAlert;

/**
 * 主界面控制器。
 * 职责：FXML 事件绑定 + 委托 Manager 层处理业务逻辑。
 * <ul>
 *   <li>{@link LogStreamManager} — 日志缓冲、刷新、历史加载</li>
 *   <li>{@link DiskSearchEngine} — 磁盘搜索、导航、高亮</li>
 *   <li>{@link TreeViewManager} — 树过滤、状态筛选</li>
 * </ul>
 */
public class K8sLogViewerController {

    private static final Logger log = LoggerFactory.getLogger(K8sLogViewerController.class);

    // ==================== FXML 组件 ====================

    @FXML public ProgressIndicator loadingIndicator;
    @FXML public VBox logAreaContainer;
    @FXML public CodeArea headerArea;
    @FXML private TreeView<String> treeView;
    @FXML private CodeArea logArea;
    @FXML private TextField searchField;
    @FXML private Button refreshButton;
    @FXML private Button expandAllButton;
    @FXML private Button collapseAllButton;
    @FXML private Button settingsButton;
    @FXML private TextField tailField;
    @FXML private Button searchToggleButton;
    @FXML private Button scrollToTopButton;
    @FXML private Button scrollToBottomButton;
    @FXML private Button openLogFileButton;
    @FXML private ToggleButton wrapButton;
    @FXML private VBox treePane;
    @FXML private HBox treePaneWrapper;
    @FXML private ComboBox<String> statusFilterCombo;

    @FXML private Button collapseTab;
    @FXML private SplitPane splitPane;

    @FXML private VBox searchBar;
    @FXML private TextField inlineSearchField;
    @FXML private Label matchCountLabel;
    @FXML private FlowPane tagContainer;
    @FXML private ToggleButton andOrToggle;

    // ==================== 服务与管理器 ====================

    private final ClusterTreeService clusterTreeService = new ClusterTreeService();
    private final PodLogFileManager fileManager = new PodLogFileManager();

    private LogStreamManager logStreamManager;
    private DiskSearchEngine diskSearchEngine;
    private TreeViewManager treeViewManager;

    // ==================== 控制器自有状态 ====================

    private boolean isTreePaneVisible = true;
    private VirtualizedScrollPane<CodeArea> logScrollPane;

    /** 默认分割线位置，与 FXML 中 dividerPositions 一致 */
    private static final double DEFAULT_DIVIDER_POSITION = 0.18;

    /** 日志流代际计数器，切换 Pod 时递增以使旧的重连循环失效 */
    private volatile int logStreamGeneration = 0;

    /** 最大自动重连次数 */
    private static final int MAX_RECONNECT_ATTEMPTS = 3;

    /** 搜索高亮颜色数量，与 LogStyleUtil 保持一致 */
    private static final int SEARCH_HIGHLIGHT_COLORS = LogStyleUtil.SEARCH_HIGHLIGHT_COLORS;

    // ==================== 初始化 ====================

    @FXML
    public void initialize() throws IOException {
        log.info("K8s 日志查看器初始化...");

        // 创建管理器
        logStreamManager = new LogStreamManager(logArea, headerArea, fileManager);
        diskSearchEngine = new DiskSearchEngine(logArea, fileManager, matchCountLabel);
        treeViewManager = new TreeViewManager(treeView, statusFilterCombo, searchField, clusterTreeService);

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

        // 初始化 CodeArea（返回 VirtualizedScrollPane，加入布局）
        VirtualizedScrollPane<CodeArea> scrollPane = logStreamManager.initCodeArea();
        logScrollPane = scrollPane;
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        logAreaContainer.getChildren().add(scrollPane);

        // 换行按钮默认选中
        wrapButton.setSelected(true);
        setWrapButtonIcon();

        // 初始化搜索栏
        initSearchBar();

        // 初始化 tailField 监听
        initFieldListeners();

        // 注入搜索引擎的视图状态依赖
        diskSearchEngine.setViewStateSuppliers(
                logStreamManager::getViewStartLine,
                logStreamManager::getViewEndLine
        );
        diskSearchEngine.setViewLoader(logStreamManager::loadViewFromDisk);
        diskSearchEngine.setLogScrollPaneSupplier(() -> logScrollPane);

        // 裁剪回调：通知搜索引擎同步调整匹配行号
        logStreamManager.setOnTrimmed(diskSearchEngine::trimDiskMatches);

        // 文件截断回调
        fileManager.setOnFileTruncated(removedLines -> Platform.runLater(() -> {
            logStreamManager.adjustViewLinesOnTruncate(removedLines);
            diskSearchEngine.clearOnFileTruncate();
            String kw = buildSearchKeywordFromTags();
            if (!kw.isBlank()) {
                diskSearchEngine.searchDiskInBackground(kw, false);
            }
        }));

        // 初始化树视图
        treeViewManager.init();

        // 初始化折叠标签位置
        setupCollapseTab();

        // 绑定树选择事件
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getParent() != null) {
                TreeItem<String> parent = newVal.getParent();
                if (parent.getParent() != null) {
                    String namespace = parent.getValue();
                    String podName = newVal.getValue();
                    K8sQuery k8sQuery = AppConfig.getK8sQuery();
                    if (podName.equals(k8sQuery.getPodName()) && namespace.equals(k8sQuery.getNamespace())) {
                        return;
                    }
                    log.info("选择命名空间: {}, Pod: {}", namespace, podName);
                    k8sQuery.setNamespace(namespace);
                    k8sQuery.setPodName(podName);

                    // 标记该 Pod 及其上级为手动展开，搜索清空时不会折叠
                    treeViewManager.markExpandedToRoot(newVal);
                    treeViewManager.setActivePodPath(TreeViewManager.getTreeItemPath(newVal));

                    try {
                        fileManager.switchPod(podName);
                    } catch (IOException e) {
                        log.warn("日志写入失败: {}", e.getMessage());
                    }
                    showLogs();
                }
            }
        });
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
    }

    // ==================== 日志获取与刷新 ====================

    private void showLogs() {
        K8sQuery query = AppConfig.getK8sQuery();
        query.resetRuntimeState();
        query.setSearchRunning(true);
        searchToggleButton.setText("暂停");

        // 递增代际，使旧 Pod 的重连循环失效
        logStreamGeneration++;
        int generation = logStreamGeneration;

        logStreamManager.resetForNewPod();

        try {
            fileManager.switchPod(query.getPodName());
        } catch (IOException e) {
            log.warn("重建日志文件失败: {}", e.getMessage());
        }

        logStreamManager.clearAreas();

        ExecutorManager.stopLogFlushExecutor();
        LogFetchService.cancelCurrentCall();
        ScheduledExecutorService flushExecutor = ExecutorManager.restartLogFlushExecutor();
        int flushIntervalMs = AppPreferences.getLogFlushIntervalMs();
        flushExecutor.scheduleAtFixedRate(
                this::flushLogsToUI, 0, flushIntervalMs, TimeUnit.MILLISECONDS
        );

        ExecutorManager.submit(() -> {
            try {
                LogFetchService.fetchStreaming(logStreamManager::enqueueLine);
                // 流正常结束 — 仅当代际匹配时才处理（否则是切换容器导致的取消）
                if (generation != logStreamGeneration) {
                    log.info("日志流因切换容器而取消，静默退出");
                    return;
                }
                if (query.isSearchRunning() && query.getPodName() != null) {
                    log.info("日志流断开，容器仍在运行，自动重连...");
                    Platform.runLater(() -> {
                        PauseTransition delay = new PauseTransition(Duration.seconds(1));
                        delay.setOnFinished(e -> {
                            if (generation == logStreamGeneration) {
                                reconnectLogStream(generation, MAX_RECONNECT_ATTEMPTS);
                            }
                        });
                        delay.play();
                    });
                } else {
                    log.info("日志流已结束");
                    Platform.runLater(this::onLogStreamEnded);
                }
            } catch (IOException e) {
                if (generation != logStreamGeneration) {
                    log.info("日志流因切换容器而取消，静默退出");
                    return;
                }
                log.error("获取日志失败: {}", e.getMessage());
                Platform.runLater(() -> {
                    onLogStreamEnded();
                    showAlert("错误", "无法获取日志: " + e.getMessage());
                });
            }
        });
    }

    /** 重连日志流（不清理已有日志，不重置状态） */
    private void reconnectLogStream(int expectedGeneration, int remainingAttempts) {
        if (expectedGeneration != logStreamGeneration) return;

        K8sQuery query = AppConfig.getK8sQuery();
        if (!query.isSearchRunning() || query.getPodName() == null) return;

        LogFetchService.cancelCurrentCall();

        ExecutorManager.submit(() -> {
            try {
                LogFetchService.fetchStreaming(logStreamManager::enqueueLine, false);
                if (expectedGeneration != logStreamGeneration) {
                    log.info("重连的日志流因切换容器而取消，静默退出");
                    return;
                }
                if (query.isSearchRunning() && query.getPodName() != null) {
                    int next = remainingAttempts - 1;
                    if (next <= 0) {
                        log.info("已达最大重连次数，停止重连");
                        Platform.runLater(this::onLogStreamEnded);
                        return;
                    }
                    log.info("重连的日志流断开，继续自动重连（剩余{}次）...", next);
                    Platform.runLater(() -> {
                        PauseTransition delay = new PauseTransition(Duration.seconds(1));
                        delay.setOnFinished(e -> reconnectLogStream(expectedGeneration, next));
                        delay.play();
                    });
                } else {
                    Platform.runLater(this::onLogStreamEnded);
                }
            } catch (IOException e) {
                if (expectedGeneration != logStreamGeneration) {
                    log.info("重连的日志流因切换容器而取消，静默退出");
                    return;
                }
                log.error("重连日志流失败: {}", e.getMessage());
                int next = remainingAttempts - 1;
                if (query.isSearchRunning() && next > 0) {
                    Platform.runLater(() -> {
                        PauseTransition delay = new PauseTransition(Duration.seconds(3));
                        delay.setOnFinished(ev -> reconnectLogStream(expectedGeneration, next));
                        delay.play();
                    });
                } else {
                    log.info("已达最大重连次数或已停止，停止重连");
                    Platform.runLater(this::onLogStreamEnded);
                }
            }
        });
    }

    /** 日志流结束（容器退出/Pod消失/连接断开）后的清理工作 */
    private void onLogStreamEnded() {
        // 排空残留的日志行
        List<String> remaining = logStreamManager.drainQueue();
        if (!remaining.isEmpty()) {
            logStreamManager.processLogBatch(remaining);
        }

        // 停止刷新执行器，避免空转
        ExecutorManager.stopLogFlushExecutor();

        // 更新按钮状态
        K8sQuery k8sQuery = AppConfig.getK8sQuery();
        k8sQuery.setSearchRunning(false);
        searchToggleButton.setText("已结束");

        // 追加结束标记到日志区域
        logStreamManager.appendEndMarker();
    }

    /** 定时刷新：排空队列 → 处理批次 → 搜索刷新 */
    private void flushLogsToUI() {
        List<String> batch = logStreamManager.drainQueue();
        if (!batch.isEmpty()) {
            Platform.runLater(() -> {
                List<String> processedLines = logStreamManager.processLogBatch(batch);
                // 搜索增量更新
                if (!processedLines.isEmpty()) {
                    String searchKw = (searchBar != null && searchBar.isVisible()) ? buildSearchKeywordFromTags() : null;
                    if (searchKw != null && !searchKw.isBlank()) {
                        diskSearchEngine.rehighlightLogArea(searchKw);
                        diskSearchEngine.incrementalSearchUpdate(processedLines, searchKw, logStreamManager.getDiskEndLine());
                        diskSearchEngine.applyCurrentMatchHighlight();
                    }
                }
            });
        }

        // 按配置的时间间隔全量搜索一次，保证匹配列表完整
        long now = System.currentTimeMillis();
        int searchRefreshMs = AppPreferences.getSearchRefreshIntervalMs();
        if (now - logStreamManager.getLastSearchRefreshTime() >= searchRefreshMs) {
            logStreamManager.setLastSearchRefreshTime(now);
            Platform.runLater(this::refreshSearchIfNeeded);
        }
    }

    /** 定时刷新搜索（由 flushLogsToUI 触发），强制全量搜索保证匹配列表完整 */
    private void refreshSearchIfNeeded() {
        String searchKw = (searchBar != null && searchBar.isVisible()) ? buildSearchKeywordFromTags() : null;
        if (searchKw != null && !searchKw.isBlank()) {
            diskSearchEngine.searchDiskInBackground(searchKw, false, true);
        }
    }

    // ==================== 内联搜索（搜索栏 UI 逻辑） ====================

    private void initSearchBar() {
        // 回车：输入框有内容则添加标签，无内容则跳转下一个匹配
        inlineSearchField.setOnAction(e -> {
            String text = inlineSearchField.getText().trim();
            if (text.isEmpty()) {
                diskSearchEngine.findNext();
                return;
            }
            addSearchTag(text);
            inlineSearchField.clear();
            onSearchTagsChanged();
        });

        // 搜索框内上下方向键导航匹配
        inlineSearchField.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.DOWN) {
                diskSearchEngine.findNext();
                event.consume();
            } else if (event.getCode() == KeyCode.UP) {
                diskSearchEngine.findPrev();
                event.consume();
            }
        });

        // Ctrl+F / ESC / F3 快捷键
        Platform.runLater(() -> {
            if (logArea.getScene() != null) {
                logArea.getScene().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                    if (new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN).match(event)) {
                        toggleSearchBar(true);
                        String selected = logArea.getSelectedText();
                        if (selected != null && !selected.isBlank()) {
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
                                diskSearchEngine.findPrev();
                            } else {
                                diskSearchEngine.findNext();
                            }
                            event.consume();
                        }
                    }
                });
            }
        });
    }

    private boolean isTagExists(String keyword) {
        for (var node : tagContainer.getChildren()) {
            String kw = (String) ((HBox) node).getUserData();
            if (keyword.equals(kw)) return true;
        }
        return false;
    }

    private void addSearchTag(String keyword) {
        int index = tagContainer.getChildren().size();
        String colorClass = "search-tag-" + (index % SEARCH_HIGHLIGHT_COLORS);

        String display = keyword.length() > 8 ? keyword.substring(0, 8) + "..." : keyword;

        Label tagLabel = new Label(display);
        tagLabel.getStyleClass().addAll("search-tag", colorClass);
        Tooltip tooltip = new Tooltip(keyword);
        tooltip.setMaxWidth(400);
        tooltip.setWrapText(true);
        tooltip.setShowDelay(Duration.millis(200));
        tooltip.setShowDuration(Duration.seconds(300));
        tooltip.setStyle("-fx-background-color: #1a3a5c; -fx-text-fill: #e0e8f0; -fx-padding: 8 12; -fx-background-radius: 4; -fx-border-color: #3a7abd; -fx-border-radius: 4; -fx-font-size: 12px;");
        tagLabel.setTooltip(tooltip);
        tagLabel.setOnMouseClicked(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(keyword);
            Clipboard.getSystemClipboard().setContent(content);
            showAutoHideToast(tagLabel);
            e.consume();
        });

        Button removeBtn = new Button("×");
        removeBtn.getStyleClass().add("search-tag-remove");
        removeBtn.setOnMouseClicked(e -> {
            tagContainer.getChildren().remove(tagLabel.getParent());
            refreshTagColors();
            onSearchTagsChanged();
            e.consume();
        });

        HBox tagBox = new HBox(2, tagLabel, removeBtn);
        tagBox.getStyleClass().add("search-tag-box");
        tagBox.setUserData(keyword);

        tagContainer.getChildren().add(tagBox);
    }

    private void refreshTagColors() {
        for (int i = 0; i < tagContainer.getChildren().size(); i++) {
            HBox tagBox = (HBox) tagContainer.getChildren().get(i);
            Label tagLabel = (Label) tagBox.getChildren().get(0);
            tagLabel.getStyleClass().removeIf(s -> s.startsWith("search-tag-") && !s.equals("search-tag-box"));
            tagLabel.getStyleClass().add("search-tag-" + (i % SEARCH_HIGHLIGHT_COLORS));
        }
    }

    private void showAutoHideToast(Node anchor) {
        Popup toast = new Popup();
        toast.setAutoFix(true);

        Label check = new Label("✓");
        check.setStyle("-fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold; -fx-background-color: #4caf50; -fx-background-radius: 10; -fx-alignment: center; -fx-pref-width: 20; -fx-pref-height: 20;");
        Label text = new Label("复制成功");
        text.setStyle("-fx-text-fill: #333; -fx-font-size: 13px;");
        HBox box = new HBox(8, check, text);
        box.setStyle("-fx-background-color: white; -fx-padding: 8 18; -fx-background-radius: 6; -fx-alignment: center; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 8, 0, 0, 2);");
        toast.getContent().add(box);

        var stage = anchor.getScene().getWindow();
        toast.show(stage);
        Platform.runLater(() -> {
            toast.setX(stage.getX() + (stage.getWidth() - box.getWidth()) / 2);
            toast.setY(stage.getY() + 40);
        });

        PauseTransition wait = new PauseTransition(Duration.millis(500));
        wait.setOnFinished(e -> {
            FadeTransition fade = new FadeTransition(Duration.millis(500), box);
            fade.setFromValue(1.0);
            fade.setToValue(0.0);
            fade.setOnFinished(ev -> toast.hide());
            fade.play();
        });
        wait.play();
    }

    private String buildSearchKeywordFromTags() {
        if (tagContainer.getChildren().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var node : tagContainer.getChildren()) {
            String kw = (String) ((HBox) node).getUserData();
            sb.append(kw).append('\0');
        }
        return sb.toString();
    }

    private void onSearchTagsChanged() {
        String keyword = buildSearchKeywordFromTags();
        diskSearchEngine.searchDiskInBackground(keyword);
    }

    @FXML
    private void onSearchAdd() {
        String text = inlineSearchField.getText().trim();
        if (text.isEmpty()) return;
        addSearchTag(text);
        inlineSearchField.clear();
        onSearchTagsChanged();
    }

    @FXML
    private void onAndOrToggle() {
        boolean andMode = andOrToggle.isSelected();
        diskSearchEngine.setSearchAndMode(andMode);
        andOrToggle.setText(andMode ? "且" : "或");
        diskSearchEngine.resetLastKeyword();
        onSearchTagsChanged();
    }

    @FXML
    private void findNext() {
        String keyword = buildSearchKeywordFromTags();
        if (keyword.isEmpty()) return;
        diskSearchEngine.findNext();
    }

    @FXML
    private void findPrev() {
        String keyword = buildSearchKeywordFromTags();
        if (keyword.isEmpty()) return;
        diskSearchEngine.findPrev();
    }

    @FXML
    private void closeSearch() {
        toggleSearchBar(false);
    }

    private void toggleSearchBar(boolean show) {
        if (show) {
            inlineSearchField.requestFocus();
            diskSearchEngine.searchDiskInBackground(buildSearchKeywordFromTags());
        } else {
            inlineSearchField.clear();
            tagContainer.getChildren().clear();
            diskSearchEngine.clearSearch();
        }
    }

    // ==================== UI 事件处理 ====================

    @FXML
    public void handleOpenSettings(MouseEvent mouseEvent) {
        try {
            new SettingsController().openSettingsDialog();
            // 设置保存后刷新自动刷新配置
            treeViewManager.reloadAutoRefresh();
        } catch (IOException e) {
            Platform.runLater(() -> showAlert("错误", "无法加载设置窗口: " + e.getMessage()));
        }
    }

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

    private void setWrapButtonIcon() {
        javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
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

        if (searchRunning) {
            logStreamManager.resumeAndCatchUp();
            // 恢复后重新高亮搜索关键字并更新匹配列表
            String searchKw = (searchBar != null && searchBar.isVisible()) ? buildSearchKeywordFromTags() : null;
            if (searchKw != null && !searchKw.isBlank()) {
                diskSearchEngine.rehighlightLogArea(searchKw);
                diskSearchEngine.searchDiskInBackground(searchKw, false);
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
                // 在后台线程加载数据（K8s API 调用），避免阻塞 FX 线程
                TreeItem<String> rootItem = clusterTreeService.getRootItem();
                Platform.runLater(() -> treeViewManager.applyTreeFilter(rootItem));
            } catch (Exception e) {
                log.error("刷新失败: {}", e.getMessage());
            }
            Platform.runLater(() -> {
                refreshButton.setDisable(false);
                loadingIndicator.setVisible(false);
            });
        });
    }

    @FXML
    public void expandAllOnClick(MouseEvent mouseEvent) {
        treeViewManager.expandAll();
    }

    @FXML
    public void collapseAllOnClick(MouseEvent mouseEvent) {
        treeViewManager.collapseAll();
    }

    @FXML
    private void toggleTreePane() {
        if (isTreePaneVisible) {
            treePane.setVisible(false);
            treePane.setManaged(false);
            treePaneWrapper.setMinWidth(0);
            treePaneWrapper.setPrefWidth(0);
            collapseTab.setText("▶");
            if (!collapseTab.getStyleClass().contains("collapse-tab-collapsed")) {
                collapseTab.getStyleClass().add("collapse-tab-collapsed");
            }
            splitPane.setDividerPositions(0.0);
            splitPane.lookupAll(".split-pane-divider").forEach(node -> node.setMouseTransparent(true));
            isTreePaneVisible = false;
            Platform.runLater(this::updateCollapseTabPosition);
        } else {
            treePane.setVisible(true);
            treePane.setManaged(true);
            treePaneWrapper.setMinWidth(Region.USE_COMPUTED_SIZE);
            treePaneWrapper.setPrefWidth(Region.USE_COMPUTED_SIZE);
            collapseTab.setText("◀");
            collapseTab.getStyleClass().remove("collapse-tab-collapsed");
            isTreePaneVisible = true;
            // 等布局完成后再设置分割线到默认位置
            Platform.runLater(() -> {
                splitPane.setDividerPositions(DEFAULT_DIVIDER_POSITION);
                splitPane.lookupAll(".split-pane-divider").forEach(node -> node.setMouseTransparent(false));
                updateCollapseTabPosition();
            });
        }
    }

    private void setupCollapseTab() {
        StackPane.setAlignment(collapseTab, Pos.CENTER_LEFT);
        collapseTab.setText("◀");
        splitPane.widthProperty().addListener(obs -> updateCollapseTabPosition());
        splitPane.getDividers().get(0).positionProperty().addListener((obs, oldVal, newVal) -> {
            // 折叠状态下强制分割线位置为 0
            if (!isTreePaneVisible && newVal.doubleValue() > 0.001) {
                splitPane.setDividerPositions(0.0);
                return;
            }
            updateCollapseTabPosition();
        });
        Platform.runLater(this::updateCollapseTabPosition);
    }

    private void updateCollapseTabPosition() {
        double splitWidth = splitPane.getWidth();
        if (splitWidth <= 0) return;

        if (isTreePaneVisible) {
            double dividerX = splitWidth * splitPane.getDividerPositions()[0];
            collapseTab.setTranslateX(dividerX);
        } else {
            collapseTab.setTranslateX(0);
        }
    }

    @FXML
    public void scrollToTopClick(MouseEvent mouseEvent) {
        K8sQuery k8sQuery = AppConfig.getK8sQuery();
        if (k8sQuery.isSearchRunning()) {
            k8sQuery.setSearchRunning(false);
            searchToggleButton.setText("恢复");
        }
        logStreamManager.scrollToTop(k8sQuery.getPodName());
    }

    @FXML
    public void scrollToBottomClick(MouseEvent mouseEvent) {
        K8sQuery k8sQuery = AppConfig.getK8sQuery();
        if (!k8sQuery.isSearchRunning()) {
            k8sQuery.setSearchRunning(true);
            searchToggleButton.setText("暂停");
        }
        logStreamManager.scrollToBottom(k8sQuery.getPodName());
    }

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
                openWithChooser(file);
            }
        } catch (IOException e) {
            log.warn("无法用默认程序打开日志文件: {}", e.getMessage());
            openWithChooser(file);
        }
    }

    private void openWithChooser(File file) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择程序打开日志文件");
        chooser.setInitialDirectory(file.getParentFile());
        chooser.setInitialFileName(file.getName());
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
