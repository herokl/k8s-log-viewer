package com.longfor.lmk.k8slogviewer.controller;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import com.longfor.lmk.k8slogviewer.service.PodLogFileManager;
import com.longfor.lmk.k8slogviewer.utils.CommonUtils;
import com.longfor.lmk.k8slogviewer.utils.ExecutorManager;
import com.longfor.lmk.k8slogviewer.utils.LogStyleUtil;
import javafx.application.Platform;
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

    /** 用户选中的关键字（通过标签单击选中），导航时优先跳转此关键字的匹配位置 */
    private String selectedKeyword = null;
    /** 选中关键字的匹配行号列表（用于导航时只跳转到包含选中关键字的行） */
    private final List<Integer> selectedKeywordMatchLines = new ArrayList<>();
    private int selectedMatchIndex = -1;

    /** 后台搜索代际，用于取消过时的搜索结果 */
    private final AtomicLong searchGeneration = new AtomicLong(0);

    /** 截断后等待重搜索完成的标志，防止中间状态泄露到 UI */
    private volatile boolean truncateRefreshPending = false;

    /** 导航冷却时间戳，findNext/findPrev 后短时间内抑制定时搜索刷新的 applyCurrentMatchHighlight */
    private volatile long navigationCooldownUntil = 0;

    /** 上一次橙色高亮的位置列表，用于导航时清除旧高亮（多关键字时可能有多个） */
    private final java.util.List<int[]> lastHighlightedRanges = new java.util.ArrayList<>();

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
                if (gen != searchGeneration.get()) {
                    return;
                }

                diskMatchLineNumbers.clear();
                diskMatchLineNumbers.addAll(result.matchedLineNumbers);
                lastKeyword = keyword;
                // 截断重搜索完成，解除抑制
                truncateRefreshPending = false;

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

                // 如果有选中关键字，更新选中关键字的匹配列表
                if (selectedKeyword != null && !selectedKeyword.isBlank()) {
                    searchSelectedKeywordInBackground(selectedKeyword);
                }

                if (navigateToFirst && !diskMatchLineNumbers.isEmpty()) {
                    rehighlightLogArea(keyword);
                    navigateToDiskMatch();
                } else {
                    // 非导航模式：仅刷新当前匹配行的高亮，不重算全文样式也不移动光标
                    // 导航冷却期内跳过，避免覆盖用户刚进行的跳转
                    boolean inCooldown = System.currentTimeMillis() < navigationCooldownUntil;
                    if (inCooldown) {
                        // 导航冷却期内跳过，避免覆盖用户刚进行的跳转
                    } else {
                        applyCurrentMatchHighlight();
                    }
                }
            });
        });
    }

    /** 跳转到下一个匹配 */
    public void findNext() {
        searchGeneration.incrementAndGet();
        selectedSearchGeneration.incrementAndGet();  // 取消进行中的选中关键字搜索回调
        navigationCooldownUntil = System.currentTimeMillis() + 2000;
        if (selectedKeyword != null && !selectedKeywordMatchLines.isEmpty()) {
            selectedMatchIndex = (selectedMatchIndex + 1) % selectedKeywordMatchLines.size();
            navigateToSelectedKeywordMatch();
        } else if (!diskMatchLineNumbers.isEmpty()) {
            currentDiskMatchIndex = (currentDiskMatchIndex + 1) % diskMatchLineNumbers.size();
            navigateToDiskMatch();
        }
    }

    /** 跳转到上一个匹配 */
    public void findPrev() {
        searchGeneration.incrementAndGet();
        selectedSearchGeneration.incrementAndGet();  // 取消进行中的选中关键字搜索回调
        navigationCooldownUntil = System.currentTimeMillis() + 2000;
        if (selectedKeyword != null && !selectedKeywordMatchLines.isEmpty()) {
            selectedMatchIndex = (selectedMatchIndex - 1 + selectedKeywordMatchLines.size()) % selectedKeywordMatchLines.size();
            navigateToSelectedKeywordMatch();
        } else if (!diskMatchLineNumbers.isEmpty()) {
            currentDiskMatchIndex = (currentDiskMatchIndex - 1 + diskMatchLineNumbers.size()) % diskMatchLineNumbers.size();
            navigateToDiskMatch();
        }
    }

    /** 导航到选中关键字的匹配行 */
    private void navigateToSelectedKeywordMatch() {
        if (selectedMatchIndex < 0 || selectedMatchIndex >= selectedKeywordMatchLines.size()) return;
        int targetLine = selectedKeywordMatchLines.get(selectedMatchIndex);

        int viewStart = viewStartLineSupplier != null ? viewStartLineSupplier.get() : 0;
        int viewEnd = viewEndLineSupplier != null ? viewEndLineSupplier.get() : 0;

        if (targetLine >= viewStart && targetLine < viewEnd) {
            int localLine = targetLine - viewStart;
            highlightAndSelectLine(localLine, lastKeyword);
            updateMatchLabel();
        } else {
            lastHighlightedRanges.clear();
            if (viewLoader != null) {
                String podName = AppConfig.getK8sQuery().getPodName();
                viewLoader.loadView(podName, targetLine, () -> {
                    int newViewStart = viewStartLineSupplier.get();
                    int localLine = targetLine - newViewStart;
                    int newParaCount = logArea.getParagraphs().size();
                    if (localLine >= 0 && localLine < newParaCount) {
                        highlightAndSelectLine(localLine, lastKeyword);
                    }
                    updateMatchLabel();
                });
            }
        }
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
        if (currentDiskMatchIndex < 0 || currentDiskMatchIndex >= diskMatchLineNumbers.size()) {
            return;
        }

        int targetLine = diskMatchLineNumbers.get(currentDiskMatchIndex);
        String keyword = lastKeyword;

        int viewStart = viewStartLineSupplier != null ? viewStartLineSupplier.get() : 0;
        int viewEnd = viewEndLineSupplier != null ? viewEndLineSupplier.get() : 0;

        if (targetLine >= viewStart && targetLine < viewEnd) {
            int localLine = targetLine - viewStart;
            highlightAndSelectLine(localLine, keyword);
            updateMatchLabel();
        } else {
            // 视图切换前重置橙色高亮追踪，因为文本内容将完全替换
            lastHighlightedRanges.clear();
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

    /** 清除上一个"当前匹配"的橙色高亮，恢复为各关键字对应的搜索高亮颜色 */
    private void clearPreviousCurrentHighlight() {
        if (lastHighlightedRanges.isEmpty()) return;
        for (int[] range : lastHighlightedRanges) {
            int start = range[0];
            int len = range[1];
            int kwIdx = range[2];
            // 恢复为对应关键字的原始高亮颜色（search-highlight-0, search-highlight-1, ...）
            String styleClass = "search-highlight-" + (kwIdx % LogStyleUtil.SEARCH_HIGHLIGHT_COLORS);
            Set<String> normalStyles = Set.of("plain-text", styleClass);
            StyleSpansBuilder<Collection<String>> ssb = new StyleSpansBuilder<>();
            ssb.add(normalStyles, len);
            logArea.setStyleSpans(start, ssb.create());
        }
        lastHighlightedRanges.clear();
    }

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

    /** 为当前导航到的匹配项应用橙色高亮样式（所有关键字都变橙色） */
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

        clearPreviousCurrentHighlight();

        List<String> keywords = CommonUtils.parseSearchKeywords(keyword);
        for (int ki = 0; ki < keywords.size(); ki++) {
            String kw = keywords.get(ki);
            String lowerKw = kw.toLowerCase();
            int idx = 0;
            while ((idx = lowerText.indexOf(lowerKw, idx)) != -1) {
                int start = logArea.getAbsolutePosition(localLine, idx);
                String styleClass = "search-highlight-current-" + (ki % LogStyleUtil.SEARCH_HIGHLIGHT_COLORS);
                Set<String> currentStyles = Set.of("plain-text", styleClass);
                StyleSpansBuilder<Collection<String>> ssb = new StyleSpansBuilder<>();
                ssb.add(currentStyles, kw.length());
                logArea.setStyleSpans(start, ssb.create());
                lastHighlightedRanges.add(new int[]{start, kw.length(), ki});
                idx += kw.length();
            }
        }
    }

    /**
     * 高亮并选中指定行中的搜索关键字。
     *
     * @param localLine 段落索引（0-based）
     * @param keyword   搜索关键字
     */
    private void highlightAndSelectLine(int localLine, String keyword) {
        if (localLine < 0 || localLine >= logArea.getParagraphs().size()) {
            return;
        }

        String lineText = logArea.getParagraph(localLine).getText();
        String lowerText = lineText.toLowerCase();

        // 导航时不重算全文高亮（rehighlightLogArea），仅设置当前匹配行的橙色样式
        // 全文高亮在搜索关键字变化时已由 rehighlightLogArea 设置，无需重复

        // 先清除上一个橙色高亮，恢复为普通搜索高亮
        clearPreviousCurrentHighlight();

        List<String> keywords = CommonUtils.parseSearchKeywords(keyword);

        // 收集当前行中所有关键字的匹配位置，按位置排序
        List<int[]> allMatches = new java.util.ArrayList<>();  // [start, length, kwIndex]
        for (int ki = 0; ki < keywords.size(); ki++) {
            String kw = keywords.get(ki);
            String lowerKw = kw.toLowerCase();
            int idx = 0;
            while ((idx = lowerText.indexOf(lowerKw, idx)) != -1) {
                allMatches.add(new int[]{idx, kw.length(), ki});
                idx += kw.length();
            }
        }
        allMatches.sort((a, b) -> Integer.compare(a[0], b[0]));

        if (!allMatches.isEmpty()) {
            lastHighlightedRanges.clear();
            // 先找选中关键字的匹配位置，作为优先滚动目标
            int selectedStart = -1;
            int selectedEnd = -1;
            // 为每个匹配设置橙色高亮，记录原始样式以便恢复
            for (int[] match : allMatches) {
                int start = logArea.getAbsolutePosition(localLine, match[0]);
                String matchedKw = keywords.get(match[2]);
                int kwLen = matchedKw.length();
                String styleClass = "search-highlight-current-" + (match[2] % LogStyleUtil.SEARCH_HIGHLIGHT_COLORS);
                Set<String> currentStyles = Set.of("plain-text", styleClass);
                StyleSpansBuilder<Collection<String>> ssb = new StyleSpansBuilder<>();
                ssb.add(currentStyles, kwLen);
                logArea.setStyleSpans(start, ssb.create());
                lastHighlightedRanges.add(new int[]{start, kwLen, match[2]});

                // 记录选中关键字的第一个匹配
                if (selectedStart < 0 && selectedKeyword != null && matchedKw.equals(selectedKeyword)) {
                    selectedStart = start;
                    selectedEnd = start + kwLen;
                }
            }
            // 选中：优先选中关键字 → 兜底选第一个匹配
            int selectStart;
            int selectEnd;
            if (selectedStart >= 0) {
                selectStart = selectedStart;
                selectEnd = selectedEnd;
            } else {
                int[] first = allMatches.get(0);
                selectStart = logArea.getAbsolutePosition(localLine, first[0]);
                selectEnd = selectStart + keywords.get(first[2]).length();
            }
            if (selectStart >= 0) {
                logArea.selectRange(selectStart, selectEnd);
            }
            logArea.requestFocus();
            // 将关键字字符位置贴顶，确保长行换行时关键字可见
            if (selectStart >= 0) {
                scrollToCharPosition(selectStart);
            }
        } else {
            logArea.moveTo(localLine, 0);
            logArea.requestFocus();
            // fallback 到段落贴顶
            scrollToCharPosition(logArea.getAbsolutePosition(localLine, 0));
        }
    }

    /**
     * 将关键字所在位置滚动到视口顶部附近。
     * 策略：先用 requestFollowCaret 让关键字可见（通常出现在视口底部），
     * 再向上滚动约一个视口高度，将关键字移到视口顶部。
     * 解决长行换行后 showParagraphAtTop 无法保证关键字可见的问题。
     */
    private void scrollToCharPosition(int charIndex) {
        Platform.runLater(() -> {
            logArea.moveTo(charIndex);
            logArea.requestFollowCaret();

            // 等布局完成后，将关键字从视口底部调整到顶部
            Platform.runLater(() -> {
                if (logScrollPaneSupplier != null) {
                    VirtualizedScrollPane<CodeArea> scrollPane = logScrollPaneSupplier.get();
                    if (scrollPane != null) {
                        // requestFollowCaret 把关键字放在视口底部附近，
                        // 向上滚动 (viewportHeight - 50px) 使关键字贴近顶部
                        scrollPane.scrollYBy(-(scrollPane.getHeight() - 50));
                    }
                }
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
        if (truncateRefreshPending) return;  // 截断重搜索期间跳过增量更新
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
     * 视图裁剪后同步更新磁盘搜索匹配列表。
     * <p>
     * 注意：trimLogArea 只是从 CodeArea 视图中移除旧行，磁盘文件并未改变。
     * diskMatchLineNumbers 存储的是磁盘文件的绝对行号，不应做偏移调整。
     * 被裁剪出视图的匹配行仍然存在于磁盘文件中，navigateToDiskMatch
     * 会在需要时通过 loadViewFromDisk 重新加载它们。
     * <p>
     * 此方法仅移除已超出视图范围的匹配（由定时搜索刷新补回），
     * 并修正 currentDiskMatchIndex 使其仍指向正确的匹配项。
     *
     * @param removedStart 被裁剪区域起始行号（0-based，磁盘行号）
     * @param removedCount 被裁剪的行数
     */
    public void trimDiskMatches(int removedStart, int removedCount) {
        if (diskMatchLineNumbers.isEmpty()) return;

        int removedEnd = removedStart + removedCount;
        int currentLine = (currentDiskMatchIndex >= 0 && currentDiskMatchIndex < diskMatchLineNumbers.size())
                ? diskMatchLineNumbers.get(currentDiskMatchIndex) : -1;

        // 仅移除被裁剪出视图的匹配项（定时搜索会补回），不对剩余行号做偏移
        // 因为磁盘文件未变，行号仍是绝对磁盘行号
        diskMatchLineNumbers.removeIf(line -> line >= removedStart && line < removedEnd);

        // 修正 currentDiskMatchIndex
        if (diskMatchLineNumbers.isEmpty()) {
            currentDiskMatchIndex = -1;
        } else if (currentLine >= removedStart && currentLine < removedEnd) {
            // 当前匹配被移除，定位到第一个匹配
            currentDiskMatchIndex = 0;
        } else if (currentLine >= 0) {
            // 当前匹配仍在列表中，找到它的位置
            int idx = diskMatchLineNumbers.indexOf(currentLine);
            currentDiskMatchIndex = idx >= 0 ? idx : Math.min(currentDiskMatchIndex, diskMatchLineNumbers.size() - 1);
        } else {
            currentDiskMatchIndex = 0;
        }

        updateMatchLabel();
        applyCurrentMatchHighlight();
    }

    // ==================== 清除 ====================

    /** 磁盘文件截断时标记待重搜索，不立即清空避免闪烁 */
    public void clearOnFileTruncate() {
        truncateRefreshPending = true;
    }

    /** 关闭搜索时清除全部搜索状态 */
    public void clearSearch() {
        searchGeneration.incrementAndGet();  // 使进行中的后台搜索结果失效
        selectedSearchGeneration.incrementAndGet();
        diskMatchLineNumbers.clear();
        currentDiskMatchIndex = -1;
        lastKeyword = "";
        lastHighlightedRanges.clear();
        selectedKeyword = null;
        selectedKeywordMatchLines.clear();
        selectedMatchIndex = -1;
        rehighlightLogArea(null);
        updateMatchLabel();
    }

    // ==================== UI ====================

    public void updateMatchLabel() {
        if (truncateRefreshPending) return;  // 截断重搜索期间冻结显示，避免闪烁
        if (selectedKeyword != null && !selectedKeywordMatchLines.isEmpty()) {
            matchCountLabel.setText(formatCompact(selectedMatchIndex + 1) + "/" + formatCompact(selectedKeywordMatchLines.size()));
        } else if (diskMatchLineNumbers.isEmpty()) {
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

    /** 设置选中的关键字，导航时优先跳转此关键字的匹配位置；传 null 清除选中 */
    public void setSelectedKeyword(String keyword) {
        this.selectedKeyword = keyword;
        if (keyword == null || keyword.isBlank()) {
            selectedKeywordMatchLines.clear();
            selectedMatchIndex = -1;
            return;
        }
        // 后台搜索选中关键字的匹配行
        searchSelectedKeywordInBackground(keyword);
    }

    /** 选中关键字搜索的代际，独立于主搜索代际，findNext/findPrev 时递增以取消过时回调 */
    private final AtomicLong selectedSearchGeneration = new AtomicLong(0);

    private void searchSelectedKeywordInBackground(String keyword) {
        String podName = AppConfig.getK8sQuery().getPodName();
        if (podName == null) return;
        long gen = selectedSearchGeneration.incrementAndGet();  // 递增，使之前的回调失效
        ExecutorManager.submit(() -> {
            PodLogFileManager.DiskSearchResult result = fileManager.searchInLogFile(podName, keyword, false);
            Platform.runLater(() -> {
                if (gen != selectedSearchGeneration.get()) return;  // 过时则丢弃

                // 记住用户当前导航到的行号，回调后尽量保持位置
                int currentLine = (selectedMatchIndex >= 0 && selectedMatchIndex < selectedKeywordMatchLines.size())
                        ? selectedKeywordMatchLines.get(selectedMatchIndex) : -1;

                selectedKeywordMatchLines.clear();
                selectedKeywordMatchLines.addAll(result.matchedLineNumbers);

                if (selectedKeywordMatchLines.isEmpty()) {
                    selectedMatchIndex = -1;
                } else if (currentLine >= 0) {
                    // 尝试保持用户当前导航位置
                    int idx = selectedKeywordMatchLines.indexOf(currentLine);
                    selectedMatchIndex = idx >= 0 ? idx : Math.min(selectedMatchIndex, selectedKeywordMatchLines.size() - 1);
                } else {
                    // 首次搜索，跳到第一个
                    selectedMatchIndex = 0;
                    navigateToSelectedKeywordMatch();
                }
                updateMatchLabel();
            });
        });
    }

    public void resetLastKeyword() { this.lastKeyword = ""; }
}
