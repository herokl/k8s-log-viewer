package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.K8sClientManager;
import com.longfor.lmk.k8slogviewer.utils.ExecutorManager;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Pod;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pod 性能监控弹窗：使用实时折线图展示容器 CPU / 内存使用率。
 * 每 5 秒刷新一次，保留最近 60 个数据点（约 5 分钟历史）。
 */
public class PodMonitorDialog {

    private static final Logger log = LoggerFactory.getLogger(PodMonitorDialog.class);
    private static final int REFRESH_INTERVAL_S = 5;
    private static final int MAX_DATA_POINTS = 60;

    @FXML private TextField podNameLabel;
    @FXML private TextField statusLabel;
    @FXML private TextField nodeLabel;
    @FXML private TextField ipLabel;
    @FXML private TextField restartLabel;
    @FXML private VBox containerList;
    @FXML private Label hintLabel;

    private final String namespace;
    private final String podName;
    private Stage stage;
    private ScheduledExecutorService scheduler;

    // 每个容器的图表数据：containerName -> {cpuSeries, memSeries, tick}
    private final Map<String, ContainerChart> chartMap = new ConcurrentHashMap<>();

    // Pod 的容器资源规格（只在首次加载时获取）
    private Map<String, ContainerResources> resourceSpecs = new HashMap<>();

    private PodMonitorDialog(String namespace, String podName) {
        this.namespace = namespace;
        this.podName = podName;
    }

    public static void show(String namespace, String podName) {
        new PodMonitorDialog(namespace, podName).open();
    }

    private void open() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/longfor/lmk/k8slogviewer/pod_monitor_dialog.fxml"));
            loader.setController(this);
            VBox root = loader.load();

            stage = new Stage();
            stage.setTitle("性能监控 - " + namespace + "/" + podName);
            Scene scene = new Scene(root, 900, 400);
            scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
            stage.setScene(scene);
            stage.setOnCloseRequest(e -> stopRefresh());

