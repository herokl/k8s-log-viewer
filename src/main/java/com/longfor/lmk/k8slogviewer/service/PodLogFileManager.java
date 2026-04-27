package com.longfor.lmk.k8slogviewer.service;

import com.longfor.lmk.k8slogviewer.config.AppPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.longfor.lmk.k8slogviewer.utils.CommonUtils.parseSearchKeywords;

public class PodLogFileManager {

    private static final Logger log = LoggerFactory.getLogger(PodLogFileManager.class);
    private static final String LOG_ROOT = Paths.get("logs") + "/k8s_log_viewer";

    // 单例引用，供 LogCleaner 判断当前正在写入的文件
    private static volatile PodLogFileManager instance;

    private BufferedWriter writer;
    private Path currentLogFile;
    private long lastSizeCheckTime = 0;
    private static final long SIZE_CHECK_INTERVAL_MS = 5000; // 5秒检查一次

    /**
     * 文件截断回调：当 checkAndCleanSizeLimit 截断文件后通知监听者。
     * 参数为被截断的行数（从文件开头删除的行数）。
     */
    private volatile IntConsumer onFileTruncated;

    /**
     * 设置文件截断回调。
     * @param callback 参数为从文件开头删除的行数
     */
    public void setOnFileTruncated(IntConsumer callback) {
        this.onFileTruncated = callback;
    }

    /**
     * 关闭时清理所有 Pod 的历史日志文件，每个 Pod 只保留最新的一份。
     * 同时截断保留文件的内容，仅保留最新的 1000 行。
     * 清理完成后删除所有空目录。
     */
    public void cleanAllButLatest() {
        close();

        Path podLogRoot = Paths.get(LOG_ROOT);
        if (!Files.exists(podLogRoot)) return;

        try (Stream<Path> podDirs = Files.list(podLogRoot)) {
            podDirs.filter(Files::isDirectory).forEach(podDir -> {
                try (Stream<Path> logFiles = Files.list(podDir)
                        .filter(p -> p.toString().endsWith(".log"))) {

                    List<Path> files = logFiles.sorted((a, b) ->
                            Long.compare(b.toFile().lastModified(), a.toFile().lastModified())
                    ).toList();

                    // 保留最新的文件，删除其余
                    for (int i = 1; i < files.size(); i++) {
                        Files.deleteIfExists(files.get(i));
                        log.info("关闭清理：删除旧日志 {}", files.get(i).getFileName());
                    }

                    // 截断保留文件，仅保留最新 1000 行
                    if (!files.isEmpty()) {
                        Path latestFile = files.get(0);
                        List<String> allLines = Files.readAllLines(latestFile);
                        if (allLines.size() > 1000) {
                            List<String> recentLines = allLines.subList(allLines.size() - 1000, allLines.size());
                            Files.write(latestFile, recentLines, StandardOpenOption.TRUNCATE_EXISTING);
                            log.info("关闭清理：截断 {} 保留最新 1000 行", latestFile.getFileName());
                        }
                    }
                } catch (IOException e) {
                    log.warn("关闭清理 Pod 目录失败: {}", podDir, e);
                }
            });
        } catch (IOException e) {
            log.warn("关闭清理日志目录失败: {}", podLogRoot, e);
        }

        // 清理空目录（按深度逆序删除，确保嵌套空目录能删干净）
        cleanEmptyDirs(podLogRoot);
    }

