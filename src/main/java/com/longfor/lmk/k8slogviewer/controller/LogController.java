package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.util.AlertUtil;
import com.longfor.lmk.k8slogviewer.util.InstanceUtils;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1Pod;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 日志控制器工具类，采用单例模式，负责处理日志流、搜索和 Kubernetes API 交互
 */
@Slf4j
public class LogController {

    private CoreV1Api api; // Kubernetes API 客户端
    private final ExecutorService executor = Executors.newSingleThreadExecutor(); // 单线程执行器
    private boolean isPaused = false; // 日志流暂停状态
    private String currentNamespace; // 当前命名空间
    private String currentPod; // 当前 Pod
    private String currentContainer; // 当前容器
    private Consumer<String> logUpdater; // 日志更新回调


    /**
     * 初始化单例实例
     *
     * @param api        Kubernetes API 客户端
     * @param logUpdater 日志更新回调
     */
    public static void initialize(CoreV1Api api, Consumer<String> logUpdater) {
        LogController controller = InstanceUtils.getLogControllerInstance();
        controller.api = api;
        controller.logUpdater = logUpdater;
    }

    /**
     * 更新 Kubernetes API 客户端
     *
     * @param api 新的 API 客户端
     */
    public void updateApi(CoreV1Api api) {
        this.api = api;
    }

    /**
     * 填充导航树的命名空间列表，仅显示容器
     * @param navigationTree 导航树
     */
    public void populateNamespaces(TreeView<String> navigationTree) {
        log.info("开始加载导航树");
        TreeItem<String> root = new TreeItem<>("集群");
        try {
            List<V1Namespace> namespaces = api.listNamespace(null, null, null, null, null, null, null, null, null).getItems();
            for (V1Namespace ns : namespaces) {
                String nsName = null;
                if (ns.getMetadata() != null) {
                    nsName = ns.getMetadata().getName();
                }
                TreeItem<String> nsItem = new TreeItem<>(nsName);
                root.getChildren().add(nsItem);
                populatePods(nsItem, nsName);
            }
        } catch (Exception e) {
            log.error("加载命名空间失败: {}", e.getMessage());
            AlertUtil.showError("错误", "无法加载命名空间: " + e.getMessage());
        }
        navigationTree.setRoot(root);
        root.setExpanded(true);
    }

    /**
     * 填充指定命名空间下的 Pods
     * @param nsItem 命名空间树节点
     * @param namespace 命名空间名称
     */
    private void populatePods(TreeItem<String> nsItem, String namespace) {
        try {
            List<V1Pod> pods = api.listNamespacedPod(namespace, null, null, null, null, null, null, null, null, null).getItems();
            for (V1Pod pod : pods) {
                String podName = null;
                if (pod.getMetadata() != null) {
                    podName = pod.getMetadata().getName();
                }
                TreeItem<String> podItem = new TreeItem<>(podName);
                nsItem.getChildren().add(podItem);
                populateContainers(podItem, namespace, podName);
            }
        } catch (Exception e) {
            log.error("加载 Pods 失败: {}", e.getMessage());
            AlertUtil.showError("错误", "无法加载 Pods: " + e.getMessage());
        }
    }

    /**
     * 填充指定 Pod 下的容器
     *
     * @param podItem   Pod 树节点
     * @param namespace 命名空间名称
     * @param podName   Pod 名称
     */
    private void populateContainers(TreeItem<String> podItem, String namespace, String podName) {
        try {
            V1Pod pod = api.readNamespacedPod(podName, namespace, null, null, null);
            List<V1Container> containers = null;
            if (pod.getSpec() != null) {
                containers = pod.getSpec().getContainers();
            }
            if (containers != null) {
                for (V1Container container : containers) {
                    TreeItem<String> containerItem = new TreeItem<>(container.getName());
                    podItem.getChildren().add(containerItem);
                }
            }
        } catch (Exception e) {
            AlertUtil.showError("错误", "无法加载容器: " + e.getMessage());
        }
    }

    /**
     * 处理导航树选择事件
     * @param newValue 选中的树节点
     */
    public void handleTreeSelection(TreeItem<String> newValue) {
        if (newValue == null) return;
        TreeItem<String> parent = newValue.getParent();
        if (parent == null) return;

        log.info("导航树选择: {}", newValue.getValue());
        if (parent.getValue().equals("集群")) {
            currentNamespace = newValue.getValue();
            currentPod = null;
            currentContainer = null;
            logUpdater.accept("");
        } else if (parent.getParent() != null && parent.getParent().getValue().equals("集群")) {
            currentNamespace = parent.getValue();
            currentPod = newValue.getValue();
            currentContainer = null;
            logUpdater.accept("");
        } else {
            currentNamespace = parent.getParent().getValue();
            currentPod = parent.getValue();
            currentContainer = newValue.getValue();
            startLogStreaming();
        }
    }


