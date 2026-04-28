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
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(200));

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
                if (clicked != null) {
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

            TreeItem<String> prevSelected = treeView.getSelectionModel().getSelectedItem();
            String selectedPath = getTreeItemPath(prevSelected);

            if (hadFilter && !hasFilter) {
                collapseAutoExpanded();
            }

            applyTreeFilterFromSearch();

            if (selectedPath != null) {
                TreeItem<String> target = findTreeItemByPath(treeView.getRoot(), selectedPath);
                if (target != null) {
                    treeView.getSelectionModel().select(target);
                }
            }

            prevSearchText = text;
        });
        searchField.textProperty().addListener((obs, oldVal, newVal) -> searchDebounce.playFromStart());

        // 状态筛选下拉
        initStatusFilterDots();

        // 首次加载
        refreshTree();

        // 根据配置决定是否自动刷新容器树
        if (AppPreferences.isTreeAutoRefresh()) {
            startAutoRefresh();
        }
    }

    // ==================== 树刷新与过滤 ====================

    /** 搜索清空时，折叠所有非手动展开的节点 */
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
        autoRefreshExecutor = ExecutorManager.newSingleThreadScheduled("k8s-tree-refresh-");
        autoRefreshExecutor.scheduleAtFixedRate(() -> {
            clusterTreeService.clearCache();
            TreeItem<String> newRoot = clusterTreeService.getRootItem();

            Platform.runLater(() -> {
                TreeItem<String> prevSelected = treeView.getSelectionModel().getSelectedItem();
                String selectedPath = getTreeItemPath(prevSelected);

                applyTreeFilter(newRoot);

                if (selectedPath != null) {
                    TreeItem<String> target = findTreeItemByPath(treeView.getRoot(), selectedPath);
                    if (target != null) {
                        treeView.getSelectionModel().select(target);
                    }
                }
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
        TreeItem<String> rootItem = clusterTreeService.getRootItem();
        applyTreeFilter(rootItem);
    }

    /** 应用搜索关键字 + 状态筛选（搜索场景：直接 setRoot，不做 mergeChildren） */
    private void applyTreeFilterFromSearch() {
        String textFilter = searchField.getText();
        boolean filterByStatus = currentStatusFilter != null;

        TreeItem<String> result = clusterTreeService.getRootItem();
        if (filterByStatus) {
            result = filterTreeByStatus(result, currentStatusFilter);
        }
        if (textFilter != null && !textFilter.isEmpty()) {
            result = CommonUtils.filterTree(result, textFilter);
        }

        if (result == null) {
            treeView.setRoot(null);
            return;
        }

        treeView.setRoot(result);
        if (textFilter == null || textFilter.isEmpty()) {
            expandManuallyExpanded(result);
        }
    }

    /** 应用搜索关键字 + 状态筛选 */
    public void applyTreeFilter() {
        applyTreeFilter(clusterTreeService.getRootItem());
    }

    public void applyTreeFilter(TreeItem<String> rootItem) {
        String textFilter = searchField.getText();
        boolean filterByStatus = currentStatusFilter != null;

        TreeItem<String> result = rootItem;
        if (filterByStatus) {
            result = filterTreeByStatus(result, currentStatusFilter);
        }
        if (textFilter != null && !textFilter.isEmpty()) {
            result = CommonUtils.filterTree(result, textFilter);
        }

        if (result == null) {
            treeView.setRoot(null);
            return;
        }

        // 增量合并到现有显示树，避免整棵树替换导致的闪烁
        TreeItem<String> currentRoot = treeView.getRoot();
        if (currentRoot != null && Objects.equals(currentRoot.getValue(), result.getValue())) {
            mergeChildren(currentRoot, result);
        } else {
            treeView.setRoot(result);
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
                // 已有节点：递归合并子节点，更新 graphic（状态圆点）
                mergeChildren(tgtChild, srcChild);
                if (srcChild.getGraphic() != null) {
                    tgtChild.setGraphic(srcChild.getGraphic());
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
        if (node.isLeaf() && node.getGraphic() != null) {
            String phase = (String) node.getGraphic().getUserData();
            return status.equals(phase) ? node : null;
        }
        TreeItem<String> newParent = new TreeItem<>(node.getValue());
        newParent.setExpanded(node.isExpanded());
        for (TreeItem<String> child : node.getChildren()) {
            TreeItem<String> filtered = filterTreeByStatus(child, status);
            if (filtered != null) {
                newParent.getChildren().add(filtered);
            }
        }
        return newParent.getChildren().isEmpty() ? null : newParent;
    }

    // ==================== 状态筛选 ComboBox ====================

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
                    Circle dot = new Circle(5);
                    if ("All".equals(item)) {
                        dot.setFill(Color.TRANSPARENT);
                        dot.setStroke(Color.web(statusColors.get(item)));
                        dot.setStrokeWidth(1.5);
                    } else {
                        dot.setFill(Color.web(statusColors.get(item)));
                    }
                    setGraphic(dot);
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
                    Circle dot = new Circle(5);
                    if ("All".equals(item)) {
                        dot.setFill(Color.TRANSPARENT);
                        dot.setStroke(Color.web(statusColors.get(item)));
                        dot.setStrokeWidth(1.5);
                    } else {
                        dot.setFill(Color.web(statusColors.get(item)));
                    }
                    setGraphic(dot);
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

    // ==================== 右键操作 ====================

    /** 右键删除选中的 Pod */
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
                                refreshTree();
                                CommonUtils.showAlert("提示", "Pod " + podName + " 已删除");
                            });
                        } catch (Exception ex) {
                            log.error("删除 Pod 失败: {}", ex.getMessage());
                            Platform.runLater(() -> CommonUtils.showAlert("错误", "删除 Pod 失败: " + ex.getMessage()));
                        }
                    });
                }, null);
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
     * - 状态圆点带 Tooltip
     */
    private class StyledTreeCell extends TreeCell<String> {
        private final HBox content;
        private final Label label;
        private final HBox indentBox;
        private final Rectangle activeIndicator;

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

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);

            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                setTooltip(null);
                return;
            }

            TreeItem<String> treeItem = getTreeItem();
            int level = getTreeItemLevel(treeItem);

            indentBox.getChildren().clear();

            // 绘制缩进
            if (!treeItem.isLeaf()) {
                for (int i = 0; i < level; i++) {
                    if (i < level - 1) {
                        // 上层：空白占位
                        Region spacer = new Region();
                        spacer.setPrefWidth(INDENT_PER_LEVEL);
                        spacer.setMinWidth(INDENT_PER_LEVEL);
                        indentBox.getChildren().add(spacer);
                    } else {
                        // 当前层：加减框
                        StackPane expandBox = createExpandBox(treeItem);
                        indentBox.getChildren().add(expandBox);
                    }
                }
            } else {
                // 叶子节点：缩进对齐到父级文字下方
                Region spacer = new Region();
                spacer.setPrefWidth(LEAF_INDENT);
                spacer.setMinWidth(LEAF_INDENT);
                indentBox.getChildren().add(spacer);
            }

            // 根节点：加减框
            if (level == 0 && !treeItem.isLeaf()) {
                StackPane expandBox = createExpandBox(treeItem);
                indentBox.getChildren().add(expandBox);
            }

            // 设置 graphic（状态圆点）
            label.setGraphic(treeItem.getGraphic());

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
            if (treeItem.getGraphic() instanceof Circle circle && circle.getUserData() instanceof String phase) {
                Tooltip tooltip = new Tooltip("状态: " + phase);
                Tooltip.install(label, tooltip);
            }

            setGraphic(content);
            setText(null);
        }

        /** 创建方框加减图标 */
        private StackPane createExpandBox(TreeItem<String> treeItem) {
            Rectangle bg = new Rectangle(BOX_SIZE, BOX_SIZE);
            bg.setArcWidth(3);
            bg.setArcHeight(3);
            bg.setFill(BOX_FILL);
            bg.setStroke(BOX_BORDER);
            bg.setStrokeWidth(1);

            // + 或 - 符号
            Line h = new Line(3, BOX_SIZE / 2, BOX_SIZE - 3, BOX_SIZE / 2);
            h.setStroke(SYMBOL_COLOR);
            h.setStrokeWidth(1.5);

            StackPane box;
            if (treeItem.isExpanded()) {
                box = new StackPane(bg, h);
            } else {
                Line v = new Line(BOX_SIZE / 2, 3, BOX_SIZE / 2, BOX_SIZE - 3);
                v.setStroke(SYMBOL_COLOR);
                v.setStrokeWidth(1.5);
                box = new StackPane(bg, h, v);
            }

            box.setOnMouseClicked(e -> {
                boolean wasExpanded = treeItem.isExpanded();
                treeItem.setExpanded(!wasExpanded);
                String path = getTreeItemPath(treeItem);
                if (path != null) {
                    if (wasExpanded) {
                        manuallyExpandedPaths.remove(path);
                    } else {
                        manuallyExpandedPaths.add(path);
                    }
                }
                e.consume();
            });
            box.setStyle("-fx-cursor: hand;");

            // hover 效果
            box.setOnMouseEntered(e -> bg.setFill(BOX_HOVER_FILL));
            box.setOnMouseExited(e -> bg.setFill(BOX_FILL));

            return box;
        }

        private static int getTreeItemLevel(TreeItem<?> item) {
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
