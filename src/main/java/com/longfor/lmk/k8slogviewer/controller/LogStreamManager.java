package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import com.longfor.lmk.k8slogviewer.config.K8sQuery;
import com.longfor.lmk.k8slogviewer.service.PodLogFileManager;
import com.longfor.lmk.k8slogviewer.utils.LogStyleUtil;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;

import static com.longfor.lmk.k8slogviewer.utils.LogStyleUtil.SEPARATOR_LINE;

/**
 * 日志流管理器，负责日志缓冲、UI 刷新、历史加载、视图行号追踪。
 * <p>
 * 从 K8sLogViewerController 中拆分出来，职责单一：
 * <ul>
 *   <li>日志缓冲队列（logQueue）的入队/出队</li>
 *   <li>视图行号追踪（viewStartLine / viewEndLine / diskEndLine）</li>
 *   <li>日志批处理（processLogBatch）</li>
 *   <li>历史日志加载（向前/向后翻页）</li>
 *   <li>CodeArea 初始化与行号渲染</li>
 * </ul>
 */
public class LogStreamManager {

    private static final Logger log = LoggerFactory.getLogger(LogStreamManager.class);

    /** UI 最大保持行数 */
    private static final int MAX_LOG_LINES = 500;
    /** 每次从磁盘加载的行数 */
    private static final int HISTORY_LOAD_LINES = 1000;

    // ==================== UI 引用 ====================

    private final CodeArea logArea;
    private final CodeArea headerArea;
    private final PodLogFileManager fileManager;
    private VirtualizedScrollPane<CodeArea> logScrollPane;

    // ==================== 日志缓冲 ====================

    private final Queue<String> logQueue = new ArrayDeque<>();
    private final Object logQueueLock = new Object();

    // ==================== 视图行号追踪 ====================

    private int viewStartLine = 0;
    private int viewEndLine = 0;
    private int diskEndLine = 0;
    private volatile boolean loadingHistory = false;
    private long lastSearchRefreshTime = 0;

    // ==================== 自动滚动控制 ====================

    /** true = 用户点"暂停"后停止跟滚；false = 跟随最新日志 */
    private volatile boolean autoScrollPaused = false;

    /** 跟滚状态变化回调（通知 Controller 更新按钮文案） */
    private java.util.function.Consumer<Boolean> onAutoScrollStateChanged;

    public void setOnAutoScrollStateChanged(java.util.function.Consumer<Boolean> callback) {
        this.onAutoScrollStateChanged = callback;
    }

    /** 恢复自动跟滚（用户点"恢复"时调用） */
    public void resumeAutoScroll() {
        setAutoScrollPaused(false);
    }

    /** 暂停自动跟滚（用户点"暂停"按钮时调用） */
    public void pauseAutoScroll() {
        setAutoScrollPaused(true);
    }

    /** 当前是否处于暂停跟滚状态 */
    public boolean isAutoScrollPaused() {
        return autoScrollPaused;
    }

    private void setAutoScrollPaused(boolean paused) {
        if (autoScrollPaused != paused) {
            autoScrollPaused = paused;
            if (onAutoScrollStateChanged != null) {
                onAutoScrollStateChanged.accept(paused);
            }
        }
    }

    // ==================== 回调 ====================

    /** 日志裁剪后通知搜索引擎同步调整匹配行号 */
    private BiConsumer<Integer, Integer> onTrimmed;

    public void setOnTrimmed(BiConsumer<Integer, Integer> callback) {
        this.onTrimmed = callback;
    }

    // ==================== 构造 ====================

    public LogStreamManager(CodeArea logArea, CodeArea headerArea, PodLogFileManager fileManager) {
        this.logArea = logArea;
        this.headerArea = headerArea;
        this.fileManager = fileManager;
    }

    // ==================== CodeArea 初始化 ====================

    /**
     * 初始化 CodeArea：设置不可编辑、换行、滚动条、行号工厂。
     * 返回创建的 VirtualizedScrollPane，由调用方加入布局。
     */
    public VirtualizedScrollPane<CodeArea> initCodeArea() {
        headerArea.setEditable(false);
        logArea.setEditable(false);
        logArea.setParagraphGraphicFactory(this::createLineNumberLabel);
        headerArea.setWrapText(true);
        logArea.setWrapText(true);

        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(logArea);
        logScrollPane = scrollPane;
        logScrollPane.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // 滚动到顶部/底部时自动加载历史
        scrollPane.estimatedScrollYProperty().addListener((obs, oldVal, newVal) -> {
            if (!loadingHistory && newVal != null && newVal <= 5.0) {
                loadHistoryFromDisk();
            }
            if (!loadingHistory && newVal != null) {
                double totalHeight = logArea.getTotalHeightEstimate();
                double viewportHeight = scrollPane.getHeight();
                double maxScroll = totalHeight - viewportHeight;
                if (maxScroll > 0 && (maxScroll - newVal) <= 30.0) {
                    loadForwardFromDisk();
                }
            }
        });

        return scrollPane;
    }

