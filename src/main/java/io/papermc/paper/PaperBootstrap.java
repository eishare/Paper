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
            String tuicPort = (String) config.get("tuic_port");
            String hy2Port = (String) config.get("hy2_port");
            String realityPort = (String) config.get("reality_port");
            String sni = (String) config.getOrDefault("sni", "www.bing.com");

            if (uuid == null || uuid.isEmpty()) throw new RuntimeException("uuid 未设置");
            if (tuicPort == null || tuicPort.isEmpty()) tuicPort = "4443";
            if (hy2Port == null || hy2Port.isEmpty()) hy2Port = "4444";
            if (realityPort == null || realityPort.isEmpty()) realityPort = "4445";

            System.out.println("config.yml 加载成功");

            Files.createDirectories(Paths.get(".singbox"));

            // 生成证书
            generateSelfSignedCert();

            // 生成 sing-box 配置文件
            generateSingBoxConfig(uuid, tuicPort, hy2Port, realityPort, sni);

            // 启动 sing-box
            startSingBox();

            // 自动检测公网 IP
            String host = detectPublicIP();

            // 输出节点链接
            printNodeLinks(uuid, tuicPort, hy2Port, realityPort, sni, host);

            // 每日北京时间 00:00 自动重启
            scheduleDailyRestart();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 读取 config.yml
    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(Paths.get("config.yml"))) {
            return yaml.load(in);
        }
    }

    // 生成自签证书
    private static void generateSelfSignedCert() throws IOException, InterruptedException {
        Path certDir = Paths.get(".singbox");
        Path certPath = certDir.resolve("cert.pem");
        Path keyPath = certDir.resolve("key.pem");

        if (Files.exists(certPath) && Files.exists(keyPath)) {
            System.out.println("证书已存在，跳过生成");
            return;
        }

        System.out.println("正在生成自签证书 (OpenSSL)...");
        new ProcessBuilder("bash", "-c",
                "openssl req -x509 -newkey rsa:2048 -keyout .singbox/key.pem -out .singbox/cert.pem -days 365 -nodes -subj '/CN=bing.com'")
                .inheritIO().start().waitFor();

        System.out.println("已生成自签证书 (OpenSSL)");
    }

    // 生成 sing-box 配置
    private static void generateSingBoxConfig(String uuid, String tuic, String hy2, String reality, String sni) throws IOException {
        String json = """
        {
          "log": { "level": "info" },
          "inbounds": [
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
            },
            {
              "type": "tuic",
              "listen": "::",
              "listen_port": %s,
              "uuid": "%s",
              "password": "%s"
            },
            {
              "type": "hysteria2",
              "listen": "::",
              "listen_port": %s,
              "password": "%s"
            }
          ],
          "outbounds": [{"type": "direct"}]
        }
        """.formatted(reality, uuid, sni, sni, tuic, uuid, uuid, hy2, uuid);

        Files.writeString(Paths.get(".singbox/config.json"), json);
        System.out.println("sing-box 配置生成完成");
    }

    // 启动 sing-box
    private static void startSingBox() throws IOException {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "curl -Lo sing-box https://github.com/SagerNet/sing-box/releases/latest/download/sing-box-linux-amd64 && chmod +x sing-box && ./sing-box run -c .singbox/config.json &");
        pb.inheritIO();
        pb.start();
        System.out.println("sing-box 已启动");
    }

    // 自动检测公网 IP
    private static String detectPublicIP() {
        try {
            return new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream())).readLine();
        } catch (Exception e) {
            return "your-server-domain-or-ip";
        }
    }

    // 打印节点链接
    private static void printNodeLinks(String uuid, String tuicPort, String hy2Port, String realityPort, String sni, String host) {
        System.out.println("\n=== 节点链接 ===");
        System.out.println("VLESS Reality:");
        System.out.printf("vless://%s@%s:%s?encryption=none&security=reality&sni=%s#Reality\n",
                uuid, host, realityPort, sni);

        System.out.println("\nTUIC:");
        System.out.printf("tuic://%s@%s:%s?alpn=h3#TUIC\n", uuid, host, tuicPort);

        System.out.println("\nHysteria2:");
        System.out.printf("hy2://%s@%s:%s?insecure=1#Hysteria2\n", uuid, host, hy2Port);
    }

    // 每日北京时间零点自动重启
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
        long period = 24 * 60 * 60;

        scheduler.scheduleAtFixedRate(restartTask, initialDelay, period, TimeUnit.SECONDS);
        System.out.println("[定时重启] 已计划每日北京时间 00:00 自动重启");
    }

    // 计算距离北京时间 00:00 的秒数
    private static long computeSecondsUntilMidnightBeijing() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        LocalDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return Duration.between(now, midnight).toSeconds();
    }
}
