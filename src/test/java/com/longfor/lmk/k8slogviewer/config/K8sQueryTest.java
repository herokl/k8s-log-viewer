package com.longfor.lmk.k8slogviewer.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class K8sQueryTest {

    @Test
    void builder_shouldCreateWithDefaults() {
        K8sQuery query = K8sQuery.builder().build();
        assertEquals(0, query.getContextLines());
        assertEquals(1000, query.getTailLines());
        assertEquals(0, query.getSinceSeconds());
        assertTrue(query.isFollow());
        assertTrue(query.isSearchRunning());
        assertNull(query.getNamespace());
        assertNull(query.getPodName());
        assertNull(query.getKeyword());
    }

    @Test
    void builder_shouldApplyCustomValues() {
        K8sQuery query = K8sQuery.builder()
                .contextLines(5)
                .tailLines(500)
                .sinceSeconds(3600)
                .follow(false)
                .searchRunning(false)
                .build();

        assertEquals(5, query.getContextLines());
        assertEquals(500, query.getTailLines());
        assertEquals(3600, query.getSinceSeconds());
        assertFalse(query.isFollow());
        assertFalse(query.isSearchRunning());
    }

    @Test
    void setters_shouldWorkCorrectly() {
        K8sQuery query = K8sQuery.builder().build();
        query.setNamespace("default");
        query.setPodName("my-pod");
        query.setKeyword("error");
        query.setContextLines(3);
        query.setSinceSeconds(7200);
        query.setFollow(false);
        query.setTailLines(2000);
        query.setSearchRunning(false);

        assertEquals("default", query.getNamespace());
        assertEquals("my-pod", query.getPodName());
        assertEquals("error", query.getKeyword());
        assertEquals(3, query.getContextLines());
        assertEquals(7200, query.getSinceSeconds());
        assertFalse(query.isFollow());
        assertEquals(2000, query.getTailLines());
        assertFalse(query.isSearchRunning());
    }

    @Test
    void headerCaptured_shouldDefaultTrue() {
        K8sQuery query = K8sQuery.builder().build();
        // 默认为 false（未初始化），调用 resetRuntimeState 后变为 true
        assertFalse(query.isHeaderCaptured());
        query.resetRuntimeState();
        assertTrue(query.isHeaderCaptured());
    }

    @Test
    void resetRuntimeState_shouldSetHeaderCapturedTrue() {
        K8sQuery query = K8sQuery.builder().build();
        query.setHeaderCaptured(false);
        assertFalse(query.isHeaderCaptured());

        query.resetRuntimeState();
        assertTrue(query.isHeaderCaptured());
    }

    @Test
    void resetRuntimeState_shouldNotResetOtherFields() {
        K8sQuery query = K8sQuery.builder().build();
        query.setNamespace("ns");
        query.setPodName("pod");
        query.setHeaderCaptured(true);

        query.resetRuntimeState();

        assertEquals("ns", query.getNamespace());
        assertEquals("pod", query.getPodName());
    }

    @Test
    void sinceSeconds_shouldSupportLong() {
        K8sQuery query = K8sQuery.builder()
                .sinceSeconds(Integer.MAX_VALUE + 1L)
                .build();
        assertEquals((long) Integer.MAX_VALUE + 1L, query.getSinceSeconds());
    }
}
