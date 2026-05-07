package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import com.longfor.lmk.k8slogviewer.service.PodLogFileManager;
import com.longfor.lmk.k8slogviewer.utils.CommonUtils;
import com.longfor.lmk.k8slogviewer.utils.ExecutorManager;
import com.longfor.lmk.k8slogviewer.utils.LogStyleUtil;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.control.Label;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 磁盘搜索引擎，负责日志文件中关键字的搜索、导航与高亮。
 * <p>
 * 从 K8sLogViewerController 中拆分出来，职责单一：
 * <ul>
 *   <li>磁盘搜索状态管理（匹配行号列表、当前匹配索引）</li>
 *   <li>后台搜索调度（searchDiskInBackground）</li>
 *   <li>搜索导航（findNext / findPrev / navigateToDiskMatch）</li>
 *   <li>搜索高亮（rehighlightLogArea / applyCurrentMatchHighlight）</li>
 *   <li>增量搜索更新（incrementalSearchUpdate）</li>
 * </ul>
 */
public class DiskSearchEngine {

    private static final Logger log = LoggerFactory.getLogger(DiskSearchEngine.class);

    // ==================== UI 引用 ====================

    private final CodeArea logArea;
    private final PodLogFileManager fileManager;
    private final Label matchCountLabel;

    // ==================== 搜索状态 ====================

    private final List<Integer> diskMatchLineNumbers = new ArrayList<>();
    private int currentDiskMatchIndex = -1;
    private String lastKeyword = "";
    private boolean searchAndMode = false;

    /** 后台搜索代际，用于取消过时的搜索结果 */
    private final AtomicLong searchGeneration = new AtomicLong(0);

    // ==================== 视图状态供应商（由 Controller 注入） ====================

    private Supplier<Integer> viewStartLineSupplier;
    private Supplier<Integer> viewEndLineSupplier;

    @FunctionalInterface
    public interface ViewLoader {
        void loadView(String podName, int centerLine, Runnable onLoaded);
    }

    private ViewLoader viewLoader;
    private Supplier<VirtualizedScrollPane<CodeArea>> logScrollPaneSupplier;

    // ==================== 构造 ====================

    public DiskSearchEngine(CodeArea logArea, PodLogFileManager fileManager, Label matchCountLabel) {
        this.logArea = logArea;
        this.fileManager = fileManager;
        this.matchCountLabel = matchCountLabel;
    }

    // ==================== 依赖注入 ====================

    public void setViewStateSuppliers(Supplier<Integer> startLine, Supplier<Integer> endLine) {
        this.viewStartLineSupplier = startLine;
        this.viewEndLineSupplier = endLine;
    }

    public void setViewLoader(ViewLoader loader) {
        this.viewLoader = loader;
    }

    public void setLogScrollPaneSupplier(Supplier<VirtualizedScrollPane<CodeArea>> supplier) {
        this.logScrollPaneSupplier = supplier;
    }

    // ==================== 搜索操作 ====================

    /** 用户主动搜索，跳转到第一个匹配 */
    public void searchDiskInBackground(String keyword) {
        searchDiskInBackground(keyword, true);
    }

    /** 搜索磁盘日志文件 */
    public void searchDiskInBackground(String keyword, boolean navigateToFirst) {
        searchDiskInBackground(keyword, navigateToFirst, false, true);
    }

