package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.*;
import com.longfor.lmk.k8slogviewer.service.ClusterTreeService;
import com.longfor.lmk.k8slogviewer.service.LogFetchService;
import com.longfor.lmk.k8slogviewer.service.PodLogFileManager;
import com.longfor.lmk.k8slogviewer.utils.CommonUtils;
import com.longfor.lmk.k8slogviewer.utils.ExecutorManager;
import com.longfor.lmk.k8slogviewer.utils.LogStyleUtil;
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
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    @FXML private VBox treeLoadingOverlay;

    @FXML private Button collapseTab;
    @FXML private SplitPane splitPane;
    @FXML private ComboBox<KubeConfigProfile> profileSwitchCombo;
    @FXML private HBox namespaceFilterContainer;
    private com.longfor.lmk.k8slogviewer.ui.TagInput namespaceTagInput;

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
    private volatile boolean isNsReloading = false;  // 命名空间切换刷新树期间，跳过 Pod 日志查询
    private VirtualizedScrollPane<CodeArea> logScrollPane;

    /** 默认分割线位置，与 FXML 中 dividerPositions 一致 */
    private static final double DEFAULT_DIVIDER_POSITION = 0.18;

    /** 日志流代际计数器，切换 Pod 时递增以使旧的重连循环失效 */
    private final AtomicInteger logStreamGeneration = new AtomicInteger(0);

    /** 最大自动重连次数 */
    private static final int MAX_RECONNECT_ATTEMPTS = 3;

    /** 搜索高亮颜色数量，与 LogStyleUtil 保持一致 */
    private static final int SEARCH_HIGHLIGHT_COLORS = LogStyleUtil.SEARCH_HIGHLIGHT_COLORS;

    // ==================== 初始化 ====================

    @FXML
    public void initialize() throws IOException {
        log.info("K8s 日志查看器初始化...");

        // 彻底清除搜索输入框的JavaFX默认边框
        searchField.setStyle(
            "-fx-background-color: transparent;" +
            "-fx-border-color: transparent; -fx-border-width: 0;" +
            "-fx-background-insets: 0,0; -fx-background-radius: 0;" +
            "-fx-focus-color: transparent; -fx-faint-focus-color: transparent;"
        );

        // 创建管理器
        logStreamManager = new LogStreamManager(logArea, headerArea, fileManager);
        // 跟滚状态变化时同步按钮文案（向上滚→恢复，回底→暂停）
        logStreamManager.setOnAutoScrollStateChanged(paused ->
                searchToggleButton.setText(paused ? "恢复" : "暂停"));
        diskSearchEngine = new DiskSearchEngine(logArea, fileManager, matchCountLabel);
        treeViewManager = new TreeViewManager(treeView, statusFilterCombo, searchField, clusterTreeService);

        // 先加载偏好文件（必须在 init 之前，否则 combo 读到空数据）
        AppPreferences.loadFromFile();
        boolean isFirstRun = AppPreferences.isFirstRun();
        boolean ok = AppPreferences.initializeEnvironment();

        // 首次运行 或 自动检测失败时弹出设置面板
        if (!ok || isFirstRun) {
            if (!ok) log.warn("自动检测失败，需要手动配置");
            else log.info("首次运行，打开设置面板供用户确认配置");
            // 延迟到 Scene 就绪后再弹窗，避免 initOwner 时 Scene 为 null
            Platform.runLater(() -> {
                String prevProfile = AppPreferences.getActiveProfileName();
                try {
                    new SettingsController().openSettingsDialog();
                } catch (IOException e) {
                    CommonUtils.showToast(settingsButton, "✗", "初始化失败，请联系邮箱解决", "#E74C3C");
                    return;
                }
                treeViewManager.reloadAutoRefresh();
                refreshProfileCombo();
                if (!Objects.equals(prevProfile, AppPreferences.getActiveProfileName())) {
                    KubeConfigProfile curProfileObj = AppPreferences.getActiveProfile();
                    if (curProfileObj != null) {
                        switchProfile(curProfileObj);
                    }
                }
            });
        }

        initSettingsIcon();
        initProfileSwitchCombo();
        initNamespaceFilterCombo();
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
            // 命名空间切换刷新树期间跳过，避免误触发日志查询
            if (isNsReloading) return;
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

        loadNamespaceOptions();
    }

    private void initSettingsIcon() {
        try {
            ImageView icon = new ImageView(new Image(Objects.requireNonNull(
                    getClass().getResourceAsStream("/com/longfor/lmk/k8slogviewer/icons/settings.png"))));
            icon.setFitHeight(20);
            icon.setFitWidth(20);
            settingsButton.setGraphic(icon);
        } catch (Exception e) {
            Platform.runLater(() -> CommonUtils.showToast(settingsButton, "✗", "无法加载设置图标: " + e.getMessage(), "#E74C3C"));
        }
    }

    private void initProfileSwitchCombo() {
        profileSwitchCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(KubeConfigProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                    getStyleClass().removeIf("active-profile-item"::equals);
                } else {
                    setText(item.getName());
                    setGraphic(null);
                    // 当前激活配置高亮标识
                    String activeName = AppPreferences.getActiveProfileName();
                    if (item.getName().equals(activeName)) {
                        setStyle("-fx-font-weight: bold; -fx-text-fill: #326CE5;");
                        getStyleClass().add("active-profile-item");
                    } else {
                        getStyleClass().removeIf("active-profile-item"::equals);
                    }
                }
            }
        });
        profileSwitchCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(KubeConfigProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "切换配置" : item.getName());
            }
        });

        refreshProfileCombo();

        // 切换配置时不立即触发（避免初始化时触发），用标志位控制
        profileSwitchCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && oldVal != null && !newVal.getName().equals(oldVal.getName())) {
                switchProfile(newVal);
            }
        });
    }

    /** 初始化命名空间标签式多选过滤（TagInput 组件） */
    private void initNamespaceFilterCombo() {
        namespaceTagInput = new com.longfor.lmk.k8slogviewer.ui.TagInput();
        namespaceTagInput.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        HBox.setHgrow(namespaceTagInput, Priority.ALWAYS);
        namespaceFilterContainer.getChildren().add(namespaceTagInput);

        // 选中变化回调
        namespaceTagInput.setOnSelectionChanged(selected -> handleNsSelectionChanged());

        // 刷新命名空间回调（弹窗中点击 ⟳ 按钮）
        namespaceTagInput.setOnRefreshNamespaces(() -> {
            Platform.runLater(() -> {
                CommonUtils.showToast(namespaceTagInput, "⟳", "正在加载命名空间...", "#326CE5");
                fetchNamespacesFromApi(allNs -> {
                    String activeNs = getActiveNamespace();
                    if (activeNs != null && !allNs.contains(activeNs)) {
                        stopCurrentLogStream();
                    }
                    var currentSelected = new ArrayList<>(namespaceTagInput.getSelectedItems());
                    namespaceTagInput.getItems().setAll(allNs);
                    if (activeNs != null && allNs.contains(activeNs)
                            && !currentSelected.contains(activeNs)) {
                        currentSelected.add(0, activeNs);
                    }
                    currentSelected.retainAll(allNs);
                    if (!currentSelected.isEmpty()) {
                        namespaceTagInput.selectMultiple(currentSelected);
                    }
                    CommonUtils.showToast(namespaceTagInput, "✓",
                            "已刷新，共 " + allNs.size() + " 个命名空间", "#27AE60");
                }, err ->
                        CommonUtils.showToast(namespaceTagInput, "✗", "刷新失败: " + err, "#E74C3C")
                );
            });
        });
    }

    /** 处理命名空间选择变化 */
    private void handleNsSelectionChanged() {
        // 如果当前正在跑日志的容器所在命名空间不再被选中，关闭日志流
        String activeNs = getActiveNamespace();
        if (activeNs != null && !namespaceTagInput.getSelectedItems().contains(activeNs)) {
            stopCurrentLogStream();
        }
        saveNamespaceSelection();
        applyNsFilterFromCache();
    }

    /**
     * 获取当前正在运行日志的容器所在的命名空间。
     * 从 activePodPath（格式：root/ns/pod/container）中提取。
     */
    private String getActiveNamespace() {
        String path = treeViewManager.getActivePodPath();
        if (path == null) return null;
        String[] parts = path.split("/", -1);
        // 格式: root/ns/pod/container → ns 在 index 1
        return parts.length >= 3 ? parts[1] : null;
    }

    /** 关闭当前日志流并清空状态（不显示结束提示） */
    private void stopCurrentLogStream() {
        logStreamGeneration.incrementAndGet();
        LogFetchService.cancelCurrentCall();
        ExecutorManager.stopLogFlushExecutor();
        logStreamManager.resetForNewPod();
        logStreamManager.clearAreas();
        AppConfig.getK8sQuery().resetRuntimeState();
        AppConfig.getK8sQuery().setPodName(null);
        AppConfig.getK8sQuery().setNamespace(null);
        treeViewManager.setActivePodPath(null);
    }

    /** 从 K8s API 加载命名空间列表并恢复选中状态（仅首次或切配置时调用） */
    private void loadNamespaceOptions() {
        fetchNamespacesFromApi(allNs -> {
            namespaceTagInput.getItems().setAll(allNs);
            restoreNamespaceSelection(allNs);
        }, err -> CommonUtils.showToast(settingsButton, "✗", "加载命名空间失败: " + err, "#E74C3C"));
    }

    /**
     * 从 K8s API 异步加载全量命名空间列表（不再查询全量 Pod）。
     *
     * @param onSuccess  加载成功回调（FX 线程），接收 allNs 列表
     * @param onError    加载失败回调（FX 线程，可为 null 仅打日志）
     */
    private void fetchNamespacesFromApi(java.util.function.Consumer<java.util.List<String>> onSuccess,
                                        java.util.function.Consumer<String> onError) {
        ExecutorManager.submit(() -> {
            try {
                var api = com.longfor.lmk.k8slogviewer.config.K8sClientManager.getCoreV1Api();
                java.util.List<String> allNs = api.listNamespace(null, null, null, null,
                        null, null, null, null, null).getItems().stream()
                        .map(ns -> ns.getMetadata().getName())
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
                Platform.runLater(() -> onSuccess.accept(allNs));
            } catch (Exception e) {
                log.warn("加载命名空间列表失败: {}", e.getMessage());
                if (onError != null) {
                    Platform.runLater(() -> onError.accept(e.getMessage()));
                }
            }
        });
    }

    /**
     * 恢复已保存的命名空间选中状态。
     * <ul>
     *   <li>有保存配置 → 直接使用</li>
     *   <li>无保存配置 → 逐个探测找到第一个有 Pod 的 ns，自动选中并持久化</li>
     * </ul>
     */
    private void restoreNamespaceSelection(java.util.List<String> allNs) {
        String profileName = AppPreferences.getActiveProfileName();
        if (profileName == null || allNs.isEmpty()) return;
        List<String> saved = AppPreferences.getSelectedNamespaces(profileName);

        if (!saved.isEmpty()) {
            // 有保存配置，直接使用（过滤掉已不存在的命名空间）
            saved.retainAll(allNs);
            if (!saved.isEmpty()) {
                namespaceTagInput.selectMultiple(saved);
                Platform.runLater(this::applyNsFilterFromCache);
                return;
            }
        }

        // 无有效保存配置：异步逐个探测，找到第一个有 Pod 的命名空间
        findFirstNamespaceWithPodsAndSelect(allNs);
    }

    /**
     * 逐个探测命名空间，找到第一个包含 Pod 的 ns 后选中并保存为默认配置。
     */
    private void findFirstNamespaceWithPodsAndSelect(java.util.List<String> allNs) {
        ExecutorManager.submit(() -> {
            for (String ns : allNs) {
                try {
                    var api = com.longfor.lmk.k8slogviewer.config.K8sClientManager.getCoreV1Api();
                    var pods = api.listNamespacedPod(ns, null, null, null, null,
                            null, null, null, null, null);
                    if (pods != null && !pods.getItems().isEmpty()) {
                        String found = ns;
                        Platform.runLater(() -> {
                            namespaceTagInput.selectMultiple(List.of(found));
                            saveNamespaceSelection();
                            applyNsFilterFromCache();
                        });
                        return;
                    }
                } catch (Exception e) {
                    log.warn("探测命名空间[{}]是否有Pod失败: {}", ns, e.getMessage());
                }
            }
            // 所有命名空间都没有 Pod，选中第一个作为兜底
            if (!allNs.isEmpty()) {
                String first = allNs.get(0);
                Platform.runLater(() -> {
                    namespaceTagInput.selectMultiple(List.of(first));
                    CommonUtils.showToast(namespaceTagInput, "⚠", "未找到包含 Pod 的命名空间", "#F39C12");
                });
            }
        });
    }

    /** 保存当前选中的命名空间到偏好设置 */
    private void saveNamespaceSelection() {
        String profileName = AppPreferences.getActiveProfileName();
        if (profileName != null) {
            AppPreferences.setSelectedNamespaces(profileName,
                    new ArrayList<>(namespaceTagInput.getSelectedItems()));
        }
    }

    /**
     * 根据当前选中命名空间加载树并展示。
     */
    private void applyNsFilterFromCache() {
        reloadTreeWithApiCall();
    }

    /**
     * 根据当前选中命名空间全量加载树（含所有 Pod 子节点，不懒加载）。
     */
    private void reloadTreeWithApiCall() {
        List<String> nsList = getEffectiveNamespaces();
        if (nsList.isEmpty()) return;

        isNsReloading = true;
        refreshButton.setDisable(true);
        treeViewManager.cancelPendingFilters();
        searchField.setText("");
        treeViewManager.clearStatusFilter();
        loadingIndicator.setVisible(true);
        treeLoadingOverlay.setVisible(true);

        ExecutorManager.submit(() -> {
            try {
                // 先设置 lastRequestedNamespaces（forceReloadFull 依赖此字段）
                clusterTreeService.loadForNamespaces(nsList);
                // 全量加载所有 Pod
                TreeItem<String> fullRoot = clusterTreeService.forceReloadFull();
                Platform.runLater(() -> {
                    if (fullRoot != null) {
                        treeViewManager.setRootDirectly(fullRoot);
                        // 如果有正在跑日志的容器且其命名空间仍在选中列表中，自动定位到该 Pod
                        String activePath = treeViewManager.getActivePodPath();
                        String activeNs = getActiveNamespace();
                        if (activePath != null && activeNs != null
                                && namespaceTagInput.getSelectedItems().contains(activeNs)) {
                            treeViewManager.locateAndExpandToPath(activePath);
                        }
                    }
                    treeLoadingOverlay.setVisible(false);
                    loadingIndicator.setVisible(false);
                    refreshButton.setDisable(false);
                    isNsReloading = false;
                });
            } catch (Exception e) {
                log.error("加载集群数据失败: {}", e.getMessage());
                Platform.runLater(() -> {
                    treeLoadingOverlay.setVisible(false);
                    loadingIndicator.setVisible(false);
                    refreshButton.setDisable(false);
                    isNsReloading = false;
                    CommonUtils.showToast(refreshButton, "✗", "加载数据失败: " + e.getMessage(), "#E74C3C");
                });
            }
        });
    }

    /**
     * 获取实际生效的命名空间列表（始终非空）。
     */
    private List<String> getEffectiveNamespaces() {
        var checked = namespaceTagInput.getSelectedItems();
        return new ArrayList<>(checked);
    }
    public void refreshProfileCombo() {
        KubeConfigProfile current = AppPreferences.getActiveProfile();
        profileSwitchCombo.getItems().setAll(AppPreferences.getKubeConfigProfiles());
        if (current != null) {
            for (KubeConfigProfile p : profileSwitchCombo.getItems()) {
                if (current.getName().equals(p.getName())) {
                    profileSwitchCombo.getSelectionModel().select(p);
                    break;
                }
            }
        }
    }

    /** 切换 KubeConfig 配置 */
    private void switchProfile(KubeConfigProfile profile) {
        AppPreferences.setActiveProfileName(profile.getName());
        K8sClientManager.reset();
        clusterTreeService.clearNsCache();

        // 关闭当前日志流
        stopCurrentLogStream();

        // 静默清空（不触发中间状态的 UI 重建，避免闪烁）
        namespaceTagInput.resetSilent();
        loadNamespaceOptions();

        reloadTreeWithApiCall();
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
        int generation = logStreamGeneration.incrementAndGet();

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
                if (generation != logStreamGeneration.get()) {
                    log.info("日志流因切换容器而取消，静默退出");
                    return;
                }
                if (query.isSearchRunning() && query.getPodName() != null) {
                    log.info("日志流断开，容器仍在运行，自动重连...");
                    Platform.runLater(() -> {
                        PauseTransition delay = new PauseTransition(Duration.seconds(1));
                        delay.setOnFinished(e -> {
                            if (generation == logStreamGeneration.get()) {
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
                if (generation != logStreamGeneration.get()) {
                    log.info("日志流因切换容器而取消，静默退出");
                    return;
                }
                log.error("获取日志失败: {}", e.getMessage());
                Platform.runLater(() -> {
                    onLogStreamEnded();
                    CommonUtils.showToast(searchBar, "✗", "无法获取日志: " + e.getMessage(), "#E74C3C");
                });
            }
        });
    }

    /** 重连日志流（不清理已有日志，不重置状态） */
    private void reconnectLogStream(int expectedGeneration, int remainingAttempts) {
        if (expectedGeneration != logStreamGeneration.get()) return;

        K8sQuery query = AppConfig.getK8sQuery();
        if (!query.isSearchRunning() || query.getPodName() == null) return;

        LogFetchService.cancelCurrentCall();

        ExecutorManager.submit(() -> {
            try {
                LogFetchService.fetchStreaming(logStreamManager::enqueueLine, false);
                if (expectedGeneration != logStreamGeneration.get()) {
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
                if (expectedGeneration != logStreamGeneration.get()) {
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
        CommonUtils.showToast(anchor, "✓", "复制成功", "#4caf50");
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
            String prevProfile = AppPreferences.getActiveProfileName();
            new SettingsController().openSettingsDialog();
            // 设置保存后刷新自动刷新配置
            treeViewManager.reloadAutoRefresh();
            // 刷新工具栏配置下拉列表
            refreshProfileCombo();
            // 配置切换时复用 switchProfile 统一处理
            String curProfile = AppPreferences.getActiveProfileName();
            if (!Objects.equals(prevProfile, curProfile)) {
                KubeConfigProfile curProfileObj = AppPreferences.getActiveProfile();
                if (curProfileObj != null) {
                    switchProfile(curProfileObj);
                }
            }
        } catch (IOException e) {
            Platform.runLater(() -> CommonUtils.showToast(settingsButton, "✗", "无法加载设置窗口: " + e.getMessage(), "#E74C3C"));
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

        // 当前显示"恢复" → 全部恢复：日志流 + 跟滚 + 置底
        if (!k8sQuery.isSearchRunning() || logStreamManager.isAutoScrollPaused()) {
            k8sQuery.setSearchRunning(true);
            logStreamManager.resumeAutoScroll();   // 触发回调改按钮文案为"暂停"
            logStreamManager.resumeAndCatchUp();
            String searchKw = (searchBar != null && searchBar.isVisible()) ? buildSearchKeywordFromTags() : null;
            if (searchKw != null && !searchKw.isBlank()) {
                diskSearchEngine.rehighlightLogArea(searchKw);
                diskSearchEngine.searchDiskInBackground(searchKw, false);
            }
            return;
        }

        // 当前显示"暂停" → 暂停跟滚（日志继续追加到 UI，用户可上下查看）
        logStreamManager.pauseAutoScroll();       // 触发回调改按钮文案为"恢复"
    }

    @FXML
    public void refreshOnClick(MouseEvent mouseEvent) {
        reloadTreeWithApiCall();
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
            logStreamManager.pauseAutoScroll();   // 触发回调改按钮文案为"恢复"
        }
        logStreamManager.scrollToTop(k8sQuery.getPodName());
    }

    @FXML
    public void scrollToBottomClick(MouseEvent mouseEvent) {
        K8sQuery k8sQuery = AppConfig.getK8sQuery();
        k8sQuery.setSearchRunning(true);
        logStreamManager.resumeAutoScroll();   // 触发回调改按钮文案为"暂停"
        logStreamManager.scrollToBottom(k8sQuery.getPodName());
    }

    @FXML
    public void openLogFileClick(MouseEvent mouseEvent) {
        String podName = AppConfig.getK8sQuery().getPodName();
        if (podName == null) {
            CommonUtils.showToast(openLogFileButton, "⚠", "请先选择一个 Pod", "#F39C12");
            return;
        }

        java.nio.file.Path logFile = fileManager.getLatestLogFile(podName);
        if (logFile == null || !Files.exists(logFile)) {
            CommonUtils.showToast(openLogFileButton, "⚠", "当前没有日志文件", "#F39C12");
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
            CommonUtils.showToast(openLogFileButton, "✗", "无法用所选程序打开日志文件: " + ex.getMessage(), "#E74C3C");
        }
    }
}
