package com.longfor.lmk.k8slogviewer.service;

import com.longfor.lmk.k8slogviewer.config.K8sClientManager;
import com.longfor.lmk.k8slogviewer.model.PodStatus;
import com.longfor.lmk.k8slogviewer.utils.CommonUtils;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodStatus;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.TreeItem;
import javafx.scene.shape.Circle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 集群树视图数据服务，负责加载和缓存命名空间/Pod 层级结构。
 */
public class ClusterTreeService {

    private static final Logger log = LoggerFactory.getLogger(ClusterTreeService.class);
    private static final String ROOT_NAME = "集群";

    private volatile TreeItem<String> cachedRoot;

    /**
     * 获取集群树根节点，带缓存。
     * 调用 {@link #clearCache()} 可强制刷新。
     */
    public TreeItem<String> getRootItem() {
        TreeItem<String> root = cachedRoot;
        if (root != null) {
            return root;
        }
        return loadRootItem();
    }

    /**
     * 清除缓存，下次调用 getRootItem() 会重新加载
     */
    public void clearCache() {
        cachedRoot = null;
    }

    private synchronized TreeItem<String> loadRootItem() {
        // 双重检查
        if (cachedRoot != null) {
            return cachedRoot;
        }
        TreeItem<String> root = new TreeItem<>(ROOT_NAME);
        root.setExpanded(true);
        try {
            CoreV1Api api = K8sClientManager.getCoreV1Api();
            List<V1Namespace> namespaces = api.listNamespace(
                    null, null, null, null, null, null, null, null, null
            ).getItems();
            // 串行加载保证排序生效
            namespaces.stream()
                    .sorted(Comparator.comparing(ns -> Optional.ofNullable(ns.getMetadata())
                            .map(V1ObjectMeta::getName).orElse("zzz"), String.CASE_INSENSITIVE_ORDER))
                    .forEach(ns -> loadPods(ns, root, api));
        } catch (ApiException e) {
            Platform.runLater(() ->
                    CommonUtils.showAlert("错误", "无法加载 Kubernetes 数据: " + e.getResponseBody()));
        } catch (Exception e) {
            log.error("加载集群数据异常", e);
        }
        cachedRoot = root;
        return root;
    }

    private void loadPods(V1Namespace ns, TreeItem<String> root, CoreV1Api api) {
        String nsName = Optional.ofNullable(ns.getMetadata())
                .map(V1ObjectMeta::getName)
                .orElse("unknown");
        TreeItem<String> nsItem = new TreeItem<>(nsName);
        try {
            List<V1Pod> pods = api.listNamespacedPod(
                    nsName, null, null, null, null, null, null, null, null, null
            ).getItems();
            if (pods != null) {
                // 按 Pod 名称排序，Running 优先
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
                            Node dot = createStatusDot(phase);
                            podItem.setGraphic(dot);
                            nsItem.getChildren().add(podItem);
                        });
            }
        } catch (ApiException e) {
            log.info("获取命名空间[{}] Pod 失败: {}", nsName, e.getResponseBody());
        }
        if (!nsItem.getChildren().isEmpty()) {
            root.getChildren().add(nsItem);
        }
    }

    /** 状态排序权重：使用 PodStatus 枚举统一管理 */
    private static int statusOrder(String phase) {
        return PodStatus.fromPhase(phase).getOrder();
    }

    /** 根据 Pod 状态创建彩色圆点标识，phase 存入 userData 供筛选使用 */
    private static Node createStatusDot(String phase) {
        PodStatus status = PodStatus.fromPhase(phase);
        Circle dot = new Circle(5);
        dot.setStyle("-fx-fill: " + status.getColorHex() + ";");
        dot.setUserData(phase);
        return dot;
    }
}