            // Ctrl+C 复制当前所有容器使用率摘要到剪贴板
            KeyCodeCombination ctrlC = new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN);
            stage.getScene().setOnKeyPressed(e -> {
                if (ctrlC.match(e)) {
                    copyMonitorInfo();
                }
            });

            stage.show();

            refreshData();

            scheduler = ExecutorManager.newSingleThreadScheduled("pod-monitor-");
            scheduler.scheduleAtFixedRate(
                    () -> Platform.runLater(this::refreshData),
                    REFRESH_INTERVAL_S, REFRESH_INTERVAL_S, TimeUnit.SECONDS
            );
        } catch (IOException e) {
            log.error("加载监控弹窗 FXML 失败", e);
        }
    }

    private void stopRefresh() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private static final String COPYABLE_FIELD_STYLE =
            "-fx-background-color: transparent; -fx-background-insets: 0; -fx-border-color: transparent; " +
            "-fx-font-size: 12px; -fx-text-fill: #333; -fx-padding: 0;";

    private static final String POD_NAME_STYLE =
            "-fx-background-color: transparent; -fx-background-insets: 0; -fx-border-color: transparent; " +
            "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #326CE5; -fx-padding: 0;";

    private static final String STATUS_FIELD_STYLE =
            "-fx-background-color: #F0F4FF; -fx-background-insets: 0; -fx-border-color: transparent; " +
            "-fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 2 10; -fx-background-radius: 4;";

    private static final String DETAIL_FIELD_STYLE =
            "-fx-background-color: transparent; -fx-background-insets: 0; -fx-border-color: transparent; " +
            "-fx-font-size: 12px; -fx-text-fill: #555; -fx-padding: 0;";

    @FXML
    public void initialize() {
        podNameLabel.setText(podName);
        podNameLabel.setStyle(POD_NAME_STYLE);
        statusLabel.setStyle(STATUS_FIELD_STYLE);
        nodeLabel.setStyle(DETAIL_FIELD_STYLE);
        ipLabel.setStyle(DETAIL_FIELD_STYLE);
        restartLabel.setStyle(DETAIL_FIELD_STYLE);
    }

    private void refreshData() {
        ExecutorManager.submit(() -> {
            try {
                CoreV1Api api = K8sClientManager.getCoreV1Api();
                V1Pod pod = api.readNamespacedPod(podName, namespace, null, null, null);
                Map<String, Map<String, Quantity>> usageMap = MetricsFetcher.fetchPodMetrics(namespace, podName);

                Platform.runLater(() -> updateUI(pod, usageMap));
            } catch (ApiException e) {
                log.warn("获取 Pod 信息失败: {}", e.getResponseBody());
            } catch (Exception e) {
                log.warn("获取 Pod 信息异常", e);
            }
        });
    }

    private void updateUI(V1Pod pod, Map<String, Map<String, Quantity>> usageMap) {
        // 更新 Pod 基本信息（仅首次或状态变化时更新）
        String status = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
        String nodeName = pod.getSpec() != null && pod.getSpec().getNodeName() != null
                ? pod.getSpec().getNodeName() : "N/A";
        String podIP = pod.getStatus() != null && pod.getStatus().getPodIP() != null
                ? pod.getStatus().getPodIP() : "N/A";
        int restartCount = 0;
        if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
            restartCount = pod.getStatus().getContainerStatuses().stream()
                    .mapToInt(cs -> cs.getRestartCount() != null ? cs.getRestartCount() : 0).sum();
        }

        podNameLabel.setText(podName);
        statusLabel.setText(status);
        statusLabel.setStyle(STATUS_FIELD_STYLE + "-fx-text-fill: " + statusColor(status) + ";");
        nodeLabel.setText(nodeName);
        ipLabel.setText(podIP);
        restartLabel.setText(String.valueOf(restartCount));
        restartLabel.setStyle(DETAIL_FIELD_STYLE + (restartCount > 0 ? "-fx-text-fill: #E74C3C; -fx-font-weight: bold;" : ";"));

        // 首次加载容器规格
        if (resourceSpecs.isEmpty() && pod.getSpec() != null && pod.getSpec().getContainers() != null) {
            for (V1Container container : pod.getSpec().getContainers()) {
                Quantity cpuReq = getResourceQuantity(container, "requests", "cpu");
                Quantity cpuLim = getResourceQuantity(container, "limits", "cpu");
                Quantity memReq = getResourceQuantity(container, "requests", "memory");
                Quantity memLim = getResourceQuantity(container, "limits", "memory");
                resourceSpecs.put(container.getName(),
                        new ContainerResources(cpuReq, cpuLim, memReq, memLim));

                // 创建图表
                Platform.runLater(() -> addContainerChart(container.getName()));
            }

            if (usageMap.isEmpty()) {
                hintLabel.setVisible(true);
                hintLabel.setText("提示：实时使用量需要集群安装 metrics-server");
            }
        }

        // 更新折线图数据
        if (pod.getSpec() != null && pod.getSpec().getContainers() != null) {
            for (V1Container container : pod.getSpec().getContainers()) {
                Map<String, Quantity> usage = usageMap.getOrDefault(container.getName(), Map.of());
                ContainerChart cc = chartMap.get(container.getName());
                if (cc == null) continue;

                ContainerResources spec = resourceSpecs.getOrDefault(container.getName(), ContainerResources.EMPTY);

                // CPU 使用率（相对 limit，无 limit 则相对 request）
                double cpuPct = calcPercent(usage.get("cpu"), spec.cpuLim != null ? spec.cpuLim : spec.cpuReq);
                // 内存使用率
                double memPct = calcPercent(usage.get("memory"), spec.memLim != null ? spec.memLim : spec.memReq);

                cc.addData(cpuPct, memPct);
            }
        }
    }

    /**
     * 为容器创建 CPU + 内存双折线图
     */
    private void addContainerChart(String containerName) {
        ContainerChart cc = new ContainerChart(containerName, resourceSpecs.getOrDefault(containerName, ContainerResources.EMPTY));
        chartMap.put(containerName, cc);
        containerList.getChildren().add(cc.getRoot());
    }

    // ==================== 折线图构建 ====================

    private static class ContainerChart {
        private final AreaChart<Number, Number> cpuChart;
        private final AreaChart<Number, Number> memChart;
        private final XYChart.Series<Number, Number> cpuSeries;
        private final XYChart.Series<Number, Number> memSeries;
        private final Label cpuPctLabel;
        private final Label memPctLabel;
        private int tick = 0;

        ContainerChart(String name, ContainerResources spec) {
            cpuSeries = new XYChart.Series<>();
            cpuSeries.setName("CPU 使用率");
            memSeries = new XYChart.Series<>();
            memSeries.setName("内存使用率");

            cpuChart = createChart("CPU", "#4285F4", spec.cpuReq, spec.cpuLim, cpuSeries);
            memChart = createChart("内存", "#34A853", spec.memReq, spec.memLim, memSeries);

            VBox box = new VBox(6);
            box.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 8; " +
                    "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 4, 0, 0, 1); -fx-padding: 10 14 12 14;");

            // 标题行：容器名 + CPU/内存百分比
            HBox titleRow = new HBox(10);
            titleRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            Label icon = new Label("\uD83D\uDCAA");
            icon.setStyle("-fx-font-size: 13px;");
            Label title = new Label(name);
            title.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #333;");
            Region spacer = new Region();
            HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
            cpuPctLabel = new Label("CPU --");
            cpuPctLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #4285F4; -fx-font-weight: bold;");
            memPctLabel = new Label("MEM --");
            memPctLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #34A853; -fx-font-weight: bold;");
            titleRow.getChildren().addAll(icon, title, spacer, cpuPctLabel, memPctLabel);
            box.getChildren().add(titleRow);

            HBox chartsRow = new HBox(12);
            chartsRow.getChildren().addAll(cpuChart, memChart);
            HBox.setHgrow(cpuChart, javafx.scene.layout.Priority.ALWAYS);
            HBox.setHgrow(memChart, javafx.scene.layout.Priority.ALWAYS);
            box.getChildren().add(chartsRow);
            this.root = box;
        }

        private final VBox root;
        VBox getRoot() { return root; }

        void addData(double cpuPct, double memPct) {
            tick++;
            // 更新百分比标签
            cpuPctLabel.setText("CPU " + (cpuPct < 0 ? "--" : String.format("%.1f%%", cpuPct * 100)));
            memPctLabel.setText("MEM " + (memPct < 0 ? "--" : String.format("%.1f%%", memPct * 100)));

            // CPU 数据
            ObservableList<XYChart.Data<Number, Number>> cpuData = cpuSeries.getData();
            cpuData.add(new XYChart.Data<>(tick, cpuPct < 0 ? 0 : cpuPct * 100));
            if (cpuData.size() > MAX_DATA_POINTS) cpuData.remove(0);

            // 内存数据
            ObservableList<XYChart.Data<Number, Number>> memData = memSeries.getData();
            memData.add(new XYChart.Data<>(tick, memPct < 0 ? 0 : memPct * 100));
            if (memData.size() > MAX_DATA_POINTS) memData.remove(0);

            // 动态调整 X 轴范围
            updateXAxis(cpuChart);
            updateXAxis(memChart);
        }

        private void updateXAxis(AreaChart<Number, Number> chart) {
            NumberAxis xAxis = (NumberAxis) chart.getXAxis();
            xAxis.setLowerBound(Math.max(0, tick - MAX_DATA_POINTS));
            xAxis.setUpperBound(tick + 1);
        }

        private static AreaChart<Number, Number> createChart(String title, String color,
                                                              Quantity req, Quantity lim,
                                                              XYChart.Series<Number, Number> series) {
            NumberAxis xAxis = new NumberAxis();
            xAxis.setTickLabelsVisible(false);
            xAxis.setTickMarkVisible(false);
            xAxis.setMinorTickVisible(false);
            xAxis.setAutoRanging(false);
            xAxis.setLowerBound(0);
            xAxis.setUpperBound(MAX_DATA_POINTS);

            NumberAxis yAxis = new NumberAxis(0, 100, 20);
            yAxis.setLabel("%");

            AreaChart<Number, Number> chart = new AreaChart<>(xAxis, yAxis);
            chart.setTitle(title + (lim != null ? "（限制: " + formatRes(lim) + "）" : ""));
            chart.setCreateSymbols(false);
            chart.setAnimated(false);
            chart.setLegendVisible(false);
            chart.setPrefHeight(160);
            chart.setMinHeight(100);
            chart.getData().add(series);

            // 通过内联样式设置颜色，场景就绪后更新节点样式
            chart.setStyle("-fx-background-color: transparent;");
            chart.sceneProperty().addListener((obs, old, scene) -> {
                if (scene != null) {
                    applySeriesColor(series, color);
                }
            });

            return chart;
        }

        private static void applySeriesColor(XYChart.Series<Number, Number> series, String color) {
            var node = series.getNode();
            if (node == null) return;
            var fill = node.lookup(".chart-series-area-fill");
            var line = node.lookup(".chart-series-area-line");
            if (fill != null) fill.setStyle("-fx-fill: " + color + "22;");
            if (line != null) line.setStyle("-fx-stroke: " + color + "; -fx-stroke-width: 2px;");
        }
    }

    // ==================== 资源规格记录 ====================

    private record ContainerResources(Quantity cpuReq, Quantity cpuLim, Quantity memReq, Quantity memLim) {
        static final ContainerResources EMPTY = new ContainerResources(null, null, null, null);
    }

    // ==================== 工具方法 ====================

    private static Quantity getResourceQuantity(V1Container container, String type, String resource) {
        if (container.getResources() == null) return null;
        Map<String, Quantity> map = "requests".equals(type)
                ? container.getResources().getRequests()
                : container.getResources().getLimits();
        return map != null ? map.get(resource) : null;
    }

    private static double calcPercent(Quantity usage, Quantity baseline) {
        if (usage == null || baseline == null) return -1;
        try {
            double u = parseQuantity(usage.toSuffixedString());
            double b = parseQuantity(baseline.toSuffixedString());
            if (b <= 0) return -1;
            return Math.min(u / b, 1.0);
        } catch (Exception e) {
            log.debug("计算使用率失败: usage={}, baseline={}", usage, baseline, e);
            return -1;
        }
    }

    /**
     * 解析 Quantity 字符串为统一数值。
     * CPU 统一转为 cores（"100m" → 0.1, "50000000n" → 0.05）
     * 内存统一转为 bytes（"256Mi" → 268435456, "1Gi" → 1073741824）
     * 同一资源类型的使用量和限额用相同基准，比值正确即可。
     */
    private static double parseQuantity(String s) {
        s = s.trim();
        // CPU 小数单位
        if (s.endsWith("n")) return Double.parseDouble(s.replace("n", "")) / 1_000_000_000.0;
        if (s.endsWith("u")) return Double.parseDouble(s.replace("u", "")) / 1_000_000.0;
        if (s.endsWith("m")) return Double.parseDouble(s.replace("m", "")) / 1000.0;
        // 内存二进制单位 → bytes
        if (s.endsWith("Ki")) return Double.parseDouble(s.replace("Ki", "")) * 1024;
        if (s.endsWith("Mi")) return Double.parseDouble(s.replace("Mi", "")) * 1024 * 1024;
        if (s.endsWith("Gi")) return Double.parseDouble(s.replace("Gi", "")) * 1024L * 1024 * 1024;
        if (s.endsWith("Ti")) return Double.parseDouble(s.replace("Ti", "")) * 1024L * 1024 * 1024 * 1024;
        if (s.endsWith("Pi")) return Double.parseDouble(s.replace("Pi", "")) * 1024L * 1024 * 1024 * 1024 * 1024;
        if (s.endsWith("Ei")) return Double.parseDouble(s.replace("Ei", "")) * 1024L * 1024 * 1024 * 1024 * 1024 * 1024;
        // 内存十进制单位 → bytes
        if (s.endsWith("k")) return Double.parseDouble(s.replace("k", "")) * 1000;
        if (s.endsWith("K")) return Double.parseDouble(s.replace("K", "")) * 1000;
        if (s.endsWith("M")) return Double.parseDouble(s.replace("M", "")) * 1_000_000;
        if (s.endsWith("G")) return Double.parseDouble(s.replace("G", "")) * 1_000_000_000;
        if (s.endsWith("T")) return Double.parseDouble(s.replace("T", "")) * 1_000_000_000_000.0;
        if (s.endsWith("P")) return Double.parseDouble(s.replace("P", "")) * 1_000_000_000_000_000.0;
        if (s.endsWith("E")) return Double.parseDouble(s.replace("E", "")) * 1_000_000_000_000_000_000.0;
        // 无后缀：CPU 为核心数，内存为字节数
        return Double.parseDouble(s);
    }

    private static String formatRes(Quantity q) {
        if (q == null) return "N/A";
        String s = q.toSuffixedString();
        // 纯数字无后缀：CPU 场景是核心数，内存场景是字节数
        if (!s.isEmpty() && Character.isDigit(s.charAt(s.length() - 1))) {
            try {
                double val = Double.parseDouble(s);
                if (val >= 1024) {
                    // 内存字节数 → 转为 Mi 显示
                    return String.format("%.0fMi", val / (1024 * 1024));
                }
                // CPU 核心数
                return val < 1 ? String.format("%.0fm", val * 1000) : String.format("%.1f核", val);
            } catch (NumberFormatException e) {
                return s;
            }
        }
        return s;
    }

    private static String statusColor(String status) {
        return switch (status) {
            case "Running" -> "#2E7D32";
            case "Pending" -> "#F57F17";
            case "Failed", "Unknown" -> "#C62828";
            default -> "#555";
        };
    }

    private void copyMonitorInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Pod: ").append(podName).append("\n");
        sb.append("命名空间: ").append(namespace).append("\n");
        sb.append("状态: ").append(statusLabel.getText()).append("\n");
        sb.append("节点: ").append(nodeLabel.getText()).append("\n");
        sb.append("IP: ").append(ipLabel.getText()).append("\n");
        sb.append("重启: ").append(restartLabel.getText()).append("\n\n");

        for (Map.Entry<String, ContainerChart> entry : chartMap.entrySet()) {
            ContainerChart cc = entry.getValue();
            sb.append("容器: ").append(entry.getKey()).append("\n");
            sb.append("  ").append(cc.cpuPctLabel.getText()).append("\n");
            sb.append("  ").append(cc.memPctLabel.getText()).append("\n\n");
        }

        javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, sb.toString().trim()));
    }
}