    private Node createLineNumberLabel(int line) {
        Label label = new Label(String.valueOf(viewStartLine + line + 1));
        label.setFont(javafx.scene.text.Font.font("JetBrains Mono", javafx.scene.text.FontWeight.NORMAL, 12));
        label.setTextFill(javafx.scene.paint.Color.web("#E0E0E0"));
        label.setAlignment(Pos.CENTER_RIGHT);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setPadding(new Insets(0, 10, 0, 6));
        label.setStyle("-fx-background-color: #2D2D2D;");
        return label;
    }

    public void refreshLineNumbers() {
        logArea.setParagraphGraphicFactory(this::createLineNumberLabel);
    }

    // ==================== 队列操作 ====================

    /**
     * 入队一行日志，同时写入磁盘文件。
     * 由 LogFetchService 的流式回调调用。
     */
    public void enqueueLine(String line) {
        synchronized (logQueueLock) {
            fileManager.append(line);
            logQueue.offer(line);
        }
    }

    /** 排空日志缓冲队列，返回所有待处理行 */
    public List<String> drainQueue() {
        List<String> batch = new ArrayList<>();
        synchronized (logQueueLock) {
            while (!logQueue.isEmpty()) {
                batch.add(logQueue.poll());
            }
        }
        return batch;
    }

    // ==================== 状态重置 ====================

    /** 切换 Pod 时重置视图行号追踪和缓冲 */
    public void resetForNewPod() {
        resumeAutoScroll();
        viewStartLine = 0;
        viewEndLine = 0;
        diskEndLine = 0;
        lastSearchRefreshTime = 0;
        loadingHistory = false;
        synchronized (logQueueLock) {
            logQueue.clear();
        }
    }

    /** 清空 logArea 和 headerArea */
    public void clearAreas() {
        Platform.runLater(() -> {
            LogStyleUtil.clear(logArea);
            LogStyleUtil.clear(headerArea);
            refreshLineNumbers();
        });
    }

    // ==================== 批处理 ====================

    /**
     * 处理一批日志行：分离 header/log，追加到 CodeArea，更新行号追踪。
     * <p>
     * 注意：搜索高亮由调用方（Controller）在调用此方法后单独处理。
     *
     * @return 实际追加到 logArea 的行列表（用于搜索增量更新），暂停时返回空列表
     */
    public List<String> processLogBatch(List<String> lines) {
        K8sQuery k8sQuery = AppConfig.getK8sQuery();

        // 分离 header 行和 log 行
        List<String> headerLines = new ArrayList<>();
        List<String> logLines = new ArrayList<>();

        for (String line : lines) {
            if (k8sQuery.isHeaderCaptured()) {
                boolean isSeparator = line.trim().contains(SEPARATOR_LINE);
                if (isSeparator) {
                    k8sQuery.setHeaderCaptured(false);
                } else {
                    headerLines.add(line);
                }
            } else {
                logLines.add(line);
            }
        }

        // 追加 header 行
        for (String line : headerLines) {
            LogStyleUtil.appendHeaderLine(headerArea, line);
        }

        // 用户手动暂停时不追加日志到 UI（仍写入磁盘）
        if (autoScrollPaused || logLines.isEmpty()) {
            if (!logLines.isEmpty()) {
                diskEndLine += logLines.size();
            }
            return List.of();
        }

        // 追加 log 行
        LogStyleUtil.appendBatch(logArea, logLines, null);
        viewEndLine += logLines.size();
        diskEndLine += logLines.size();

        // 裁剪旧行
        trimLogArea();

        // 自动跟滚
        if (!autoScrollPaused) {
            logArea.moveTo(logArea.getLength());
            logArea.requestFollowCaret();
        }

        return logLines;
    }

