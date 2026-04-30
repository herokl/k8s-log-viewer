package com.longfor.lmk.k8slogviewer.service;

import com.longfor.lmk.k8slogviewer.config.AppPreferences;
import com.longfor.lmk.k8slogviewer.config.K8sClientManager;
import com.longfor.lmk.k8slogviewer.config.KubeConfigProfile;
import com.longfor.lmk.k8slogviewer.model.PodStatus;
import com.longfor.lmk.k8slogviewer.utils.CommonUtils;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodStatus;
import javafx.scene.control.TreeItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集群树视图数据服务。
 *
 * - 缓存 key: profileName#namespace，避免重复 API 调用
 * - 支持全量加载和增量刷新，增量刷新只更新选中命名空间的 Pod 数据
 */
public class ClusterTreeService {

    private static final Logger log = LoggerFactory.getLogger(ClusterTreeService.class);

    /** 缓存 key: profileName#namespace → 该命名空间的 Pod 子节点 */
    private final ConcurrentHashMap<String, List<TreeItem<String>>> nsCache = new ConcurrentHashMap<>();

    /** 上一次加载的树根节点 */
    private volatile TreeItem<String> lastLoadedRoot;

    /** 当前正在加载的命名空间列表 */
    private volatile List<String> lastRequestedNamespaces;

    /**
     * 加载命名空间树（优先使用缓存），用于初始展示和命名空间过滤。
     */
    public synchronized TreeItem<String> loadNamespaceSkeleton(List<String> namespaces) {
        if (namespaces == null || namespaces.isEmpty()) return null;
        String profileName = getActiveProfileName();
        if (profileName == null) return null;

        lastRequestedNamespaces = new ArrayList<>(namespaces);
        KubeConfigProfile activeProfile = AppPreferences.getActiveProfile();
        String rootName = activeProfile != null ? activeProfile.getName() : "集群";
        TreeItem<String> root = new TreeItem<>(rootName);
        root.setExpanded(true);

        try {
            CoreV1Api api = K8sClientManager.getCoreV1Api();
            for (String nsName : namespaces) {
                String cacheKey = profileName + "#" + nsName;
                // 检查是否有已缓存的 Pod 列表
                List<TreeItem<String>> cachedChildren = nsCache.get(cacheKey);
                // 无缓存且非空缓存的命名空间才加入树（无 Pod 的命名空间剔除）
                if (cachedChildren == null || !cachedChildren.isEmpty()) {
                    TreeItem<String> nsItem = new TreeItem<>(nsName);
                    if (cachedChildren != null) {
                        nsItem.getChildren().addAll(cachedChildren);
                        nsItem.setExpanded(false);
                    }
                    root.getChildren().add(nsItem);
                }
            }
        } catch (Exception e) {
            log.error("加载集群数据异常", e);
        }

        lastLoadedRoot = root;
        return root;
    }

    /** 兼容旧调用名 */
    public TreeItem<String> loadForNamespaces(List<String> namespaces) {
        return loadNamespaceSkeleton(namespaces);
    }

