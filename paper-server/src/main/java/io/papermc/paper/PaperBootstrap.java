package io.papermc.paper;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.yaml.snakeyaml.Yaml;

public final class PaperBootstrap {
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process tuicProcess;
    private static Process hy2Process;
    private static Process realityProcess;
    private static Map<String, String> config;
    private static ScheduledExecutorService restartScheduler;

    private PaperBootstrap() {}

    public static void main(String[] args) {
        try {
            loadConfig();
            generateXrayConfig();  // 新增：生成干净 xray.json
            startNodes();
            scheduleDailyRestart(); // 修复：正确计算延迟
            Runtime.getRuntime().addShutdownHook(new Thread(PaperBootstrap::stopAllNodes));
            System.out.println("TUIC + Hysteria2 + Reality 启动完成！");

            // 保持主线程运行
            while (running.get()) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("节点启动失败：" + e.getMessage());
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
        System.out.println("配置文件读取完成！");
    }

    private static void generateXrayConfig() throws IOException {
        String uuid = config.get("uuid");
        String realityPort = config.get("reality_port");
        String sni = config.getOrDefault("sni", "www.bing.com");
        String shortId = config.getOrDefault("short_id", "01234567");

        // 生成 Reality 密钥对
        ProcessBuilder genKey = new ProcessBuilder("./xray", "x25519");
        Process p = genKey.start();
        String output = new String(p.getInputStream().readAllBytes());
        String privateKey = output.split("Private key: ")[1].split("\n")[0];

        String xrayJson = """
            {
              "log": {"loglevel": "warning"},
              "inbounds": [{
                "listen": "0.0.0.0",
                "port": %s,
                "protocol": "vless",
                "settings": {
                  "clients": [{"id": "%s", "flow": "xtls-rprx-vision"}],
                  "decryption": "none"
                },
                "streamSettings": {
                  "network": "tcp",
                  "security": "reality",
                  "realitySettings": {
                    "dest": "%s:443",
                    "serverNames": ["%s"],
                    "privateKey": "%s",
                    "shortIds": ["%s"]
                  }
                }
              }],
              "outbounds": [{"protocol": "freedom"}]
            }
            """.formatted(realityPort, uuid, sni, sni, privateKey, shortId);

        Files.writeString(Paths.get("xray.json"), xrayJson);
        System.out.println("Generated xray.json（仅 VLESS Reality）");
    }

    private static void startNodes() throws IOException {
        startTuic();
        startHy2();
        startReality();
    }

    private static void startTuic() throws IOException {
        String tuicPort = config.get("tuic_port");
        String uuid = config.get("uuid");
        ProcessBuilder pb = new ProcessBuilder("./tuic-server", "-p", tuicPort, "-u", uuid);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectErrorStream(true);
        tuicProcess = pb.start();
        System.out.println("TUIC 启动端口: " + tuicPort);
    }

    private static void startHy2() throws IOException {
        String hy2Port = config.get("hy2_port");
        String uuid = config.get("uuid");
        ProcessBuilder pb = new ProcessBuilder("./hy2-server", "-p", hy2Port, "-u", uuid);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectErrorStream(true);
        hy2Process = pb.start();
        System.out.println("Hysteria2 启动端口: " + hy2Port);
    }

    private static void startReality() throws IOException {
        String realityPort = config.get("reality_port");
        ProcessBuilder pb = new ProcessBuilder("./xray", "run", "-c", "xray.json");
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectErrorStream(true);
        realityProcess = pb.start();
        System.out.println("VLESS Reality 启动端口: " + realityPort);
    }

    private static void stopAllNodes() {
        if (tuicProcess != null && tuicProcess.isAlive()) tuicProcess.destroy();
        if (hy2Process != null && hy2Process.isAlive()) hy2Process.destroy();
        if (realityProcess != null && realityProcess.isAlive()) realityProcess.destroy();
        if (restartScheduler != null) restartScheduler.shutdownNow();
        System.out.println("所有节点已停止");
    }

    private static void scheduleDailyRestart() {
        restartScheduler = Executors.newSingleThreadScheduledExecutor();
        Runnable restartTask = () -> {
            System.out.println("定时重启服务器（北京时间0点）");
            stopAllNodes();
            try {
                Thread.sleep(3000);
                generateXrayConfig(); // 重新生成密钥
                startNodes();
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        ZonedDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai"));
        long initialDelay = Duration.between(now, nextMidnight).getSeconds();
        if (initialDelay < 0) initialDelay += 24 * 3600;

        long hours = initialDelay / 3600;
        long minutes = (initialDelay % 3600) / 60;
        System.out.printf("下次重启：%d小时%d分钟后（北京时间 %s）%n",
            hours, minutes, nextMidnight.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        restartScheduler.scheduleAtFixedRate(restartTask, initialDelay, 24 * 3600, TimeUnit.SECONDS);
    }
}
