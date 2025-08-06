package com.longfor.lmk.k8slogviewer.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class SingleProcessManager {
    private static final AtomicReference<Process> currentProcess = new AtomicReference<>(null);
    private static final Map<Long, ExecutorService> executorMap = new HashMap<>();

    private SingleProcessManager() {
        throw new IllegalStateException("Utility class");
    }

    // 注册新进程，销毁旧进程树
    public static synchronized void register(Process newProcess, ExecutorService newExecutor) {
        executorMap.put(newProcess.pid(), newExecutor);
        Process oldProcess = currentProcess.getAndSet(newProcess);
        if (oldProcess != null && oldProcess.isAlive()) {
            log.info("检测到旧进程存在，准备销毁进程树...");
            destroyProcessTree(oldProcess);
            long pid = oldProcess.pid();
            ExecutorService executorService = executorMap.get(pid);
            if (!executorService.isShutdown()) {
                log.info("-----------进程pid[{}], 线程终止------------", pid);
                executorService.shutdown();
                executorMap.remove(pid);
            }
        }
        log.info("新进程已注册，PID={}", newProcess.pid());
    }

    // 取消注册
    public static synchronized void unregister(Process process) {
        boolean removed = currentProcess.compareAndSet(process, null);
        if (removed) {
            log.info("进程注销成功，PID={}", process.pid());
            destroyProcessTree(process);
        }
    }

    // 销毁整个进程树（适用于 JDK 9+）
    private static void destroyProcessTree(Process process) {
        long pid = process.pid();
        Optional<ProcessHandle> handleOpt = ProcessHandle.of(pid);
        if (handleOpt.isEmpty()) {
            log.warn("无法获取进程句柄，直接强制销毁 PID={}", pid);
            process.destroyForcibly();
            return;
        }

        ProcessHandle handle = handleOpt.get();

        // 先销毁所有子进程
        handle.descendants().forEach(child -> {
            log.info("终止子进程 PID={}", child.pid());
            child.destroy();
        });

        // 再销毁主进程
        log.info("终止主进程 PID={}", pid);
        handle.destroy();
    }

    // JVM 退出时，销毁当前注册的进程
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process process = currentProcess.get();
            if (process != null && process.isAlive()) {
                log.info("JVM 退出时发现活跃进程，开始销毁...");
                destroyProcessTree(process);
            }
        }));
    }
}