    /** 同步获取指定命名空间的 Pod 列表 */
    public List<TreeItem<String>> fetchPodItems(String nsName) {
        try {
            CoreV1Api api = K8sClientManager.getCoreV1Api();
            var pods = api.listNamespacedPod(
                    nsName, null, null, null, null, null, null, null, null, null).getItems();
            List<TreeItem<String>> items = new ArrayList<>();
            if (pods != null) {
                pods.stream()
                        .sorted(Comparator
                                .comparing((V1Pod p) -> {
                                    String phase = Optional.ofNullable(p.getStatus())
                                            .map(V1PodStatus::getPhase).orElse("Unknown");
                                    return statusOrder(phase);
                                })
                                .thenComparing(p -> Optional.ofNullable(p.getMetadata())
                                        .map(V1ObjectMeta::getName).orElse("zzz"), String.CASE_INSENSITIVE_ORDER))
                        .forEach(pod -> {
                            String name = Optional.ofNullable(pod.getMetadata())
                                    .map(V1ObjectMeta::getName).orElse(null);
                            if (name == null) return;
                            String phase = Optional.ofNullable(pod.getStatus())
                                    .map(V1PodStatus::getPhase).orElse("Unknown");
                            TreeItem<String> podItem = new TreeItem<>(name);
                            CommonUtils.putTreeItemData(podItem, phase);
                            items.add(podItem);
                        });
            }
            return items;
        } catch (ApiException e) {
            log.info("获取命名空间[{}] Pod 失败: code={}, message={}, body={}",
                    nsName, e.getCode(), e.getMessage(), e.getResponseBody());
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("获取命名空间[{}] Pod 异常: {}", nsName, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public TreeItem<String> getCachedRoot() {
        return lastLoadedRoot;
    }

    /**
     * 增量刷新（纯数据采集，不修改已有 TreeItem）。
     * <p>
     * 只在后台线程做 K8s API 调用并返回原始 Pod 数据，
     * TreeItem 的变更必须由调用方在 JavaFX Application Thread 上执行。
     *
     * @return 每个命名空间对应的最新 Pod 子节点列表，无选中命名空间时返回 null
     */
    public synchronized Map<String, List<TreeItem<String>>> incrementalFetchPods() {
        List<String> nsList = lastRequestedNamespaces;
        if (nsList == null || nsList.isEmpty()) return null;
        String profileName = getActiveProfileName();
        if (profileName == null) return null;

        try {
            CoreV1Api api = K8sClientManager.getCoreV1Api();

            Map<String, List<TreeItem<String>>> result = new LinkedHashMap<>();
            for (String nsName : nsList) {
                String cacheKey = profileName + "#" + nsName;
                List<TreeItem<String>> children = fetchPodItems(nsName);
                result.put(nsName, children);
                nsCache.put(cacheKey, children);
            }
            return result;
        } catch (Exception e) {
            log.error("增量刷新数据异常", e);
            return null;
        }
    }

    /**
     * 将增量获取的 Pod 数据合并到现有树中（必须在 FX 线程调用）。
     *
     * @param podData   命名空间 → Pod 列表映射
     * @return 更新后的根节点（与 lastLoadedRoot 同一对象）
     */
    public synchronized TreeItem<String> applyIncrementalUpdate(Map<String, List<TreeItem<String>>> podData) {
        if (podData == null || podData.isEmpty()) return lastLoadedRoot;

        TreeItem<String> root = lastLoadedRoot;
        if (root == null) return forceReloadFull();

        // 构建现有命名空间节点的 name→item 索引
        Map<String, TreeItem<String>> nsMap = new LinkedHashMap<>();
        for (TreeItem<String> child : root.getChildren()) {
            nsMap.put(child.getValue(), child);
        }

        List<TreeItem<String>> updatedNsChildren = new ArrayList<>();
        for (var entry : podData.entrySet()) {
            String nsName = entry.getKey();
            List<TreeItem<String>> children = entry.getValue();

            if (children.isEmpty() && !nsMap.containsKey(nsName)) continue;

            TreeItem<String> nsItem = nsMap.get(nsName);
            if (nsItem != null) {
                // 已存在的命名空间：增量替换子 Pod（保留展开状态）
                nsItem.getChildren().setAll(children);
                updatedNsChildren.add(nsItem);
            } else if (!children.isEmpty()) {
                // 新增的命名空间
                TreeItem<String> newNsItem = new TreeItem<>(nsName);
                newNsItem.getChildren().addAll(children);
                updatedNsChildren.add(newNsItem);
            }
        }

        root.getChildren().setAll(updatedNsChildren);
        return root;
    }

    /**
     * 强制重新加载所有选中命名空间的 Pod（含子节点，用于刷新/自动刷新/删除后刷新）。
     * 与 {@link #loadNamespaceSkeleton} 不同：此方法会同步加载每个 NS 的 Pod 数据。
     */
    public synchronized TreeItem<String> forceReloadFull() {
        List<String> nsList = lastRequestedNamespaces;
        if (nsList == null || nsList.isEmpty()) return null;
        String profileName = getActiveProfileName();
        if (profileName == null) return null;

        // 清除缓存后全量重载
        clearNsCache();
        KubeConfigProfile activeProfile = AppPreferences.getActiveProfile();
        String rootName = activeProfile != null ? activeProfile.getName() : "集群";
        TreeItem<String> root = new TreeItem<>(rootName);
        root.setExpanded(true);

        try {
            CoreV1Api api = K8sClientManager.getCoreV1Api();
            for (String nsName : nsList) {
                String cacheKey = profileName + "#" + nsName;
                List<TreeItem<String>> children = fetchPodItems(nsName);
                if (children.isEmpty()) continue; // 无 Pod 的命名空间不加入树
                nsCache.put(cacheKey, children);
                TreeItem<String> nsItem = new TreeItem<>(nsName);
                nsItem.getChildren().addAll(children);
                root.getChildren().add(nsItem);
            }
        } catch (Exception e) {
            log.error("强制重新加载数据异常", e);
        }

        lastLoadedRoot = root;
        return root;
    }

    /** 仅清除 Pod 缓存（不重建树），配合 forceReloadFull 使用 */
    public void clearNsCache() {
        String profileName = getActiveProfileName();
        if (profileName != null) {
            nsCache.keySet().removeIf(key -> key.startsWith(profileName + "#"));
        }
    }

    /** 状态排序权重 */
    private static int statusOrder(String phase) {
        return PodStatus.fromPhase(phase).getOrder();
    }

    private static String getActiveProfileName() {
        KubeConfigProfile profile = AppPreferences.getActiveProfile();
        return profile != null ? profile.getName() : null;
    }
}
