package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class PaperBootstrap {

    public static void main(String[] args) {
        try {
            System.out.println("config.yml 加载中...");
            Map<String, Object> config = loadConfig();

            String uuid = (String) config.get("uuid");
            String tuicPort = trim((String) config.get("tuic_port"));
            String hy2Port = trim((String) config.get("hy2_port"));
            String realityPort = trim((String) config.get("reality_port"));
            String sni = (String) config.getOrDefault("sni", "www.bing.com");

            if (uuid == null || uuid.isEmpty()) {
                throw new RuntimeException("❌ 配置错误: uuid 未设置！");
            }

            // 判断部署的协议
            boolean deployVLESS = (realityPort != null && !realityPort.isEmpty());
            boolean deployTUIC = (tuicPort != null && !tuicPort.isEmpty());
            boolean deployHY2 = (hy2Port != null && !hy2Port.isEmpty());

            if (!deployVLESS && !deployTUIC && !deployHY2) {
                throw new RuntimeException("❌ 配置错误: 未设置任何协议端口！");
            }

            System.out.println("✅ config.yml 加载成功");
            Files.createDirectories(Paths.get(".singbox"));

            // 生成证书
            generateSelfSignedCert();

            // 根据配置生成 sing-box.json
            generateSingBoxConfig(uuid, deployVLESS, deployTUIC, deployHY2, tuicPort, hy2Port, realityPort, sni);

            // 下载 & 启动 sing-box
            startSingBox();

            // 自动检测公网 IP
            String host = detectPublicIP();

            // 输出节点
            printDeployedLinks(uuid, deployVLESS, deployTUIC, deployHY2, tuicPort, hy2Port, realityPort, sni, host);

            // 定时重启
            scheduleDailyRestart();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(Paths.get("config.yml"))) {
            return yaml.load(in);
        }
    }

    private static String trim(String s) {
        return (s == null) ? "" : s.trim();
    }

    private static void generateSelfSignedCert() throws IOException, InterruptedException {
        Path certDir = Paths.get(".singbox");
        Path certPath = certDir.resolve("cert.pem");
        Path keyPath = certDir.resolve("key.pem");

        if (Files.exists(certPath) && Files.exists(keyPath)) {
            System.out.println("🔑 证书已存在，跳过生成");
            return;
        }

        System.out.println("🔨 正在生成自签证书 (OpenSSL)...");
        new ProcessBuilder("bash", "-c",
                "openssl req -x509 -newkey rsa:2048 -keyout .singbox/key.pem -out .singbox/cert.pem -days 365 -nodes -subj '/CN=bing.com'")
                .inheritIO().start().waitFor();

        System.out.println("✅ 已生成自签证书 (OpenSSL)");
    }

    private static void generateSingBoxConfig(String uuid, boolean deployVLESS, boolean deployTUIC, boolean deployHY2,
                                              String tuicPort, String hy2Port, String realityPort, String sni) throws IOException {

        List<String> inbounds = new ArrayList<>();

        if (deployVLESS) {
            inbounds.add("""
            {
              "type": "vless",
              "listen": "::",
              "listen_port": %s,
              "users": [{"uuid": "%s"}],
              "tls": {
                "enabled": true,
                "server_name": "%s",
                "reality": {
                  "enabled": true,
                  "handshake": {"server": "%s", "server_port": 443},
                  "private_key": "",
                  "short_id": ""
                }
              }
            }
            """.formatted(realityPort, uuid, sni, sni));
        }

        if (deployTUIC) {
            inbounds.add("""
            {
              "type": "tuic",
              "listen": "::",
              "listen_port": %s,
              "uuid": "%s",
              "password": "%s"
            }
            """.formatted(tuicPort, uuid, uuid));
        }

        if (deployHY2) {
            inbounds.add("""
            {
              "type": "hysteria2",
              "listen": "::",
              "listen_port": %s,
              "password": "%s"
            }
            """.formatted(hy2Port, uuid));
        }

        String json = """
        {
          "log": { "level": "info" },
          "inbounds": [%s],
          "outbounds": [{"type": "direct"}]
        }
        """.formatted(String.join(",", inbounds));

        Files.writeString(Paths.get(".singbox/config.json"), json);
        System.out.println("✅ sing-box 配置生成完成");
    }

    private static void startSingBox() throws IOException, InterruptedException {
        System.out.println("⬇️ 正在下载 sing-box...");
        ProcessBuilder download = new ProcessBuilder("bash", "-c",
                "curl -L -o sing-box https://github.com/SagerNet/sing-box/releases/latest/download/sing-box-linux-amd64 && chmod +x sing-box");
        download.inheritIO().start().waitFor();

        if (!Files.exists(Paths.get("sing-box"))) {
            throw new IOException("❌ sing-box 下载失败！");
        }

        new ProcessBuilder("bash", "-c", "./sing-box run -c .singbox/config.json &").inheritIO().start();
        System.out.println("🚀 sing-box 已启动");
    }

    private static String detectPublicIP() {
        try {
            return new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream())).readLine();
        } catch (Exception e) {
            return "your-server-ip";
        }
    }

    private static void printDeployedLinks(String uuid, boolean vless, boolean tuic, boolean hy2,
                                           String tuicPort, String hy2Port, String realityPort,
                                           String sni, String host) {

        System.out.println("\n=== ✅ 已部署节点链接 ===");

        if (vless) {
            System.out.println("VLESS Reality:");
            System.out.printf("vless://%s@%s:%s?encryption=none&security=reality&sni=%s#Reality\n",
                    uuid, host, realityPort, sni);
        }

        if (tuic) {
            System.out.println("\nTUIC:");
            System.out.printf("tuic://%s@%s:%s?alpn=h3#TUIC\n", uuid, host, tuicPort);
        }

        if (hy2) {
            System.out.println("\nHysteria2:");
            System.out.printf("hy2://%s@%s:%s?insecure=1#Hysteria2\n", uuid, host, hy2Port);
        }
    }

    private static void scheduleDailyRestart() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        Runnable restartTask = () -> {
            System.out.println("[定时重启] 正在执行每日重启任务...");
            try {
                Runtime.getRuntime().exec("reboot");
            } catch (IOException e) {
                e.printStackTrace();
            }
        };
        long initialDelay = computeSecondsUntilMidnightBeijing();
        scheduler.scheduleAtFixedRate(restartTask, initialDelay, 86400, TimeUnit.SECONDS);
        System.out.println("[定时重启] 已计划每日北京时间 00:00 自动重启");
    }

    private static long computeSecondsUntilMidnightBeijing() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        LocalDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return Duration.between(now, midnight).toSeconds();
    }
}
