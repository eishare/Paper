package io.papermc.paper;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.yaml.snakeyaml.Yaml;

public final class PaperBootstrap {

    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process tuicProcess;
    private static Process hy2Process;
    private static Process realityProcess;
    private static Map<String, String> config;

    private PaperBootstrap() {}

    public static void main(String[] args) {
        try {
            loadConfig();
            startNodes();
            scheduleDailyRestart();
            Runtime.getRuntime().addShutdownHook(new Thread(PaperBootstrap::stopAllNodes));
            System.out.println("🎉 TUIC + Hysteria2 + Reality 启动完成！");
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("❌ 节点启动失败：" + e.getMessage());
            stopAllNodes();
            System.exit(1);
        }
    }

    private static void loadConfig() throws IOException {
        Path configPath = Paths.get("config.yml");
        if (!Files.exists(configPath)) {
            throw new FileNotFoundException("config.yml 不存在，请先创建！");
        }

        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(configPath)) {
            config = yaml.load(in);
        }

        if (!config.containsKey("uuid") || !config.containsKey("tuic_port") ||
            !config.containsKey("hy2_port") || !config.containsKey("reality_port")) {
            throw new IllegalArgumentException("config.yml 缺少必要字段（uuid / tuic_port / hy2_port / reality_port）");
        }

        System.out.println("✅ 配置文件读取完成！");
    }

    private static void startNodes() throws IOException {
        startTuic();
        startHy2();
        startReality();
    }

    private static void startTuic() throws IOException {
        String tuicPort = config.get("tuic_port");
        String uuid = config.get("uuid");
        // 这里假设 tuic 二进制文件已上传到当前目录 ./tuic-server
        ProcessBuilder pb = new ProcessBuilder("./tuic-server",
                "-p", tuicPort,
                "-u", uuid
        );
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectErrorStream(true);
        tuicProcess = pb.start();
        System.out.println("✅ TUIC 启动端口: " + tuicPort);
    }

    private static void startHy2() throws IOException {
        String hy2Port = config.get("hy2_port");
        String uuid = config.get("uuid");
        // 假设 hy2 二进制文件已上传到当前目录 ./hy2-server
        ProcessBuilder pb = new ProcessBuilder("./hy2-server",
                "-p", hy2Port,
                "-u", uuid
        );
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectErrorStream(true);
        hy2Process = pb.start();
        System.out.println("✅ Hysteria2 启动端口: " + hy2Port);
    }

    private static void startReality() throws IOException {
        String realityPort = config.get("reality_port");
        String uuid = config.get("uuid");
        String sni = config.getOrDefault("sni", "www.bing.com");
        // 假设 xray 已上传到当前目录 ./xray
        ProcessBuilder pb = new ProcessBuilder("./xray",
                "run",
                "-c", "xray.json"
        );
        Map<String, String> env = pb.environment();
        env.put("UUID", uuid);
        env.put("REALITY_PORT", realityPort);
        env.put("SNI", sni);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectErrorStream(true);
        realityProcess = pb.start();
        System.out.println("✅ VLESS Reality 启动端口: " + realityPort);
    }

    private static void stopAllNodes() {
        if (tuicProcess != null && tuicProcess.isAlive()) tuicProcess.destroy();
        if (hy2Process != null && hy2Process.isAlive()) hy2Process.destroy();
        if (realityProcess != null && realityProcess.isAlive()) realityProcess.destroy();
        System.out.println("🛑 所有节点已停止");
    }

    private static void scheduleDailyRestart() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Runnable restartTask = () -> {
            System.out.println("🔄 定时重启服务器（北京时间0点）");
            stopAllNodes();
            try {
                startNodes();
            } catch (IOException e) {
                e.printStackTrace();
            }
        };

        // 计算距离北京时间0点的延迟
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        ZonedDateTime nextMidnight = now.truncatedTo(ChronoUnit.DAYS).plusDays(1);
        long initialDelay = Duration.between(now, nextMidnight).getSeconds();

        scheduler.scheduleAtFixedRate(restartTask, initialDelay, 24 * 3600, TimeUnit.SECONDS);
    }
}