    /**
     * 在磁盘文件中搜索关键字（后台线程），完成后更新匹配列表和 UI。
     *
     * @param keyword               搜索关键字
     * @param navigateToFirst       是否跳转到第一个匹配
     * @param force                 强制搜索，即使关键字未变化也执行（定时刷新时为 true）
     * @param incrementGeneration   是否递增搜索代际（定时刷新传 false 以避免冲掉用户导航）
     */
    public void searchDiskInBackground(String keyword, boolean navigateToFirst, boolean force, boolean incrementGeneration) {
        if (keyword == null || keyword.isBlank()) {
            diskMatchLineNumbers.clear();
            currentDiskMatchIndex = -1;
            lastKeyword = "";
            updateMatchLabel();
            rehighlightLogArea(null);
            return;
        }

        if (!force && keyword.equals(lastKeyword)) return;

        String podName = AppConfig.getK8sQuery().getPodName();
        if (podName == null) return;

        // 递增代际，使之前未完成的后台搜索结果作废
        // 定时刷新不递增，避免冲掉用户主动搜索的导航
        long gen = incrementGeneration ? searchGeneration.incrementAndGet() : searchGeneration.get();

        int currentMatchLine = (currentDiskMatchIndex >= 0 && currentDiskMatchIndex < diskMatchLineNumbers.size())
                ? diskMatchLineNumbers.get(currentDiskMatchIndex) : -1;

        ExecutorManager.submit(() -> {
            PodLogFileManager.DiskSearchResult result = fileManager.searchInLogFile(podName, keyword, searchAndMode);

            Platform.runLater(() -> {
                // 代际不匹配说明已有更新的搜索任务，丢弃此结果
                if (gen != searchGeneration.get()) return;

                diskMatchLineNumbers.clear();
                diskMatchLineNumbers.addAll(result.matchedLineNumbers);
                lastKeyword = keyword;

                if (navigateToFirst) {
                    currentDiskMatchIndex = diskMatchLineNumbers.isEmpty() ? -1 : 0;
                } else if (currentMatchLine >= 0 && !diskMatchLineNumbers.isEmpty()) {
                    int idx = diskMatchLineNumbers.indexOf(currentMatchLine);
                    currentDiskMatchIndex = idx >= 0 ? idx : Math.min(currentDiskMatchIndex, diskMatchLineNumbers.size() - 1);
                } else if (!diskMatchLineNumbers.isEmpty()) {
                    currentDiskMatchIndex = 0;
                } else {
                    currentDiskMatchIndex = -1;
                }

                updateMatchLabel();

                if (navigateToFirst && !diskMatchLineNumbers.isEmpty()) {
                    rehighlightLogArea(keyword);
                    navigateToDiskMatch();
                } else {
                    // 非导航模式：仅刷新当前匹配行的高亮，不重算全文样式也不移动光标
                    applyCurrentMatchHighlight();
                }
            });
        });
    }

    /** 跳转到下一个匹配 */
    public void findNext() {
        if (diskMatchLineNumbers.isEmpty()) return;
        searchGeneration.incrementAndGet();  // 使进行中的后台搜索结果失效，防止回调覆盖当前导航
        currentDiskMatchIndex = (currentDiskMatchIndex + 1) % diskMatchLineNumbers.size();
        navigateToDiskMatch();
    }

    /** 跳转到上一个匹配 */
    public void findPrev() {
        if (diskMatchLineNumbers.isEmpty()) return;
        searchGeneration.incrementAndGet();  // 使进行中的后台搜索结果失效，防止回调覆盖当前导航
        currentDiskMatchIndex = (currentDiskMatchIndex - 1 + diskMatchLineNumbers.size()) % diskMatchLineNumbers.size();
        navigateToDiskMatch();
    }

    /** 跳转到指定索引的匹配项（1-based，用户输入） */
    public void navigateToMatchIndex(int index) {
        if (diskMatchLineNumbers.isEmpty()) return;
        if (index < 1 || index > diskMatchLineNumbers.size()) return;
        currentDiskMatchIndex = index - 1;
        navigateToDiskMatch();
    }

    /** 获取当前匹配索引（0-based） */
    public int getCurrentMatchIndex() {
        return currentDiskMatchIndex;
    }

    /** 获取匹配总数 */
    public int getMatchCount() {
        return diskMatchLineNumbers.size();
    }

    /**
     * 跳转到当前磁盘搜索匹配的行。
     * 如果匹配行不在当前视图范围内，自动从磁盘加载对应的页面。
     */
    public void navigateToDiskMatch() {
        if (currentDiskMatchIndex < 0 || currentDiskMatchIndex >= diskMatchLineNumbers.size()) return;

        int targetLine = diskMatchLineNumbers.get(currentDiskMatchIndex);
        String keyword = lastKeyword;

        int viewStart = viewStartLineSupplier != null ? viewStartLineSupplier.get() : 0;
        int viewEnd = viewEndLineSupplier != null ? viewEndLineSupplier.get() : 0;

        if (targetLine >= viewStart && targetLine < viewEnd) {
            int localLine = targetLine - viewStart;
            highlightAndSelectLine(localLine, keyword);
            updateMatchLabel();
        } else {
            if (viewLoader != null) {
                String podName = AppConfig.getK8sQuery().getPodName();
                viewLoader.loadView(podName, targetLine, () -> {
                    int newViewStart = viewStartLineSupplier.get();
                    int localLine = targetLine - newViewStart;
                    if (localLine >= 0 && localLine < logArea.getParagraphs().size()) {
                        highlightAndSelectLine(localLine, keyword);
                    }
                    updateMatchLabel();
                });
            }
        }
    }

    // ==================== 高亮 ====================