    /**
     * 开始实时日志流传输
     */
    private void startLogStreaming() {
        if (currentNamespace == null || currentPod == null || currentContainer == null) {
            log.warn("无法启动日志流: namespace={}, pod={}, container={}",
                    currentNamespace, currentPod, currentContainer);
            return;
        }
        executor.submit(() -> {
            try {
                log.info("开始日志流: namespace={}, pod={}, container={}",
                        currentNamespace, currentPod, currentContainer);
                String logs = api.readNamespacedPodLog(currentPod, currentNamespace, currentContainer, null, null, null, null, null, null, null, null);
                BufferedReader reader = new BufferedReader(new StringReader(logs));
                String line;
                while ((line = reader.readLine()) != null && !isPaused) {
                    String finalLine = line;
                    javafx.application.Platform.runLater(() -> logUpdater.accept(finalLine));
                    // 可选：控制日志刷新频率，但不阻塞读取逻辑
                    Thread.sleep(100);
                }
            } catch (Exception e) {
                log.error("日志流失败: {}", e.getMessage());
                javafx.application.Platform.runLater(() -> AlertUtil.showError("错误", "无法获取日志: " + e.getMessage()));
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * 切换暂停/恢复状态
     *
     * @param pauseResumeButton 暂停/恢复按钮
     */
    public void togglePauseResume(Button pauseResumeButton) {
        isPaused = !isPaused;
        pauseResumeButton.setText(isPaused ? "恢复" : "暂停");
        pauseResumeButton.setStyle(isPaused ? "-fx-text-fill: white;" : "-fx-background-color: #2196F3; ");
        if (!isPaused) {
            startLogStreaming();
        }
        log.info("切换暂停/恢复状态: isPaused={}", isPaused);
    }

    /**
     * 执行日志搜索
     *
     * @param searchTerm    搜索关键词
     * @param searchMode    搜索模式
     * @param caseSensitive 是否区分大小写
     * @param logArea       日志显示区域
     */
    public void performSearch(String searchTerm, String searchMode, boolean caseSensitive, TextArea logArea) {
        if (searchTerm.isEmpty()) {
            log.warn("日志搜索关键词为空");
            return;
        }

        log.info("执行日志搜索: term={}, mode={}, caseSensitive={}", searchTerm, searchMode, caseSensitive);
        String logContent = logArea.getText();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(logContent))) {
            List<Integer> matchedLines = getIntegers(searchTerm, searchMode, caseSensitive, reader, lines);

            // 显示搜索结果及上下文
            StringBuilder result = new StringBuilder();
            for (int match : matchedLines) {
                int start = Math.max(0, match - 2);
                int end = Math.min(lines.size(), match + 3);
                if (!result.isEmpty()) {
                    result.append("\n--- (非连续日志) ---\n");
                }
                for (int i = start; i < end; i++) {
                    String displayLine = lines.get(i);
                    if (i == match) {
                        displayLine = ">> " + displayLine;
                    }
                    result.append(displayLine).append("\n");
                }
            }
            logArea.setText(result.toString());
            logArea.setScrollTop(0);
            log.info("日志搜索完成，匹配行数: {}", matchedLines.size());
        } catch (Exception e) {
            AlertUtil.showError("错误", "搜索失败: " + e.getMessage());
        }
    }

    private List<Integer> getIntegers(String searchTerm, String searchMode, boolean caseSensitive, BufferedReader reader, List<String> lines) throws IOException {
        String line;
        int lineNumber = 0;
        List<Integer> matchedLines = new ArrayList<>();

        while ((line = reader.readLine()) != null) {
            lines.add(line);
            boolean isMatch = false;
            switch (searchMode) {
                case "模糊匹配" ->
                        isMatch = caseSensitive ? line.contains(searchTerm) : line.toLowerCase().contains(searchTerm.toLowerCase());
                case "精确匹配" ->
                        isMatch = caseSensitive ? line.equals(searchTerm) : line.equalsIgnoreCase(searchTerm);
                case "正则表达式" -> {
                    Pattern pattern = caseSensitive ? Pattern.compile(searchTerm) : Pattern.compile(searchTerm, Pattern.CASE_INSENSITIVE);
                    isMatch = pattern.matcher(line).find();
                }
                default -> throw new IllegalArgumentException("无效的搜索模式: " + searchMode);
            }
            if (isMatch) {
                matchedLines.add(lineNumber);
            }
            lineNumber++;
        }
        return matchedLines;
    }

    /**
     * 搜索导航树节点
     * @param navigationTree 导航树
     * @param searchTerm 搜索关键词
     * @return 是否找到匹配节点
     */
    public boolean searchTreeNodes(TreeView<String> navigationTree, String searchTerm) {
        log.info("搜索导航树，关键词: {}", searchTerm);
        TreeItem<String> root = navigationTree.getRoot();
        if (root == null) {
            log.warn("导航树根节点为空");
            return false;
        }

        // 递归搜索节点
        TreeItem<String> matchedItem = findMatchingNode(root, searchTerm.toLowerCase());
        if (matchedItem != null) {
            // 展开所有父节点
            TreeItem<String> parent = matchedItem.getParent();
            while (parent != null) {
                parent.setExpanded(true);
                parent = parent.getParent();
            }
            // 选中匹配节点
            navigationTree.getSelectionModel().select(matchedItem);
            navigationTree.scrollTo(navigationTree.getRow(matchedItem));
            log.info("找到匹配节点: {}", matchedItem.getValue());
            return true;
        }
        log.info("未找到匹配节点: {}", searchTerm);
        return false;
    }

    /**
     * 递归查找匹配的树节点
     * @param node 当前节点
     * @param searchTerm 搜索关键词（小写）
     * @return 匹配的节点或 null
     */
    private TreeItem<String> findMatchingNode(TreeItem<String> node, String searchTerm) {
        if (node.getValue() != null && node.getValue().toLowerCase().contains(searchTerm)) {
            return node;
        }
        for (TreeItem<String> child : node.getChildren()) {
            TreeItem<String> match = findMatchingNode(child, searchTerm);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    /**
     * 关闭执行器
     */
    public void shutdown() {
        executor.shutdownNow();
    }
}