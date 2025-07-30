package com.longfor.lmk.k8slogviewer.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具类：使用 Git 提供的 bash.exe 执行 kubectl 命令，支持 grep -C 和时间范围过滤。
 */
public class KubectlLogFetcher {

    private static String bashPath = null;

    /**
     * 检查 Git 的 bash.exe 是否可用（Windows 环境）
     *
     * @return bash.exe 路径，若不可用则返回 null
     */
    public static String findBashPath() {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return "/bin/bash"; // 非 Windows 环境使用系统 bash
        }
        // 检查默认 Git 路径
        String defaultPath = "C:\\Program Files\\Git\\bin\\bash.exe";
        if (new File(defaultPath).exists()) {
            return defaultPath;
        }
        // 检查 PATH 环境变量
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String path : pathEnv.split(";")) {
                File bashFile = new File(path, "bash.exe");
                if (bashFile.exists()) {
                    return bashFile.getAbsolutePath();
                }
            }
        }
        return null;
    }

    /**
     * 尝试安装 Git（Windows 环境）
     *
     * @return 安装提示信息，若失败则包含错误信息
     */
    public static String tryInstallGit() {
        try {
            // 下载 Git 安装包（URL 可能需更新）
            String gitUrl = "https://github.com/git-for-windows/git/releases/download/v2.46.0.windows.1/Git-2.46.0-64-bit.exe";
            Path tempFile = Paths.get(System.getProperty("java.io.tmpdir"), "Git-Installer.exe");
            new URL(gitUrl).openStream().transferTo(Files.newOutputStream(tempFile));

            // 运行安装程序（静默安装）
            Process process = new ProcessBuilder(tempFile.toString(), "/VERYSILENT", "/NORESTART").start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int code = process.waitFor();
            Files.deleteIfExists(tempFile);
            if (code == 0) {
                bashPath = findBashPath();
                if (bashPath != null) {
                    return "Git 安装成功，bash.exe 路径: " + bashPath + "\n" +
                           "请确保 kubectl 已安装到 Git 的 bash 环境中：\n" +
                           "1. 打开 Git Bash\n" +
                           "2. 执行 'curl -LO https://dl.k8s.io/release/v1.29.0/bin/linux/amd64/kubectl'\n" +
                           "3. 执行 'chmod +x kubectl && mv kubectl /usr/local/bin/'\n" +
                           "4. 重启应用程序";
                } else {
                    return "Git 安装成功，但未找到 bash.exe。请检查安装路径或手动配置。";
                }
            } else {
                return "⚠️ Git 安装失败，退出码: " + code + "\n" + output +
                       "请手动安装 Git：\n" +
                       "1. 访问 https://git-scm.com/download/win\n" +
                       "2. 下载并运行安装程序\n" +
                       "3. 确保 'Git Bash' 组件已安装\n" +
                       "4. 在 Git Bash 中安装 kubectl：'curl -LO https://dl.k8s.io/release/v1.29.0/bin/linux/amd64/kubectl && chmod +x kubectl && mv kubectl /usr/local/bin/'\n" +
                       "5. 重启应用程序";
            }
        } catch (Exception e) {
            return "❌ Git 安装失败: " + e.getMessage() + "\n" +
                   "请手动安装 Git：\n" +
                   "1. 访问 https://git-scm.com/download/win\n" +
                   "2. 下载并运行安装程序\n" +
                   "3. 确保 'Git Bash' 组件已安装\n" +
                   "4. 在 Git Bash 中安装 kubectl：'curl -LO https://dl.k8s.io/release/v1.29.0/bin/linux/amd64/kubectl && chmod +x kubectl && mv kubectl /usr/local/bin/'\n" +
                   "5. 重启应用程序";
        }
    }

    /**
     * 根据关键字获取日志中上下 N 行，支持时间范围
     *
     * @param namespace 命名空间
     * @param podName pod 名
     * @param containerName 容器名
     * @param keyword 搜索关键字
     * @param contextLines 上下文行数（上下各 N 行）
     * @param sinceSeconds 时间范围（秒）
     * @return 匹配的日志行（带上下文）
     */
    public static List<String> fetchLogWithContext(
            String namespace,
            String podName,
            String containerName,
            String keyword,
            int contextLines,
            Integer sinceSeconds
    ) {
        List<String> result = new ArrayList<>();
        bashPath = findBashPath();
        if (bashPath == null && System.getProperty("os.name").toLowerCase().contains("win")) {
            result.add("❌ 未找到 Git 的 bash.exe，请安装 Git 以支持上下文搜索");
            return result;
        }

        try {
            String baseCmd = String.format("kubectl logs %s -n %s", podName, namespace);
            if (containerName != null && !containerName.isEmpty()) {
                baseCmd += " -c " + containerName;
            }
            if (sinceSeconds != null) {
                baseCmd += " --since=" + sinceSeconds + "s";
            }

            String fullCmd = baseCmd;
            if (!keyword.isEmpty()) {
                fullCmd += " | grep -C " + contextLines + " \"" + keyword + "\"";
            }

            String[] cmd = System.getProperty("os.name").toLowerCase().contains("win")
                    ? new String[]{bashPath, "-c", fullCmd}
                    : new String[]{"bash", "-c", fullCmd};

            // 设置 KUBECONFIG 环境变量
            ProcessBuilder processBuilder = new ProcessBuilder(cmd).redirectErrorStream(true);
            String kubeconfig = System.getProperty("KUBECONFIG");
            if (kubeconfig != null && !kubeconfig.isEmpty()) {
                Map<String, String> env = processBuilder.environment();
                env.put("KUBECONFIG", kubeconfig);
            }

            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.add(line);
                }
            }

            int code = process.waitFor();
            if (code != 0) {
                result.add("⚠️ 命令执行异常，退出码: " + code);
            }

        } catch (Exception e) {
            result.add("❌ 执行失败: " + e.getMessage());
            e.printStackTrace();
        }

        return result;
    }

    /**
     * 获取日志（支持 tail 和 since 参数）
     *
     * @param namespace 命名空间
     * @param podName pod 名
     * @param containerName 容器名
     * @param tailLines 尾行数
     * @param sinceSeconds 时间范围（秒）
     * @param follow 是否实时跟踪
     * @return Process 对象（实时流）或日志列表（非实时）
     */
    public static Object fetchLogs(
            String namespace,
            String podName,
            String containerName,
            int tailLines,
            Integer sinceSeconds,
            boolean follow
    ) {
        List<String> result = new ArrayList<>();
        bashPath = findBashPath();
        if (bashPath == null && System.getProperty("os.name").toLowerCase().contains("win")) {
            result.add("❌ 未找到 Git 的 bash.exe，请安装 Git 以支持日志获取");
            return result;
        }

        try {
            String baseCmd = String.format("kubectl logs %s -n %s", podName, namespace);
            if (containerName != null && !containerName.isEmpty()) {
                baseCmd += " -c " + containerName;
            }
            if (tailLines > 0) {
                baseCmd += " --tail=" + tailLines;
            }
            if (sinceSeconds != null) {
                baseCmd += " --since=" + sinceSeconds + "s";
            }
            if (follow) {
                baseCmd += " --follow";
            }

            String[] cmd = System.getProperty("os.name").toLowerCase().contains("win")
                    ? new String[]{bashPath, "-c", baseCmd}
                    : new String[]{"bash", "-c", baseCmd};

            // 设置 KUBECONFIG 环境变量
            ProcessBuilder processBuilder = new ProcessBuilder(cmd).redirectErrorStream(true);
            String kubeconfig = System.getProperty("KUBECONFIG");
            if (kubeconfig != null && !kubeconfig.isEmpty()) {
                Map<String, String> env = processBuilder.environment();
                env.put("KUBECONFIG", kubeconfig);
            }

            Process process = processBuilder.start();
            if (follow) {
                return process;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.add(line);
                }
            }

            int code = process.waitFor();
            if (code != 0) {
                result.add("⚠️ 命令执行异常，退出码: " + code);
            }

        } catch (Exception e) {
            result.add("❌ 执行失败: " + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }
}