    /**
     * 对整个 logArea 重新计算搜索高亮样式。
     * @param searchKeyword 搜索关键字，null 或空则清除高亮
     */
    public void rehighlightLogArea(String searchKeyword) {
        String searchKw = (searchKeyword != null && !searchKeyword.isBlank()) ? searchKeyword : null;
        if (searchKw == null || !searchAndMode) {
            String text = logArea.getText();
            StyleSpans<Collection<String>> spans =
                    LogStyleUtil.computeHighlighting(false, text, searchKw, false);
            logArea.setStyleSpans(0, spans);
        } else {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < logArea.getParagraphs().size(); i++) {
                lines.add(logArea.getText(i, 0, i, logArea.getParagraphLength(i)));
            }
            StyleSpans<Collection<String>> spans =
                    LogStyleUtil.computeBatchHighlighting(lines, searchKw, true);
            logArea.setStyleSpans(0, spans);
        }
    }

    /** 为当前导航到的匹配项应用橙色高亮样式 */
    public void applyCurrentMatchHighlight() {
        if (currentDiskMatchIndex < 0 || currentDiskMatchIndex >= diskMatchLineNumbers.size()) return;
        String keyword = lastKeyword;
        if (keyword.isBlank()) return;

        int targetLine = diskMatchLineNumbers.get(currentDiskMatchIndex);
        int viewStart = viewStartLineSupplier != null ? viewStartLineSupplier.get() : 0;
        int localLine = targetLine - viewStart;
        if (localLine < 0 || localLine >= logArea.getParagraphs().size()) return;

        String lineText = logArea.getParagraph(localLine).getText();
        String lowerText = lineText.toLowerCase();

        List<String> keywords = CommonUtils.parseSearchKeywords(keyword);
        int bestIdx = -1;
        String matchedKw = null;
        for (String kw : keywords) {
            int idx = lowerText.indexOf(kw.toLowerCase());
            if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
                bestIdx = idx;
                matchedKw = kw;
            }
        }

        if (bestIdx >= 0) {
            int start = logArea.getAbsolutePosition(localLine, bestIdx);
            Set<String> currentStyles = Set.of("plain-text", "search-highlight-current");
            StyleSpansBuilder<Collection<String>> ssb = new StyleSpansBuilder<>();
            ssb.add(currentStyles, matchedKw.length());
            logArea.setStyleSpans(start, ssb.create());
        }
    }

    /**
     * 高亮并选中指定行中的搜索关键字。
     *
     * @param localLine 段落索引（0-based）
     * @param keyword   搜索关键字
     */
    private void highlightAndSelectLine(int localLine, String keyword) {
        if (localLine < 0 || localLine >= logArea.getParagraphs().size()) return;

        String lineText = logArea.getParagraph(localLine).getText();
        String lowerText = lineText.toLowerCase();

        rehighlightLogArea(keyword);

        List<String> keywords = CommonUtils.parseSearchKeywords(keyword);
        int bestIdx = -1;
        String matchedKw = null;
        for (String kw : keywords) {
            int idx = lowerText.indexOf(kw.toLowerCase());
            if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
                bestIdx = idx;
                matchedKw = kw;
            }
        }

        if (bestIdx >= 0) {
            int start = logArea.getAbsolutePosition(localLine, bestIdx);
            Set<String> currentStyles = Set.of("plain-text", "search-highlight-current");
            StyleSpansBuilder<Collection<String>> ssb = new StyleSpansBuilder<>();
            ssb.add(currentStyles, matchedKw.length());
            logArea.setStyleSpans(start, ssb.create());
            logArea.selectRange(start, start + matchedKw.length());
        } else {
            logArea.moveTo(localLine, 0);
        }
        logArea.requestFollowCaret();

        // 基于光标像素位置精确居中
        Platform.runLater(() -> {
            logArea.getCaretBounds().ifPresent(bounds -> {
                VirtualizedScrollPane<CodeArea> scrollPane = logScrollPaneSupplier != null ? logScrollPaneSupplier.get() : null;
                if (scrollPane == null) return;
                Bounds caretSceneBounds = logArea.localToScene(bounds);
                double caretCenterY = caretSceneBounds.getCenterY();
                double viewportCenterY = scrollPane.localToScene(0, scrollPane.getHeight() / 2).getY();
                double delta = caretCenterY - viewportCenterY;
                scrollPane.scrollYBy(delta);
            });
        });
    }

    // ==================== 增量更新 ====================

    /**
     * 增量搜索更新：扫描新追加的日志行，将匹配行号追加到磁盘匹配列表。
     *
     * @param newLines   新追加的行
     * @param keyword    搜索关键字
     * @param diskEndLine 当前磁盘文件末尾行号
     */
    public void incrementalSearchUpdate(List<String> newLines, String keyword, int diskEndLine) {
        if (newLines == null || newLines.isEmpty()) return;
        List<String> keywords = CommonUtils.parseSearchKeywords(keyword);
        if (keywords.isEmpty()) return;

        // 记住当前匹配行号，插入后据此修正索引偏移
        int currentLine = (currentDiskMatchIndex >= 0 && currentDiskMatchIndex < diskMatchLineNumbers.size())
                ? diskMatchLineNumbers.get(currentDiskMatchIndex) : -1;

        int startDiskLine = diskEndLine - newLines.size();

        for (int i = 0; i < newLines.size(); i++) {
            String line = newLines.get(i);
            boolean matches;
            if (searchAndMode) {
                matches = keywords.stream().allMatch(kw -> line.toLowerCase().contains(kw.toLowerCase()));
            } else {
                matches = keywords.stream().anyMatch(kw -> line.toLowerCase().contains(kw.toLowerCase()));
            }
            if (matches) {
                int newLine = startDiskLine + i;
                // 去重：避免与全量搜索结果重复
                int insertPos = Collections.binarySearch(diskMatchLineNumbers, newLine);
                if (insertPos < 0) {
                    diskMatchLineNumbers.add(~insertPos, newLine);
                }
            }
        }
        // 修正 currentDiskMatchIndex，确保仍指向同一匹配行
        if (currentLine >= 0 && !diskMatchLineNumbers.isEmpty()) {
            int idx = diskMatchLineNumbers.indexOf(currentLine);
            currentDiskMatchIndex = idx >= 0 ? idx : Math.min(currentDiskMatchIndex, diskMatchLineNumbers.size() - 1);
        }
        updateMatchLabel();
    }

    // ==================== 裁剪同步 ====================

    /**
     * 裁剪日志后同步更新磁盘搜索匹配列表。
     *
     * @param removedStart 被裁剪区域起始行号（0-based）
     * @param removedCount 被裁剪的行数
     */
    public void trimDiskMatches(int removedStart, int removedCount) {
        if (diskMatchLineNumbers.isEmpty()) return;

        int removedEnd = removedStart + removedCount;
        int currentLine = (currentDiskMatchIndex >= 0 && currentDiskMatchIndex < diskMatchLineNumbers.size())
                ? diskMatchLineNumbers.get(currentDiskMatchIndex) : -1;

        // 移除被裁剪范围内的匹配，并对之后的行号做偏移
        diskMatchLineNumbers.removeIf(line -> line >= removedStart && line < removedEnd);
        for (int i = 0; i < diskMatchLineNumbers.size(); i++) {
            int line = diskMatchLineNumbers.get(i);
            if (line >= removedEnd) {
                diskMatchLineNumbers.set(i, line - removedCount);
            }
        }

        if (diskMatchLineNumbers.isEmpty()) {
            currentDiskMatchIndex = -1;
        } else if (currentLine >= removedStart && currentLine < removedEnd) {
            currentDiskMatchIndex = 0;
        } else if (currentLine >= 0) {
            int adjustedCurrent = currentLine >= removedEnd ? currentLine - removedCount : currentLine;
            int idx = diskMatchLineNumbers.indexOf(adjustedCurrent);
            currentDiskMatchIndex = idx >= 0 ? idx : 0;
        } else {
            currentDiskMatchIndex = 0;
        }

        updateMatchLabel();
        applyCurrentMatchHighlight();
    }

    // ==================== 清除 ====================

    /** 磁盘文件截断时清除搜索结果 */
    public void clearOnFileTruncate() {
        diskMatchLineNumbers.clear();
        currentDiskMatchIndex = -1;
        lastKeyword = "";
    }

    /** 关闭搜索时清除全部搜索状态 */
    public void clearSearch() {
        searchGeneration.incrementAndGet();  // 使进行中的后台搜索结果失效
        diskMatchLineNumbers.clear();
        currentDiskMatchIndex = -1;
        lastKeyword = "";
        rehighlightLogArea(null);
        updateMatchLabel();
    }

    // ==================== UI ====================

    public void updateMatchLabel() {
        if (diskMatchLineNumbers.isEmpty()) {
            matchCountLabel.setText("0/0");
        } else {
            matchCountLabel.setText(formatCompact(currentDiskMatchIndex + 1) + "/" + formatCompact(diskMatchLineNumbers.size()));
        }
    }

    private static String formatCompact(int n) {
        return String.format("%,d", n);
    }

    // ==================== Getter / Setter ====================

    public void setSearchAndMode(boolean mode) { this.searchAndMode = mode; }
    public void resetLastKeyword() { this.lastKeyword = ""; }
}
