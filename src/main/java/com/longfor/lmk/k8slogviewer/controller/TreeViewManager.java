package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.AppPreferences;
import com.longfor.lmk.k8slogviewer.config.K8sClientManager;
import com.longfor.lmk.k8slogviewer.model.PodStatus;
import com.longfor.lmk.k8slogviewer.service.ClusterTreeService;
import com.longfor.lmk.k8slogviewer.utils.CommonUtils;
import com.longfor.lmk.k8slogviewer.utils.ExecutorManager;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 树视图管理器，负责树初始化、状态筛选、上下文菜单、定时刷新。
 * <p>
 * 从 K8sLogViewerController 中拆分出来，职责单一：
 * <ul>
 *   <li>TreeView 初始化与事件绑定</li>
 *   <li>按文本/状态筛选树节点</li>
 *   <li>状态下拉筛选 ComboBox 初始化</li>
 *   <li>右键上下文菜单（删除 Pod / 性能监控）</li>
 *   <li>定时刷新树并保持选中状态</li>
 * </ul>
 */
public class TreeViewManager {

    private static final Logger log = LoggerFactory.getLogger(TreeViewManager.class);

    private final TreeView<String> treeView;
    private final ComboBox<String> statusFilterCombo;
    private final TextField searchField;
    private final ClusterTreeService clusterTreeService;

    /** 当前状态筛选，null 表示"全部" */
    private String currentStatusFilter = null;

    /** 用户手动展开的节点路径集合，搜索自动展开的不在其中 */
    private final Set<String> manuallyExpandedPaths = new HashSet<>();

    /** 上一次搜索文本，用于判断是否从有搜索变为无搜索 */
    private String prevSearchText = "";