    /**
     * 裁剪旧行，始终保持 UI 在 MAX_LOG_LINES 以内。
     * 裁剪后通过 onTrimmed 回调通知搜索引擎同步调整。
     */
    private void trimLogArea() {
        int paragraphCount = logArea.getParagraphs().size();
        if (paragraphCount <= MAX_LOG_LINES) {
            viewStartLine = viewEndLine - paragraphCount;
            if (viewStartLine < 0) viewStartLine = 0;
            refreshLineNumbers();
            return;
        }

        int removeCount = Math.min(paragraphCount - MAX_LOG_LINES + (MAX_LOG_LINES / 10), paragraphCount);
        int endPos = 0;
        for (int i = 0; i < removeCount; i++) {
            endPos += logArea.getParagraphs().get(i).length() + 1;
        }
        endPos = Math.min(endPos, logArea.getLength());
        logArea.deleteText(0, endPos);

        int oldViewStart = viewStartLine;
        viewStartLine += removeCount;

        // 通知搜索引擎
        if (onTrimmed != null) {
            onTrimmed.accept(oldViewStart, removeCount);
        }

        refreshLineNumbers();
    }

    // ==================== 历史加载 ====================

    /** 滚动到顶部时加载更早的历史日志 */
    public void loadHistoryFromDisk() {
        String podName = AppConfig.getK8sQuery().getPodName();
        if (podName == null || loadingHistory) return;
        if (viewStartLine <= 0) return;

        loadingHistory = true;
        com.longfor.lmk.k8slogviewer.utils.ExecutorManager.submit(() -> {
            int count = Math.min(HISTORY_LOAD_LINES, viewStartLine);
            int startLine = viewStartLine - count;
            List<String> historyLines = fileManager.readLogLines(podName, startLine, count);

            if (historyLines.isEmpty()) {
                loadingHistory = false;
                return;
            }

            Platform.runLater(() -> {
                double scrollX = logScrollPane.getEstimatedScrollX();
                double scrollY = logScrollPane.getEstimatedScrollY();

                LogStyleUtil.prependBatch(logArea, historyLines, null);
                viewStartLine -= historyLines.size();
                refreshLineNumbers();

                logScrollPane.estimatedScrollXProperty().setValue(scrollX);
                logScrollPane.estimatedScrollYProperty().setValue(scrollY);

                loadingHistory = false;
            });
        });
    }

    /** 滚动到底部时加载更新的日志 */
    public void loadForwardFromDisk() {
        String podName = AppConfig.getK8sQuery().getPodName();
        if (podName == null || loadingHistory) return;
        if (diskEndLine <= viewEndLine) return;

        loadingHistory = true;
        com.longfor.lmk.k8slogviewer.utils.ExecutorManager.submit(() -> {
            int count = Math.min(HISTORY_LOAD_LINES, diskEndLine - viewEndLine);
            List<String> forwardLines = fileManager.readLogLines(podName, viewEndLine, count);

            if (forwardLines.isEmpty()) {
                loadingHistory = false;
                return;
            }

            Platform.runLater(() -> {
                double scrollX = logScrollPane.getEstimatedScrollX();
                double scrollY = logScrollPane.getEstimatedScrollY();

                LogStyleUtil.appendBatch(logArea, forwardLines, null);
                viewEndLine += forwardLines.size();
                trimLogArea();
                refreshLineNumbers();

                logScrollPane.estimatedScrollXProperty().setValue(scrollX);
                logScrollPane.estimatedScrollYProperty().setValue(scrollY);

                loadingHistory = false;
            });
        });
    }

    /**
     * 加载指定行号范围的日志到视图，用于搜索跳转到不在当前视图的匹配行。
     *
     * @param centerLine 目标中心行号（0-based）
     * @param onLoaded   加载完成后的回调（Platform 线程），可为 null
     */
    public void loadViewFromDisk(String podName, int centerLine, Runnable onLoaded) {
        if (podName == null) return;

        int half = MAX_LOG_LINES / 2;
        int startLine = Math.max(0, centerLine - half);
        int count = MAX_LOG_LINES;

        loadingHistory = true;
        com.longfor.lmk.k8slogviewer.utils.ExecutorManager.submit(() -> {
            List<String> lines = fileManager.readLogLines(podName, startLine, count);
            if (lines.isEmpty()) {
                loadingHistory = false;
                return;
            }

            Platform.runLater(() -> {
                LogStyleUtil.clear(logArea);
                LogStyleUtil.appendBatch(logArea, lines, null);
                viewStartLine = startLine;
                viewEndLine = startLine + lines.size();
                refreshLineNumbers();
                loadingHistory = false;

                if (onLoaded != null) {
                    onLoaded.run();
                }
            });
        });
    }

