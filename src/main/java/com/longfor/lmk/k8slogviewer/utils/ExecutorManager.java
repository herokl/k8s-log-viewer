package com.longfor.lmk.k8slogviewer.utils;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一线程池管理器，负责应用内所有异步任务的线程池生命周期管理。
 * 应用退出时调用 {@link #shutdownAll()} 优雅关闭所有线程池。
 */
public final class ExecutorManager {

    private static final AtomicInteger POOL_COUNTER = new AtomicInteger(0);

    // Debounce 调度线程池（单线程，用于搜索防抖）
    private static ScheduledExecutorService debounceExecutor;

    // 日志刷新调度线程池（单线程，50ms 周期刷新 UI）
    private static ScheduledExecutorService logFlushExecutor;

    // 通用异步任务线程池（CPU 核数，用于文件下载、树加载等一次性任务）
    private static ExecutorService workerExecutor;

    private ExecutorManager() {
        throw new IllegalStateException("Utility class");
    }

    static {
        debounceExecutor = newSingleThreadScheduled("k8s-debounce-");
        workerExecutor = newWorkerPool();
        Runtime.getRuntime().addShutdownHook(new Thread(ExecutorManager::shutdownAll));
    }

    // ==================== 获取线程池 ====================

    /**
     * 获取 Debounce 调度线程池，用于搜索输入防抖
     */
    public static ScheduledExecutorService getDebounceExecutor() {
        if (debounceExecutor == null || debounceExecutor.isShutdown()) {
            debounceExecutor = newSingleThreadScheduled("k8s-debounce-");
        }
        return debounceExecutor;
    }

    /**
     * 获取日志刷新调度线程池
     */
    public static ScheduledExecutorService getLogFlushExecutor() {
        if (logFlushExecutor == null || logFlushExecutor.isShutdown()) {
            logFlushExecutor = newSingleThreadScheduled("k8s-log-flush-");
        }
        return logFlushExecutor;
    }

    /**
     * 重新创建日志刷新线程池（停止旧的）
     */
    public static ScheduledExecutorService restartLogFlushExecutor() {
        stopLogFlushExecutor();
        logFlushExecutor = newSingleThreadScheduled("k8s-log-flush-");
        return logFlushExecutor;
    }

    /**
     * 停止日志刷新线程池
     */
    public static void stopLogFlushExecutor() {
        shutdownExecutor(logFlushExecutor);
        logFlushExecutor = null;
    }

    /**
     * 获取通用工作线程池
     */
    public static ExecutorService getWorkerExecutor() {
        if (workerExecutor == null || workerExecutor.isShutdown()) {
            workerExecutor = newWorkerPool();
        }
        return workerExecutor;
    }

    /**
     * 提交异步任务到工作线程池
     */
    public static Future<?> submit(Runnable task) {
        return getWorkerExecutor().submit(task);
    }

    /**
     * 提交带返回值的异步任务
     */
    public static <T> Future<T> submit(Callable<T> task) {
        return getWorkerExecutor().submit(task);
    }

    // ==================== Debounce 辅助 ====================

    /**
     * 取消 debounce 上一个待执行任务并重新调度
     *
     * @param task     要执行的任务
     * @param delay    延迟时间
     * @param unit     时间单位
     */
    public static void debounce(Runnable task, long delay, TimeUnit unit) {
        getDebounceExecutor().shutdownNow();
        debounceExecutor = newSingleThreadScheduled("k8s-debounce-");
        debounceExecutor.schedule(task, delay, unit);
    }

    // ==================== 生命周期管理 ====================

    /**
     * 优雅关闭所有线程池（JVM 退出时自动调用）
     */
    public static void shutdownAll() {
        shutdownExecutor(debounceExecutor);
        shutdownExecutor(logFlushExecutor);
        shutdownExecutor(workerExecutor);
        debounceExecutor = null;
        logFlushExecutor = null;
        workerExecutor = null;
    }

    private static void shutdownExecutor(ExecutorService executor) {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================== 私有工厂方法 ====================

    private static ScheduledExecutorService newSingleThreadScheduled(String prefix) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, prefix + POOL_COUNTER.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    private static ExecutorService newWorkerPool() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Executors.newFixedThreadPool(cores, r -> {
            Thread t = new Thread(r, "k8s-worker-" + POOL_COUNTER.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }
}