    /** 搜索输入防抖：避免每个字符变化都重建树 */
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(500));

    /** 过滤任务代际计数器，用于取消过时的后台过滤任务 */
    private volatile int filterGeneration = 0;

    /** 配置切换代际计数器，用于忽略过时的自动刷新结果 */
    private volatile int profileGeneration = 0;

    /** 当前活跃 Pod 路径（正在查看日志的 Pod），独立于 TreeView 选中状态，用于视觉标识 */
    private String activePodPath = null;

    /** 设置当前活跃 Pod 路径，并刷新树以更新视觉标识 */
    public void setActivePodPath(String path) {
        String oldPath = this.activePodPath;
        this.activePodPath = path;
        // 如果路径变化了，刷新树以更新标识样式
        if (!Objects.equals(oldPath, path)) {
            treeView.refresh();
        }
    }

    /** 获取当前活跃 Pod 路径 */
    public String getActivePodPath() {
        return activePodPath;
    }

    /** 清除状态筛选，恢复为"全部" */
    public void clearStatusFilter() {
        currentStatusFilter = null;
        statusFilterCombo.getSelectionModel().selectFirst();
    }

    /**
     * 取消所有待执行的过滤任务（配置切换时调用，防止旧任务覆盖新树）。
     * 递增代际计数器使正在执行的异步任务结果被丢弃，并停止搜索防抖。
     */
    public void cancelPendingFilters() {
        filterGeneration++;
        profileGeneration++;
        searchDebounce.stop();
    }

    /** 自动刷新定时器，null 表示未启动 */
    private volatile ScheduledExecutorService autoRefreshExecutor;

    public TreeViewManager(TreeView<String> treeView, ComboBox<String> statusFilterCombo,
                           TextField searchField, ClusterTreeService clusterTreeService) {
        this.treeView = treeView;
        this.statusFilterCombo = statusFilterCombo;
        this.searchField = searchField;
        this.clusterTreeService = clusterTreeService;
    }

    /** 初始化树视图：绑定事件、上下文菜单、状态下拉、定时刷新 */
    public void init() {
        // 自定义 Cell Factory
        treeView.setCellFactory(tv -> new StyledTreeCell());

        treeView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 1) {
                TreeItem<String> clicked = treeView.getSelectionModel().getSelectedItem();
                if (clicked != null && !clicked.isLeaf()) {
                    boolean wasExpanded = clicked.isExpanded();
                    clicked.setExpanded(!wasExpanded);
                    String path = getTreeItemPath(clicked);
                    if (path != null) {
                        if (wasExpanded) {
                            manuallyExpandedPaths.remove(path);
                        } else {
                            manuallyExpandedPaths.add(path);
                        }
                    }
                }
            }
        });

        // 右键上下文菜单
        ContextMenu podContextMenu = new ContextMenu();
        MenuItem deletePodItem = new MenuItem("删除 Pod");
        deletePodItem.setOnAction(e -> onDeletePod());
        MenuItem monitorItem = new MenuItem("性能监控");
        monitorItem.setOnAction(e -> onPodMonitor());
        podContextMenu.getItems().addAll(deletePodItem, monitorItem);

        treeView.setContextMenu(podContextMenu);
        podContextMenu.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (isShowing) {
                TreeItem<String> selected = treeView.getSelectionModel().getSelectedItem();
                boolean isPod = selected != null && selected.getParent() != null
                        && selected.getParent().getParent() != null;
                deletePodItem.setVisible(isPod);
                monitorItem.setVisible(isPod);
            }
        });

        // 搜索框文本变化时防抖过滤树
        searchDebounce.setOnFinished(e -> {
            String text = searchField.getText();
            boolean hadFilter = prevSearchText != null && !prevSearchText.isEmpty();
            boolean hasFilter = text != null && !text.isEmpty();

            if (hadFilter && !hasFilter) {
                collapseAutoExpanded();
            }

            // 过滤已异步化，直接调用
            applyTreeFilter();

            // 异步完成后恢复选中状态
            String selectedPath = getTreeItemPath(treeView.getSelectionModel().getSelectedItem());
            if (selectedPath != null) {
                // 延迟一点等待异步过滤完成后再恢复选中
                PauseTransition restoreSelection = new PauseTransition(Duration.millis(50));
                restoreSelection.setOnFinished(ev -> {
                    TreeItem<String> target = findTreeItemByPath(treeView.getRoot(), selectedPath);
                    if (target != null) {
                        treeView.getSelectionModel().select(target);
                    }
                });
                restoreSelection.play();
            }

            prevSearchText = text;
        });
        searchField.textProperty().addListener((obs, oldVal, newVal) -> searchDebounce.playFromStart());

        // 状态筛选下拉
        initStatusFilterDots();

        // 首次加载（不弹 toast）
        refreshTree(false);

        // 根据配置决定是否自动刷新容器树
        if (AppPreferences.isTreeAutoRefresh()) {
            startAutoRefresh();
        }
    }

    // ==================== 树刷新与过滤 ====================

    private void collapseAutoExpanded() {
        TreeItem<String> root = treeView.getRoot();
        if (root == null) return;
        collapseNonManual(root);
    }

    /** 将指定节点及其所有上级标记为手动展开 */
    public void markExpandedToRoot(TreeItem<String> node) {
        if (node == null) return;
        TreeItem<String> current = node;
        while (current != null) {
            String path = getTreeItemPath(current);
            if (path != null) {
                manuallyExpandedPaths.add(path);
            }
            if (!current.isExpanded()) {
                current.setExpanded(true);
            }
            current = current.getParent();
        }
    }

    private void collapseNonManual(TreeItem<String> node) {
        if (node == null) return;
        String path = getTreeItemPath(node);
        if (node.isExpanded() && path != null && !manuallyExpandedPaths.contains(path)) {
            node.setExpanded(false);
        }
        for (TreeItem<String> child : node.getChildren()) {
            collapseNonManual(child);
        }
    }

    /** 恢复手动展开的节点展开状态（用于直接恢复原树时） */
    private void expandManuallyExpanded(TreeItem<String> node) {
        if (node == null) return;
        String path = getTreeItemPath(node);
        if (path != null && manuallyExpandedPaths.contains(path)) {
            node.setExpanded(true);
        }
        for (TreeItem<String> child : node.getChildren()) {
            expandManuallyExpanded(child);
        }
    }

    /** 启动自动刷新定时器 */
    private void startAutoRefresh() {
        if (autoRefreshExecutor != null) return;
        int intervalSec = AppPreferences.getTreeAutoRefreshIntervalSec();
        log.debug("[自动刷新] 启动定时器，间隔={}s", intervalSec);
        autoRefreshExecutor = ExecutorManager.newSingleThreadScheduled("k8s-tree-refresh-");
        autoRefreshExecutor.scheduleAtFixedRate(() -> {
            int gen = profileGeneration;
            log.debug("[自动刷新] 触发增量刷新，代际={}, 间隔={}s", gen, intervalSec);
            // 后台线程只做 K8s API 数据采集，不触碰已有 TreeItem
            var podData = clusterTreeService.incrementalFetchPods();
            if (podData == null) {
                log.debug("[自动刷新] 增量采集返回 null（无命名空间或配置缺失）");
                return;
            }
            log.debug("[自动刷新] 采集完成，{} 个命名空间有数据", podData.size());

            Platform.runLater(() -> {
                // 配置已切换，丢弃过时的刷新结果
                if (gen != profileGeneration) return;

                String textFilter = searchField.getText();
                boolean hasFilter = textFilter != null && !textFilter.isEmpty()
                        || currentStatusFilter != null;
                // 有过滤条件时走过滤流程
                if (hasFilter) {
                    // 先在 FX 线程上合并数据到树，再过滤
                    TreeItem<String> mergedRoot = clusterTreeService.applyIncrementalUpdate(podData);
                    applyTreeFilterAsync(mergedRoot);
                    return;
                }

                // 无过滤：FX 线程上合并数据 + refresh 视图
                clusterTreeService.applyIncrementalUpdate(podData);
                treeView.refresh();
                log.debug("[自动刷新] 树视图刷新完成");
            });
        }, intervalSec, intervalSec, TimeUnit.SECONDS);
    }

    /** 停止自动刷新定时器 */
    private void stopAutoRefresh() {
        ScheduledExecutorService executor = autoRefreshExecutor;
        autoRefreshExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** 重新加载自动刷新配置（设置变更后调用） */
    public void reloadAutoRefresh() {
        stopAutoRefresh();
        if (AppPreferences.isTreeAutoRefresh()) {
            startAutoRefresh();
        }
    }

    /** 全部展开 */
    public void expandAll() {
        TreeItem<String> root = treeView.getRoot();
        if (root != null) {
            setExpandedAll(root, true);
        }
    }

    /** 全部折叠 */
    public void collapseAll() {
        TreeItem<String> root = treeView.getRoot();
        if (root != null) {
            setExpandedAll(root, false);
        }
    }

    private static void setExpandedAll(TreeItem<String> node, boolean expanded) {
        if (node == null) return;
        node.setExpanded(expanded);
        for (TreeItem<String> child : node.getChildren()) {
            setExpandedAll(child, expanded);
        }
    }

    public void refreshTree() {
        refreshTree(true);
    }

    public void refreshTree(boolean showToast) {
        TreeItem<String> rootItem = clusterTreeService.getCachedRoot();
        applyTreeFilter(rootItem);
        if (showToast) {
            CommonUtils.showToast(treeView, "↻", "刷新成功", "#326CE5");
        }
    }

    /** 应用搜索关键字 + 状态筛选（异步，不阻塞 FX 线程） */
    public void applyTreeFilter() {
        applyTreeFilterAsync(clusterTreeService.getCachedRoot());
    }

    public void applyTreeFilter(TreeItem<String> rootItem) {
        applyTreeFilterAsync(rootItem);
    }

    /**
     * 异步执行树过滤：过滤逻辑在后台线程，setRoot 在 FX 线程。
     * 使用代际计数器过滤过时的结果，确保快速连续操作时只应用最新结果。
     */
    private void applyTreeFilterAsync(TreeItem<String> rootItem) {
        if (rootItem == null) {
            Platform.runLater(() -> treeView.setRoot(null));
            return;
        }

        int generation = ++filterGeneration;
        String textFilter = searchField.getText();
        String statusFilter = currentStatusFilter;

        ExecutorManager.submit(() -> {
            TreeItem<String> result = rootItem;
            if (statusFilter != null) {
                result = filterTreeByStatus(result, statusFilter);
            }
            if (textFilter != null && !textFilter.isEmpty()) {
                result = CommonUtils.filterTree(result, textFilter);
            }

            // 代际检查：如果期间有新任务提交，丢弃本结果
            if (generation != filterGeneration) return;

            TreeItem<String> finalResult = result;
            Platform.runLater(() -> {
                if (generation != filterGeneration) return;

                if (finalResult == null) {
                    treeView.setRoot(null);
                    return;
                }

                // 搜索/状态过滤时直接替换，不用增量合并（合并会导致删字符时结果丢失）
                finalResult.setExpanded(true);
                treeView.setRoot(finalResult);
            });
        });
    }

    /**
     * 直接设置树根节点（同步，用于已有 freshRoot 的场景，如配置切换后异步加载完成）。
     * 如果有搜索/状态筛选，仍会异步过滤。
     */
    public void setRootDirectly(TreeItem<String> rootItem) {
        String textFilter = searchField.getText();
        String statusFilter = currentStatusFilter;
        // 有过滤条件时走异步过滤
        if (rootItem != null && ((textFilter != null && !textFilter.isEmpty()) || statusFilter != null)) {
            applyTreeFilterAsync(rootItem);
            return;
        }
        // 无过滤条件，直接设置
        if (rootItem == null) {
            treeView.setRoot(null);
        } else {
            TreeItem<String> currentRoot = treeView.getRoot();
            if (currentRoot != null && Objects.equals(currentRoot.getValue(), rootItem.getValue())) {
                mergeChildren(currentRoot, rootItem);
                currentRoot.setExpanded(true); // 确保根节点展开
            } else {
                rootItem.setExpanded(true); // 确保根节点展开
                treeView.setRoot(rootItem);
            }
        }
    }

    /**
     * 增量合并：将 source 的子节点合并到 target 中。
     * 保留 target 中已有的节点（含展开状态），添加新增节点，移除已删除节点。
     */
    private static void mergeChildren(TreeItem<String> target, TreeItem<String> source) {
        if (target == null || source == null) return;

        // 按 value 建立现有子节点索引
        Map<String, TreeItem<String>> targetMap = new LinkedHashMap<>();
        for (TreeItem<String> child : target.getChildren()) {
            targetMap.put(child.getValue(), child);
        }

        // 按 source 顺序构建合并后的子节点列表
        List<TreeItem<String>> merged = new ArrayList<>();
        for (TreeItem<String> srcChild : source.getChildren()) {
            TreeItem<String> tgtChild = targetMap.get(srcChild.getValue());
            if (tgtChild != null) {
                // 已有节点：递归合并子节点，同步附加数据（Pod 状态可能变化）
                mergeChildren(tgtChild, srcChild);
                Object srcData = CommonUtils.getTreeItemData(srcChild);
                if (srcData != null) {
                    CommonUtils.putTreeItemData(tgtChild, srcData);
                }
                // 搜索过滤时 source 节点被标记为展开，需传递到已有节点
                if (srcChild.isExpanded() && !tgtChild.isExpanded()) {
                    tgtChild.setExpanded(true);
                }
                merged.add(tgtChild);
            } else {
                // 新增节点
                merged.add(srcChild);
            }
        }

        // 仅在子节点有变化时才替换，避免不必要的 UI 事件导致选中丢失
        List<TreeItem<String>> current = target.getChildren();
        if (current.size() != merged.size() || !current.equals(merged)) {
            target.getChildren().setAll(merged);
        }
    }

    /** 按状态筛选树：只保留匹配状态的 Pod 节点，保持展开状态 */
    private static TreeItem<String> filterTreeByStatus(TreeItem<String> node, String status) {
        if (node == null) return null;

        // 叶子节点（Pod）：通过附加数据判断状态
        if (node.isLeaf() && CommonUtils.getTreeItemData(node) instanceof String phase) {
            if (status.equals(phase)) {
                return CommonUtils.copyLeafNode(node);
            }
            return null;
        }

        // 非叶子节点：处理子节点
        TreeItem<String> newParent = new TreeItem<>(node.getValue());
        newParent.setExpanded(true); // 筛选时自动展开
        for (TreeItem<String> child : node.getChildren()) {
            TreeItem<String> filtered = filterTreeByStatus(child, status);
            if (filtered != null) {
                newParent.getChildren().add(filtered);
            }
        }
        return newParent.getChildren().isEmpty() ? null : newParent;
    }

    // ==================== 状态筛选 ComboBox ====================

    /** 创建状态圆点（"All" 为空心，其余为实心） */
    private static Circle createStatusDot(String item, Map<String, String> statusColors) {
        Circle dot = new Circle(5);
        if ("All".equals(item)) {
            dot.setFill(Color.TRANSPARENT);
            dot.setStroke(Color.web(statusColors.get(item)));
            dot.setStrokeWidth(1.5);
        } else {
            dot.setFill(Color.web(statusColors.get(item)));
        }
        return dot;
    }

    /** 初始化状态下拉筛选（嵌入搜索框，按钮只显示颜色圆点，下拉列表圆点+文字） */
    private void initStatusFilterDots() {
        // 使用 PodStatus 枚举构建映射，"All" 为特殊筛选值
        Map<String, String> statusColors = new LinkedHashMap<>();
        statusColors.put("All", "#757575");
        for (PodStatus s : PodStatus.values()) {
            statusColors.put(s.getPhase(), s.getColorHex());
        }

        statusFilterCombo.getItems().addAll(statusColors.keySet());
        statusFilterCombo.getSelectionModel().selectFirst();

        // 下拉列表：圆点 + 文字
        statusFilterCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    setGraphic(createStatusDot(item, statusColors));
                    setText(item);
                }
            }
        });

        // 按钮上只显示颜色圆点，不显示文字
        statusFilterCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    setGraphic(createStatusDot(item, statusColors));
                }
                setText(null);
            }
        });

        statusFilterCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            currentStatusFilter = "All".equals(newVal) ? null : newVal;
            applyTreeFilter();
        });
    }

    // ==================== 树节点路径工具 ====================

    /** 获取树节点路径，格式：集群/namespace/pod */
    static String getTreeItemPath(TreeItem<String> item) {
        if (item == null) return null;
        StringBuilder sb = new StringBuilder(item.getValue());
        TreeItem<String> parent = item.getParent();
        while (parent != null) {
            sb.insert(0, parent.getValue() + "/");
            parent = parent.getParent();
        }
        return sb.toString();
    }

    /** 按路径查找树节点 */
    static TreeItem<String> findTreeItemByPath(TreeItem<String> root, String path) {
        if (root == null || path == null) return null;
        String[] parts = path.split("/", -1);
        TreeItem<String> current = root;
        for (int i = 1; i < parts.length && current != null; i++) {
            String part = parts[i];
            TreeItem<String> found = null;
            for (TreeItem<String> child : current.getChildren()) {
                if (part.equals(child.getValue())) {
                    found = child;
                    break;
                }
            }
            current = found;
        }
        return current;
    }

    /**
     * 根据路径定位并展开到目标节点，然后滚动到可见位置。
     * 用于刷新树后保持当前日志容器的位置。
     */
    public void locateAndExpandToPath(String path) {
        if (path == null) return;
        TreeItem<String> root = treeView.getRoot();
        if (root == null) return;

        // 展开根节点
        root.setExpanded(true);

        String[] parts = path.split("/", -1);
        TreeItem<String> current = root;
        for (int i = 1; i < parts.length && current != null; i++) {
            String part = parts[i];
            TreeItem<String> found = null;
            for (TreeItem<String> child : current.getChildren()) {
                if (part.equals(child.getValue())) {
                    found = child;
                    break;
                }
            }
            if (found != null) {
                found.setExpanded(true);
                current = found;
            } else {
                return; // 路径中某一级找不到，停止展开
            }
        }
        // 滚动到目标节点
        int index = treeView.getRow(current);
        if (index >= 0) {
            treeView.scrollTo(index);
        }
    }

    // ==================== 右键操作 ====================

    /** 右键删除选中的 Pod */
    private void onDeletePod() {
        TreeItem<String> selected = treeView.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getParent() == null || selected.getParent().getParent() == null) return;

        String podName = selected.getValue();
        String namespace = selected.getParent().getValue();

        CommonUtils.showDeletePodConfirm(podName, namespace, force -> {
            ExecutorManager.submit(() -> {
                try {
                    CoreV1Api api = K8sClientManager.getCoreV1Api();
                    if (force) {
                        // 强制删除：gracePeriodSeconds=0，立即从 etcd 移除
                        api.deleteNamespacedPod(
                                podName, namespace,
                                null, null, 0, null, null, null
                        );
                        log.info("已强制删除 Pod: {}/{}", namespace, podName);
                    } else {
                        api.deleteNamespacedPod(
                                podName, namespace,
                                null, null, null, null, null, null
                        );
                        log.info("已优雅删除 Pod: {}/{}", namespace, podName);
                    }
                    Platform.runLater(() -> {
                        clusterTreeService.forceReloadFull();
                        refreshTree(false);
                        CommonUtils.showToast(treeView, "✓", "Pod " + podName + (force ? " 已强制删除" : " 已删除"), "#4caf50");
                    });
                } catch (Exception ex) {
                    log.error("删除 Pod 失败: {}", ex.getMessage());
                    Platform.runLater(() -> CommonUtils.showToast(treeView, "✗", "删除 Pod 失败: " + ex.getMessage(), "#E74C3C"));
                }
            });
        });
    }

    /** 右键查看选中 Pod 的性能监控 */
    private void onPodMonitor() {
        TreeItem<String> selected = treeView.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getParent() == null || selected.getParent().getParent() == null) return;

        String podName = selected.getValue();
        String namespace = selected.getParent().getValue();

        PodMonitorDialog.show(namespace, podName);
    }

    // ==================== 自定义 TreeCell ====================

    private static final double INDENT_PER_LEVEL = 14;
    private static final double BOX_SIZE = 14;
    private static final double LEAF_INDENT = 6;
    private static final Color BOX_BORDER = Color.web("#9EAAB6");
    private static final Color BOX_FILL = Color.web("#F5F7FA");
    private static final Color BOX_HOVER_FILL = Color.web("#E8F0FE");
    private static final Color SYMBOL_COLOR = Color.web("#5C6B7A");
    private static final Color ACTIVE_POD_INDICATOR = Color.web("#326CE5");

    /**
     * 自定义树单元格，提供：
     * - 方框 +/- 展开折叠图标
     * - 虚线连接线（层级之间的竖线和横线）
     * - 层级字体差异（集群加粗、namespace 普通、pod 稍小）
     * - 状态圆点（从 TreeItem.userData 懒创建，避免搜索过滤时大量创建 Circle）
     * - 状态圆点带 Tooltip
     */
    class StyledTreeCell extends TreeCell<String> {
        private final HBox content;
        private final Label label;
        private final HBox indentBox;
        private final Rectangle activeIndicator;
        /** 缓存的状态圆点，复用避免每次 updateItem 都 new */
        private Circle cachedStatusDot;

        // 复用的缩进占位节点池，避免 updateItem 中反复 new Region
        private final List<Region> spacerPool = new ArrayList<>();
        // 复用的展开框
        private StackPane cachedExpandBox;
        private Rectangle cachedExpandBg;
        private Line cachedExpandH;
        private Line cachedExpandV;
        // 当前展开框绑定的 TreeItem，避免重复设置监听器
        private TreeItem<String> expandBoxBoundItem;

        StyledTreeCell() {
            label = new Label();
            label.setGraphicTextGap(4);

            indentBox = new HBox();
            indentBox.setAlignment(Pos.CENTER_LEFT);

            // 活跃 Pod 左侧蓝色竖条指示器
            activeIndicator = new Rectangle(3, 16);
            activeIndicator.setFill(ACTIVE_POD_INDICATOR);
            activeIndicator.setArcWidth(1.5);
            activeIndicator.setArcHeight(1.5);
            activeIndicator.setVisible(false);

            content = new HBox(0);
            content.setAlignment(Pos.CENTER_LEFT);
            content.setMaxWidth(Region.USE_PREF_SIZE);
            content.getChildren().addAll(activeIndicator, indentBox, label);

            // 监听选中状态变化，动态更新字体颜色
            selectedProperty().addListener((obs, wasSelected, isNowSelected) -> {
                String currentStyle = label.getStyle();
                if (isNowSelected) {
                    currentStyle = currentStyle.replaceAll("-fx-text-fill: #[A-Fa-f0-9]+;", "-fx-text-fill: #FFFFFF;");
                } else {
                    // 恢复原始颜色
                    TreeItem<String> treeItem = getTreeItem();
                    if (treeItem != null) {
                        int level = getTreeItemLevel(treeItem);
                        String color = level == 0 ? "#2C3E50" : level == 1 ? "#34495E" : "#555";
                        currentStyle = currentStyle.replaceAll("-fx-text-fill: #[A-Fa-f0-9]+;", "-fx-text-fill: " + color + ";");
                    }
                }
                label.setStyle(currentStyle);
            });
        }

        /** 从池中获取指定索引的 spacer，不够则创建。确保宽度恢复为 INDENT_PER_LEVEL */
        private Region getSpacer(int index) {
            if (index < spacerPool.size()) {
                Region spacer = spacerPool.get(index);
                spacer.setPrefWidth(INDENT_PER_LEVEL);
                spacer.setMinWidth(INDENT_PER_LEVEL);
                return spacer;
            }
            // 补齐池
            for (int i = spacerPool.size(); i <= index; i++) {
                Region spacer = new Region();
                spacer.setPrefWidth(INDENT_PER_LEVEL);
                spacer.setMinWidth(INDENT_PER_LEVEL);
                spacerPool.add(spacer);
            }
            return spacerPool.get(index);
        }

        /** 获取或创建复用的展开框 */
        private StackPane getOrCreateExpandBox(TreeItem<String> treeItem) {
            if (cachedExpandBox == null) {
                cachedExpandBg = new Rectangle(BOX_SIZE, BOX_SIZE);
                cachedExpandBg.setArcWidth(3);
                cachedExpandBg.setArcHeight(3);
                cachedExpandBg.setFill(BOX_FILL);
                cachedExpandBg.setStroke(BOX_BORDER);
                cachedExpandBg.setStrokeWidth(1);

                cachedExpandH = new Line(3, BOX_SIZE / 2, BOX_SIZE - 3, BOX_SIZE / 2);
                cachedExpandH.setStroke(SYMBOL_COLOR);
                cachedExpandH.setStrokeWidth(1.5);

                cachedExpandV = new Line(BOX_SIZE / 2, 3, BOX_SIZE / 2, BOX_SIZE - 3);
                cachedExpandV.setStroke(SYMBOL_COLOR);
                cachedExpandV.setStrokeWidth(1.5);

                cachedExpandBox = new StackPane(cachedExpandBg, cachedExpandH, cachedExpandV);
                cachedExpandBox.setStyle("-fx-cursor: hand;");

                // hover 效果
                cachedExpandBox.setOnMouseEntered(e -> cachedExpandBg.setFill(BOX_HOVER_FILL));
                cachedExpandBox.setOnMouseExited(e -> cachedExpandBg.setFill(BOX_FILL));

                // 点击展开/折叠
                cachedExpandBox.setOnMouseClicked(e -> {
                    TreeItem<String> bound = expandBoxBoundItem;
                    if (bound == null) return;
                    boolean wasExpanded = bound.isExpanded();
                    bound.setExpanded(!wasExpanded);
                    String path = getTreeItemPath(bound);
                    if (path != null) {
                        if (wasExpanded) {
                            manuallyExpandedPaths.remove(path);
                        } else {
                            manuallyExpandedPaths.add(path);
                        }
                    }
                    e.consume();
                });
            }

            // 绑定到当前 TreeItem
            expandBoxBoundItem = treeItem;

            // 根据展开状态显示/隐藏竖线
            cachedExpandV.setVisible(!treeItem.isExpanded());
            cachedExpandBg.setFill(BOX_FILL);

            return cachedExpandBox;
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);

            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                setTooltip(null);
                expandBoxBoundItem = null;
                return;
            }

            TreeItem<String> treeItem = getTreeItem();
            int level = getTreeItemLevel(treeItem);

            indentBox.getChildren().clear();

            // 绘制缩进：复用 spacer 池
            if (!treeItem.isLeaf()) {
                for (int i = 0; i < level; i++) {
                    if (i < level - 1) {
                        indentBox.getChildren().add(getSpacer(i));
                    } else {
                        indentBox.getChildren().add(getOrCreateExpandBox(treeItem));
                    }
                }
            } else {
                // 叶子节点：缩进对齐到父级文字下方
                Region leafSpacer = getSpacer(0);
                leafSpacer.setPrefWidth(LEAF_INDENT);
                leafSpacer.setMinWidth(LEAF_INDENT);
                indentBox.getChildren().add(leafSpacer);
            }

            // 根节点：加减框
            if (level == 0 && !treeItem.isLeaf()) {
                indentBox.getChildren().add(getOrCreateExpandBox(treeItem));
            }

            // 设置 graphic（状态圆点）：从附加数据懒创建，复用 Circle 对象
            Object userData = CommonUtils.getTreeItemData(treeItem);
            if (userData instanceof String phase) {
                PodStatus status = PodStatus.fromPhase(phase);
                if (cachedStatusDot == null) {
                    cachedStatusDot = new Circle(5);
                }
                cachedStatusDot.setFill(Color.web(status.getColorHex()));
                label.setGraphic(cachedStatusDot);
            } else {
                label.setGraphic(null);
            }

            // 判断是否为活跃 Pod
            boolean isActivePod = false;
            if (activePodPath != null && treeItem.getParent() != null
                    && treeItem.getParent().getParent() != null) {
                String itemPath = getTreeItemPath(treeItem);
                isActivePod = activePodPath.equals(itemPath);
            }
            activeIndicator.setVisible(isActivePod);

            // 层级字体差异，活跃 Pod 加粗显示
            boolean selected = isSelected();
            String style;
            String fontWeight = isActivePod && !selected ? "bold" : null;
            if (level == 0) {
                style = "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + (selected ? "#FFFFFF" : "#2C3E50") + ";";
            } else if (level == 1) {
                style = "-fx-font-size: 13px; " + (fontWeight != null ? "-fx-font-weight: " + fontWeight + "; " : "") + "-fx-text-fill: " + (selected ? "#FFFFFF" : "#34495E") + ";";
            } else {
                style = "-fx-font-size: 13px; " + (fontWeight != null ? "-fx-font-weight: " + fontWeight + "; " : "") + "-fx-text-fill: " + (selected ? "#FFFFFF" : (isActivePod ? "#326CE5" : "#555")) + ";";
            }
            label.setStyle(style);
            label.setText(item);

            // Pod 节点显示状态 Tooltip
            if (CommonUtils.getTreeItemData(treeItem) instanceof String phase) {
                Tooltip tooltip = new Tooltip("状态: " + phase);
                Tooltip.install(label, tooltip);
            }

            setGraphic(content);
            setText(null);
        }

        static int getTreeItemLevel(TreeItem<?> item) {
            int level = 0;
            TreeItem<?> parent = item.getParent();
            while (parent != null) {
                level++;
                parent = parent.getParent();
            }
            return level;
        }
    }
}