    // ==================== 恢复与滚动 ====================

    /** 恢复滚动时，把暂停期间积压在磁盘但未显示的日志补回到 UI */
    public void resumeAndCatchUp() {
        resumeAutoScroll();
        String podName = AppConfig.getK8sQuery().getPodName();
        if (podName == null) return;

        int currentVisibleLines = logArea.getParagraphs().size();
        int gapStart = viewStartLine + currentVisibleLines;
        int gapCount = diskEndLine - gapStart;

        if (gapCount <= 0) return;

        com.longfor.lmk.k8slogviewer.utils.ExecutorManager.submit(() -> {
            List<String> lines = fileManager.readLogLines(podName, gapStart, gapCount);
            if (lines.isEmpty()) return;

            Platform.runLater(() -> {
                LogStyleUtil.appendBatch(logArea, lines, null);
                trimLogArea();
                logArea.moveTo(logArea.getLength());
                logArea.requestFollowCaret();
            });
        });
    }

    /** 置顶：跳转到磁盘文件第一行 */
    public void scrollToTop(String podName) {
        if (podName == null) return;

        loadingHistory = true;
        com.longfor.lmk.k8slogviewer.utils.ExecutorManager.submit(() -> {
            List<String> lines = fileManager.readLogLines(podName, 0, MAX_LOG_LINES);
            if (lines.isEmpty()) {
                Platform.runLater(() -> {
                    loadingHistory = false;
                    logArea.moveTo(0, 0);
                });
                return;
            }

            Platform.runLater(() -> {
                LogStyleUtil.clear(logArea);
                LogStyleUtil.appendBatch(logArea, lines, null);
                viewStartLine = 0;
                viewEndLine = lines.size();
                refreshLineNumbers();
                loadingHistory = false;
                logArea.moveTo(0, 0);
                logArea.requestFollowCaret();
            });
        });
    }

    /** 置底：跳转到磁盘文件最后一行 */
    public void scrollToBottom(String podName) {
        resumeAutoScroll();
        if (podName == null) return;

        loadingHistory = true;
        com.longfor.lmk.k8slogviewer.utils.ExecutorManager.submit(() -> {
            int totalLines = fileManager.getLineCount(podName);
            if (totalLines <= 0) {
                Platform.runLater(() -> {
                    loadingHistory = false;
                    logArea.moveTo(logArea.getLength());
                    logArea.requestFollowCaret();
                });
                return;
            }

            int startLine = Math.max(0, totalLines - MAX_LOG_LINES);
            List<String> lines = fileManager.readLogLines(podName, startLine, MAX_LOG_LINES);
            if (lines.isEmpty()) {
                Platform.runLater(() -> loadingHistory = false);
                return;
            }

            Platform.runLater(() -> {
                LogStyleUtil.clear(logArea);
                LogStyleUtil.appendBatch(logArea, lines, null);
                viewStartLine = startLine;
                viewEndLine = totalLines;
                diskEndLine = Math.max(diskEndLine, totalLines);
                refreshLineNumbers();
                loadingHistory = false;
                logArea.moveTo(logArea.getLength());
                logArea.requestFollowCaret();
            });
        });
    }

    // ==================== 文件截断处理 ====================

    /** 磁盘文件被截断后，同步调整视图行号映射 */
    public void adjustViewLinesOnTruncate(int removedLines) {
        viewStartLine = Math.max(0, viewStartLine - removedLines);
        viewEndLine = Math.max(0, viewEndLine - removedLines);
        diskEndLine = Math.max(0, diskEndLine - removedLines);
        refreshLineNumbers();
    }

    // ==================== Getter / Setter ====================

    public int getViewStartLine() { return viewStartLine; }
    public int getViewEndLine() { return viewEndLine; }
    public int getDiskEndLine() { return diskEndLine; }
    public long getLastSearchRefreshTime() { return lastSearchRefreshTime; }
    public void setLastSearchRefreshTime(long time) { this.lastSearchRefreshTime = time; }

    // ==================== 流结束标记 ====================

    /** 在日志区域追加流结束标记 */
    public void appendEndMarker() {
        Platform.runLater(() -> {
            LogStyleUtil.appendBatch(logArea,
                    List.of("", "--- 日志流已结束，容器可能已退出或被删除 ---"), null);
            logArea.moveTo(logArea.getLength());
            logArea.requestFollowCaret();
        });
    }
}