    /**
     * 递归删除指定路径下的所有空目录（深层优先）。
     */
    private void cleanEmptyDirs(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isDirectory)
                    .filter(dir -> isEmptyDirectory(dir))
                    .sorted(java.util.Comparator.comparingInt(Path::getNameCount).reversed())
                    .forEach(dir -> {
                        try {
                            Files.deleteIfExists(dir);
                            log.info("关闭清理：删除空目录 {}", dir);
                        } catch (IOException e) {
                            // ignore
                        }
                    });
        } catch (IOException e) {
            log.warn("清理空目录失败: {}", root, e);
        }
    }

    private boolean isEmptyDirectory(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    public PodLogFileManager() {
        instance = this;
    }

    public synchronized void switchPod(String podName) throws IOException {
        close();

        String fileName = String.format("%s_%s.log",
                podName,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));

        Path dir = Paths.get(LOG_ROOT, podName);
        Files.createDirectories(dir);

        this.currentLogFile = dir.resolve(fileName);
        this.writer = new BufferedWriter(new FileWriter(currentLogFile.toFile(), true));
        this.lastSizeCheckTime = System.currentTimeMillis();

        // 切换 Pod 时清理旧文件（按时间）
        cleanOldFiles(dir, Duration.ofDays(2));
    }

    public synchronized void append(String line) {
        if (writer == null) return;
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();

            // 定期检查目录总大小
            long now = System.currentTimeMillis();
            if (now - lastSizeCheckTime > SIZE_CHECK_INTERVAL_MS) {
                checkAndCleanSizeLimit();
                lastSizeCheckTime = now;
            }
        } catch (IOException e) {
            log.error("写入日志失败: {}", e.getMessage());
        }
    }

    public synchronized void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                log.debug("关闭日志写入器时出错", e);
            }
        }
        writer = null;
        currentLogFile = null;
    }

    public Path getCurrentLogFile() {
        return currentLogFile;
    }

    /**
     * 获取当前正在写入的日志文件路径（静态访问，供 LogCleaner 使用）。
     */
    public static Path getCurrentActiveLogFile() {
        return instance != null ? instance.currentLogFile : null;
    }

    /**
     * 获取当前 Pod 最近的日志文件路径（按修改时间排序，取最新的）。
     */
    public Path getLatestLogFile(String podName) {
        Path dir = Paths.get(LOG_ROOT, podName);
        if (!Files.exists(dir)) return null;

        try (Stream<Path> paths = Files.list(dir)) {
            return paths
                    .filter(p -> p.toString().endsWith(".log"))
                    .max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            log.warn("列出日志目录失败: {}", dir, e);
            return null;
        }
    }

    /**
     * 从磁盘日志文件中读取指定行范围的日志（从后往前加载历史）。
     *
     * @param podName   Pod 名称
     * @param fromEnd   从文件末尾倒数第几行开始读取（0=最后一行）
     * @param count     读取的行数
     * @return          日志行列表，按文件中的顺序（从旧到新）
     */
    public synchronized List<String> readLogLinesFromEnd(String podName, int fromEnd, int count) {
        Path logFile = getLatestLogFile(podName);
        if (logFile == null) return Collections.emptyList();

        try (Stream<String> lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
            List<String> allLines = lines.collect(Collectors.toList());
            int total = allLines.size();
            int start = Math.max(0, total - fromEnd - count);
            int end = total - fromEnd;
            if (end <= 0 || start >= total) return Collections.emptyList();
            return new ArrayList<>(allLines.subList(start, end));
        } catch (IOException e) {
            log.warn("读取日志文件失败: {}", logFile, e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取当前 Pod 最新日志文件的总行数。
     *
     * @param podName Pod 名称
     * @return 总行数，文件不存在或读取失败返回 0
     */
    public synchronized int getLineCount(String podName) {
        Path logFile = getLatestLogFile(podName);
        if (logFile == null) return 0;

        try (Stream<String> lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
            return (int) lines.count();
        } catch (IOException e) {
            log.warn("统计日志行数失败: {}", logFile, e);
            return 0;
        }
    }

    /**
     * 从磁盘日志文件中按行号范围读取日志。
     *
     * @param podName   Pod 名称
     * @param startLine 起始行号（0-based，包含）
     * @param count     读取行数
     * @return          日志行列表
     */
    public synchronized List<String> readLogLines(String podName, int startLine, int count) {
        Path logFile = getLatestLogFile(podName);
        if (logFile == null) return Collections.emptyList();

        try (Stream<String> lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
            List<String> result = lines.skip(startLine).limit(count).collect(Collectors.toList());
            return result;
        } catch (IOException e) {
            log.warn("读取日志文件失败: {}", logFile, e);
            return Collections.emptyList();
        }
    }

    /**
     * 在磁盘日志文件中搜索关键字，返回所有匹配的行号（0-based）。
     *
     * @param podName Pod 名称
     * @param keyword 搜索关键字
     * @return 匹配的行号列表，以及文件总行数
     */
    public synchronized DiskSearchResult searchInLogFile(String podName, String keyword, boolean andMode) {
        Path logFile = getLatestLogFile(podName);
        if (logFile == null || keyword == null || keyword.isBlank()) {
            return new DiskSearchResult(Collections.emptyList(), 0);
        }

        // 支持多关键字搜索：支持引号语法
        List<String> keywords = parseSearchKeywords(keyword);
        List<String> lowerKeywords = new ArrayList<>();
        for (String kw : keywords) {
            lowerKeywords.add(kw.toLowerCase());
        }
        if (lowerKeywords.isEmpty()) {
            return new DiskSearchResult(Collections.emptyList(), 0);
        }

        List<Integer> matchedLines = new ArrayList<>();
        int totalLines = 0;

        try (Stream<String> lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
            Iterable<String> iterable = lines::iterator;
            for (String line : iterable) {
                String lowerLine = line.toLowerCase();
                if (andMode && lowerKeywords.size() > 1) {
                    // 且模式：行中必须包含所有关键字
                    boolean allMatch = true;
                    for (String lowerKw : lowerKeywords) {
                        if (!lowerLine.contains(lowerKw)) {
                            allMatch = false;
                            break;
                        }
                    }
                    if (allMatch) {
                        matchedLines.add(totalLines);
                    }
                } else {
                    // 或模式：行中包含任一关键字即为匹配
                    for (String lowerKw : lowerKeywords) {
                        if (lowerLine.contains(lowerKw)) {
                            matchedLines.add(totalLines);
                            break;
                        }
                    }
                }
                totalLines++;
            }
        } catch (IOException e) {
            log.warn("搜索日志文件失败: {}", logFile, e);
        }

        return new DiskSearchResult(matchedLines, totalLines);
    }

    /**
     * 磁盘搜索结果
     */
    public static class DiskSearchResult {
        public final List<Integer> matchedLineNumbers;
        public final int totalLines;

        public DiskSearchResult(List<Integer> matchedLineNumbers, int totalLines) {
            this.matchedLineNumbers = matchedLineNumbers;
            this.totalLines = totalLines;
        }
    }

    /**
     * 检查当前日志文件大小是否超限，超限则清除历史日志，保留最新部分
     */
    private void checkAndCleanSizeLimit() {
        if (currentLogFile == null || writer == null) {
            return;
        }

        try {
            File file = currentLogFile.toFile();
            long currentSize = file.length();
            int maxMB = AppPreferences.getMaxLogSizeMB();
            long maxBytes = (long) maxMB * 1024 * 1024;

            if (currentSize > maxBytes) {
                log.info("当前日志文件超过 {} MB ({} MB)，清除历史日志: {}", maxMB, currentSize / 1024 / 1024, currentLogFile.getFileName());
                
                // 关闭当前 writer
                writer.close();
                writer = null;
                
                // 读取文件所有行
                List<String> allLines = Files.readAllLines(currentLogFile);
                
                // 计算需要保留的行数（保留最新的，目标是控制在最大容量的50%左右）
                int linesToKeep = Math.max(100, allLines.size() / 2);
                List<String> recentLines = allLines.stream()
                        .skip(Math.max(0, allLines.size() - linesToKeep))
                        .toList();
                
                // 重新写入文件，只保留最新的日志
                Files.write(currentLogFile, recentLines, StandardOpenOption.TRUNCATE_EXISTING);
                
                // 重新打开 writer
                writer = new BufferedWriter(new FileWriter(currentLogFile.toFile(), true));
                
                log.info("已清除历史日志，保留最新 {} 行，当前大小约 {} MB", recentLines.size(), file.length() / 1024 / 1024);
                
                // 通知监听者文件已被截断
                int removedLines = allLines.size() - linesToKeep;
                if (onFileTruncated != null) {
                    onFileTruncated.accept(removedLines);
                }
            }
        } catch (IOException e) {
            log.warn("检查或清除历史日志时出错: {}", e.getMessage());
        }
    }

    /**
     * 删除超过 retention 的旧文件
     */
    private void cleanOldFiles(Path dir, Duration retention) {
        try {
            Files.list(dir).forEach(path -> {
                try {
                    File file = path.toFile();
                    Instant lastModified = Instant.ofEpochMilli(file.lastModified());
                    Instant cutoff = Instant.now().minus(retention);
                    if (lastModified.isBefore(cutoff)) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException e) {
                    log.debug("删除旧文件失败: {}", path, e);
                }
            });
        } catch (IOException e) {
            log.debug("列出目录失败: {}", dir, e);
        }
    }
}
