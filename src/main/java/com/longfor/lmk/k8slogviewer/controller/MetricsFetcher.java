package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.K8sClientManager;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiClient;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 从 K8s Metrics API 获取 Pod 容器的实时资源使用量。
 * 需要集群安装 metrics-server，否则返回空 map。
 */
final class MetricsFetcher {

    private static final Logger log = LoggerFactory.getLogger(MetricsFetcher.class);

    private MetricsFetcher() {}

    /**
     * 获取指定 Pod 各容器的实时资源使用量。
     *
     * @param namespace 命名空间
     * @param podName   Pod 名称
     * @return key=容器名, value={cpu: Quantity, memory: Quantity}；获取失败返回空 map
     */
    static Map<String, Map<String, Quantity>> fetchPodMetrics(String namespace, String podName) {
        Map<String, Map<String, Quantity>> result = new HashMap<>();
        try {
            ApiClient client = K8sClientManager.getApiClient();
            if (client == null) return result;

            // 使用 OkHttp 直接调用 metrics API
            String path = client.getBasePath() + "/apis/metrics.k8s.io/v1beta1/namespaces/" + namespace + "/pods/" + podName;
            Request request = new Request.Builder().url(path).get().build();
            Call call = client.getHttpClient().newCall(request);
            try (Response response = call.execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String json = response.body().string();
                    parseMetricsJson(json, result);
                } else if (response.code() != 404) {
                    log.debug("获取 Pod Metrics 失败 (code={})", response.code());
                }
            }
        } catch (Exception e) {
            log.debug("获取 Pod Metrics 异常: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 简易 JSON 解析：提取 containers[].name / usage.cpu / usage.memory
     */
    private static void parseMetricsJson(String json, Map<String, Map<String, Quantity>> result) {
        if (json == null || json.isEmpty()) return;

        // 找到 "containers": [ ... ]
        int containersIdx = json.indexOf("\"containers\"");
        if (containersIdx < 0) return;

        int arrayStart = json.indexOf('[', containersIdx);
        int arrayEnd = json.lastIndexOf(']');
        if (arrayStart < 0 || arrayEnd < 0) return;

        String containersArray = json.substring(arrayStart, arrayEnd + 1);

        // 按大括号块拆分每个容器
        int depth = 0;
        int blockStart = -1;
        for (int i = 0; i < containersArray.length(); i++) {
            char c = containersArray.charAt(i);
            if (c == '{') {
                if (depth == 0) blockStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && blockStart >= 0) {
                    String block = containersArray.substring(blockStart, i + 1);
                    parseContainerBlock(block, result);
                    blockStart = -1;
                }
            }
        }
    }

    private static void parseContainerBlock(String block, Map<String, Map<String, Quantity>> result) {
        String name = extractStringValue(block, "name");
        if (name == null) return;

        // 解析 usage 中的 cpu 和 memory
        int usageIdx = block.indexOf("\"usage\"");
        if (usageIdx < 0) return;

        String usageBlock = extractObjectBlock(block, usageIdx);
        if (usageBlock == null) return;

        Map<String, Quantity> usage = new HashMap<>();
        String cpuVal = extractStringValue(usageBlock, "cpu");
        String memVal = extractStringValue(usageBlock, "memory");
        if (cpuVal != null) usage.put("cpu", new Quantity(cpuVal));
        if (memVal != null) usage.put("memory", new Quantity(memVal));

        result.put(name, usage);
    }

    private static String extractStringValue(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private static String extractObjectBlock(String json, int fromIdx) {
        int colon = json.indexOf(':', fromIdx);
        if (colon < 0) return null;
        int start = json.indexOf('{', colon);
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == '{') depth++;
            else if (json.charAt(i) == '}') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return null;
    }
}
