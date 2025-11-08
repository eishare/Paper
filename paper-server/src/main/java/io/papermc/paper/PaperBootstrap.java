package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public final class PaperBootstrap {

    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET = "\033[0m";

    private static Process singBoxProcess;
    private static ScheduledExecutorService restartScheduler;
    private static Map<String, String> config;

    private PaperBootstrap() {}

    public static void main(String[] args) {
        try {
            loadConfig();
            downloadSingBox();
            generateSingBoxConfig();
            startSingBox();
            scheduleDailyRestart();

            System.out.println(ANSI_GREEN + "TUIC + Hysteria2 + VLESS-Reality 启动完成！" + ANSI_RESET);

            // 主线程保持运行
            Thread.currentThread().join();

        } catch (Exception e) {
            System.err.println(ANSI_RED + "启动失败: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
            stopSingBox();
            System.exit(1);
        }
    }

    private static void loadConfig() throws IOException {
        Path configPath = Paths.get("config.yml");
        if (!Files.exists(configPath)) {
            throw new FileNotFoundException("config.yml 不存在，请上传到根目录！");
        }
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(configPath)) {
            config = yaml.load(in);
        }
        System.out.println(ANSI_GREEN + "config.yml 加载成功" + ANSI_RESET);
    }

    private static void downloadSingBox() throws IOException, InterruptedException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;

        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = "https://github.com/SagerNet/sing-box/releases/download/v1.8.13/sing-box-1.8.13-linux-amd64.tar.gz";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = "https://github.com/SagerNet/sing-box/releases/download/v1.8.13/sing-box-1.8.13-linux-arm64.tar.gz";
        } else {
            throw new RuntimeException("不支持的架构: " + osArch);
        }

        Path binDir = Paths.get(".singbox");
        Path binPath = binDir.resolve("sing-box");

        if (!Files.exists(binPath)) {
            System.out.println(ANSI_YELLOW + "正在下载 sing-box..." + ANSI_RESET);
            Files.createDirectories(binDir);

            Path tarPath = binDir.resolve("sing-box.tar.gz");
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, tarPath, StandardCopyOption.REPLACE_EXISTING);
            }

            ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", tarPath.toString(), "-C", binDir.toString());
            pb.inheritIO().start().waitFor();

            Path extracted = Files.list(binDir)
                .filter(p -> p.toString().contains("sing-box-"))
                .findFirst().get();

            Files.move(extracted.resolve("sing-box"), binPath, StandardCopyOption.REPLACE_EXISTING);

            Files.walk(binDir)
                .filter(p -> !p.equals(binPath))
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });

            binPath.toFile().setExecutable(true);
            System.out.println(ANSI_GREEN + "sing-box 下载并安装完成" + ANSI_RESET);
        }
    }

    private static void generateSingBoxConfig() throws IOException, InterruptedException {
        String uuid = config.get("uuid");
        String tuicPort = config.get("tuic_port");
        String hy2Port = config.get("hy2_port");
        String realityPort = config.get("reality_port");
        String sni = config.getOrDefault("sni", "www.bing.com");

        String privateKey = "testkey123";
        String shortId = "01234567";

        StringBuilder inbounds = new StringBuilder();
        if (!tuicPort.isEmpty()) {
            inbounds.append(String.format("""
                {
                  "type": "tuic",
                  "tag": "tuic-in",
                  "listen": "::",
                  "listen_port": %s,
                  "users": [{"uuid": "%s","password":"admin"}],
                  "congestion_control": "bbr",
                  "tls": {"enabled": true,"alpn":["h3"],"certificate_path":".singbox/cert.pem","key_path":".singbox/private.key"}
                },""", tuicPort, uuid));
        }
        if (!hy2Port.isEmpty()) {
            inbounds.append(String.format("""
                {
                  "type": "hysteria2",
                  "tag": "hy2-in",
                  "listen": "::",
                  "listen_port": %s,
                  "users": [{"password": "%s"}],
                  "tls": {"enabled": true,"alpn":["h3"],"certificate_path":".singbox/cert.pem","key_path":".singbox/private.key"}
                },""", hy2Port, uuid));
        }
        if (!realityPort.isEmpty()) {
            inbounds.append(String.format("""
                {
                  "type": "vless",
                  "tag": "reality-in",
                  "listen": "::",
                  "listen_port": %s,
                  "users": [{"uuid": "%s","flow":"xtls-rprx-vision"}],
                  "tls": {
                    "enabled": true,
                    "server_name": "%s",
                    "reality": {"enabled": true,"handshake":{"server":"%s","server_port":443},"private_key":"%s","short_id":["%s"]}
                  }
                }""", realityPort, uuid, sni, sni, privateKey, shortId));
        }

        String json = String.format("""
            {
              "log":{"level":"warn"},
              "inbounds":[%s],
              "outbounds":[{"type":"direct"}]
            }""", inbounds);

        Files.createDirectories(Paths.get(".singbox"));
        Files.writeString(Paths.get(".singbox", "config.json"), json);
        System.out.println(ANSI_GREEN + "sing-box 配置生成完成" + ANSI_RESET);
    }

    private static void startSingBox() throws IOException {
        ProcessBuilder pb = new ProcessBuilder("./.singbox/sing-box", "run", "-c", ".singbox/config.json");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        singBoxProcess = pb.start();
        System.out.println(ANSI_GREEN + "sing-box 已启动" + ANSI_RESET);
    }

    private static void stopSingBox() {
        if (singBoxProcess != null && singBoxProcess.isAlive()) {
            singBoxProcess.destroy();
            System.out.println(ANSI_RED + "sing-box 已停止" + ANSI_RESET);
        }
        if (restartScheduler != null) restartScheduler.shutdownNow();
    }

    private static void scheduleDailyRestart() {
        restartScheduler = Executors.newSingleThreadScheduledExecutor();
        Runnable task = () -> {
            System.out.println(ANSI_YELLOW + "\n[定时重启] 北京时间 00:00 自动重启..." + ANSI_RESET);
            stopSingBox();
            try {
                Thread.sleep(2000);
                generateSingBoxConfig();
                startSingBox();
            } catch (Exception e) { e.printStackTrace(); }
        };

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        ZonedDateTime next = now.plusDays(1).toLocalDate().atStartOfDay(ZoneId.of("Asia/Shanghai"));
        long delay = Duration.between(now, next).getSeconds();

        restartScheduler.scheduleAtFixedRate(task, delay, 24 * 3600, TimeUnit.SECONDS);
        System.out.println(ANSI_YELLOW + "[定时重启] 已计划每日 00:00 自动重启" + ANSI_RESET);
    }
}
