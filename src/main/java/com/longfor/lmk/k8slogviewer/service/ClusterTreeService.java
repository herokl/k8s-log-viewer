package com.longfor.lmk.k8slogviewer.service;

import com.longfor.lmk.k8slogviewer.config.K8sClientManager;
import com.longfor.lmk.k8slogviewer.utils.CommonUtils;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1Pod;
import javafx.application.Platform;
import javafx.scene.control.TreeItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            namespaces.parallelStream().forEach(ns -> loadPods(ns, root, api));
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
                .map(m -> m.getName())
                .orElse("unknown");
        TreeItem<String> nsItem = new TreeItem<>(nsName);
        try {
            List<V1Pod> pods = api.listNamespacedPod(
                    nsName, null, null, null, null, null, null, null, null, null
            ).getItems();
            if (pods != null) {
                for (V1Pod pod : pods) {
                    Optional.ofNullable(pod.getMetadata())
                            .map(m -> m.getName())
                            .ifPresent(name -> nsItem.getChildren().add(new TreeItem<>(name)));
                }
            }
        } catch (ApiException e) {
            log.info("获取命名空间[{}] Pod 失败: {}", nsName, e.getResponseBody());
        }
        if (!nsItem.getChildren().isEmpty()) {
            root.getChildren().add(nsItem);
        }
    }
}
