package com.longfor.lmk.k8slogviewer.utils;

import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommonUtilsTest {

    private TreeItem<String> buildTree() {
        TreeItem<String> root = new TreeItem<>("cluster");
        TreeItem<String> ns1 = new TreeItem<>("namespace-1");
        TreeItem<String> ns2 = new TreeItem<>("namespace-2");

        ns1.getChildren().addAll(
                new TreeItem<>("pod-a"),
                new TreeItem<>("pod-b-service"),
                new TreeItem<>("pod-c")
        );
        ns2.getChildren().addAll(
                new TreeItem<>("pod-x"),
                new TreeItem<>("pod-y-deploy")
        );
        root.getChildren().addAll(ns1, ns2);
        return root;
    }

    @Test
    void filterTree_shouldMatchExactPodName() {
        TreeItem<String> root = buildTree();
        TreeItem<String> filtered = CommonUtils.filterTree(root, "pod-a");

        assertNotNull(filtered);
        assertEquals(1, filtered.getChildren().size());
        TreeItem<String> ns = filtered.getChildren().get(0);
        assertEquals("namespace-1", ns.getValue());
        assertEquals(1, ns.getChildren().size());
        assertEquals("pod-a", ns.getChildren().get(0).getValue());
    }

    @Test
    void filterTree_shouldMatchPartialName() {
        TreeItem<String> root = buildTree();
        TreeItem<String> filtered = CommonUtils.filterTree(root, "service");

        assertNotNull(filtered);
        assertEquals(1, filtered.getChildren().size());
        TreeItem<String> ns = filtered.getChildren().get(0);
        assertEquals(1, ns.getChildren().size());
        assertEquals("pod-b-service", ns.getChildren().get(0).getValue());
    }

    @Test
    void filterTree_shouldMatchMultiplePodsAcrossNamespaces() {
        TreeItem<String> root = buildTree();
        TreeItem<String> filtered = CommonUtils.filterTree(root, "deploy");

        assertNotNull(filtered);
        // namespace-2 包含 pod-y-deploy
        assertEquals(1, filtered.getChildren().size());
    }

    @Test
    void filterTree_shouldMatchNamespace() {
        TreeItem<String> root = buildTree();
        TreeItem<String> filtered = CommonUtils.filterTree(root, "namespace-2");

        assertNotNull(filtered);
        // namespace-2 自身匹配 "namespace-2"，但子节点 pod-x/pod-y-deploy 不匹配过滤词
        // 所以返回的 namespace-2 节点无子节点（这是 filterTree 的实际行为）
        assertEquals(1, filtered.getChildren().size());
        assertEquals("namespace-2", filtered.getChildren().get(0).getValue());
    }

    @Test
    void filterTree_shouldReturnNullForNoMatch() {
        TreeItem<String> root = buildTree();
        TreeItem<String> filtered = CommonUtils.filterTree(root, "nonexistent");

        assertNull(filtered);
    }

    @Test
    void filterTree_shouldBeCaseInsensitive() {
        TreeItem<String> root = buildTree();
        TreeItem<String> filtered = CommonUtils.filterTree(root, "POD-A");

        assertNotNull(filtered);
        assertEquals(1, filtered.getChildren().get(0).getChildren().size());
    }

    @Test
    void filterTree_shouldHandleEmptyFilter() {
        TreeItem<String> root = buildTree();
        TreeItem<String> filtered = CommonUtils.filterTree(root, "");

        // 空字符串时，所有子节点都包含空字符串，所以整个树应该保留
        assertNotNull(filtered);
        assertEquals(2, filtered.getChildren().size());
    }
}
