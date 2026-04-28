package com.longfor.lmk.k8slogviewer.model;

/**
 * Pod 状态枚举，集中管理状态名称、颜色和排序权重。
 * 消除 ClusterTreeService / TreeViewManager 中的硬编码映射。
 */
public enum PodStatus {

    RUNNING("Running", "#4CAF50", 0),
    PENDING("Pending", "#FF9800", 1),
    FAILED("Failed", "#F44336", 2),
    SUCCEEDED("Succeeded", "#2196F3", 3),
    UNKNOWN("Unknown", "#9E9E9E", 4);

    private final String phase;
    private final String colorHex;
    private final int order;

    PodStatus(String phase, String colorHex, int order) {
        this.phase = phase;
        this.colorHex = colorHex;
        this.order = order;
    }

    public String getPhase() { return phase; }
    public String getColorHex() { return colorHex; }
    public int getOrder() { return order; }

    /**
     * 根据 K8s Pod phase 字符串获取对应的 PodStatus。
     * @param phase K8s 返回的 phase 字符串，null 或无法识别时返回 UNKNOWN
     */
    public static PodStatus fromPhase(String phase) {
        if (phase == null) return UNKNOWN;
        for (PodStatus s : values()) {
            if (s.phase.equalsIgnoreCase(phase)) return s;
        }
        return UNKNOWN;
    }
}
