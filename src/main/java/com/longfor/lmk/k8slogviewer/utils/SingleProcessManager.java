package com.longfor.lmk.k8slogviewer.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class SingleProcessManager {
    private static final AtomicReference<Process> currentProcess = new AtomicReference<>(null);

    private SingleProcessManager() {
        throw new IllegalStateException("Utility class");
    }

    // 注册新进程，先销毁旧的
    public static synchronized void register(Process newProcess) {
        Process oldProcess = currentProcess.getAndSet(newProcess);
        if (oldProcess != null && oldProcess.isAlive()) {
            log.info("销毁旧的进程");
            oldProcess.destroyForcibly();
        }
    }

    // 取消注册
    public static synchronized void unregister(Process process) {
        currentProcess.compareAndSet(process, null);
    }

    // JVM退出时销毁
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process process = currentProcess.get();
            if (process != null && process.isAlive()) {
                System.out.println("JVM退出，销毁活跃进程...");
                process.destroyForcibly();
            }
        }));
    }
}

