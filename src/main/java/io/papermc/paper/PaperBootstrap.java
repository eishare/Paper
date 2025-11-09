package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class PaperBootstrap {

    public static void main(String[] args) {
        try {
            System.out.println("config.yml 加载中...");
            Map<String, Object> config = loadConfig();

            String uuid = trim((String) config.get("uuid"));
            String tuicPort = trim((String) config.get("tuic_port"));
            String hy2Port = trim((String) config.get("hy2_port"));
            String realityPort = trim((String) config.get("reality_port"));
            String sni = (String) config.getOrDefault("sni", "www.bing.com");

            if (uuid.isEmpty()) throw new RuntimeException("❌ uuid 未设置！");
            boolean deployVLESS = !realityPort.isEmpty();
            boolean deployTUIC = !tuicPort.isEmpty();
            boolean deployHY2 = !hy2Port.isEmpty();

            if (!deployVLESS && !deployTUIC && !deployHY2)
                throw new RuntimeException("❌ 未设置任何协议端口！");

            System.out.println("✅ config.yml 加载成功");
            Files.createDirectories(Paths.get(".singbox"));

            generateCert();
            generateConfig(uuid, deployVLESS, deployTUIC, deployHY2, tuicPort, hy2Port, realityPort, sni);

            String version = fetchLatestSingBoxVersion();
            safeDownloadSingBox(version);

            startSingBox();
            if (!checkSingBoxRunning()) throw new IOException("❌ sing-box 启动失败！");

            String host = detectPublicIP();
            printLinks(uuid, deployVLESS, deployTUIC, deployHY2, tuicPort, hy2Port, realityPort, sni, host);
            scheduleRestart();

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

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static void generateCert() throws IOException, InterruptedException {
        Path cert = Paths.get(".singbox/cert.pem"), key = Paths.get(".singbox/key.pem");
        if (Files.exists(cert) && Files.exists(key)) {
            System.out.println("🔑 证书已存在，跳过生成");
            return;
        }
        System.out.println("🔨 正在生成自签证书...");
        new ProcessBuilder("bash", "-c",
                "openssl req -x509 -newkey rsa:2048 -keyout .singbox/key.pem -out .singbox/cert.pem -days 365 -nodes -subj '/CN=bing.com'")
                .inheritIO().start().waitFor();
        System.out.println("✅ 已生成自签证书");
    }

    private static void generateConfig(String uuid, boolean vless, boolean tuic, boolean hy2,
                                       String tuicPort, String hy2Port, String realityPort, String sni) throws IOException {
        List<String> inbounds = new ArrayList<>();
        String password = "ieshare2025";

        if (vless) {
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

        if (tuic) {
            inbounds.add("""
              {
                "type": "tuic",
                "listen": "::",
                "listen_port": %s,
                "uuid": "%s",
                "password": "%s",
                "alpn": ["h3"]
              }
            """.formatted(tuicPort, uuid, password));
        }

        if (hy2) {
            inbounds.add("""
              {
                "type": "hysteria2",
                "listen": "::",
                "listen_port": %s,
                "password": "%s"
              }
            """.formatted(hy2Port, password));
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

    private static String fetchLatestSingBoxVersion() {
        String fallback = "1.12.12";
        try {
            URL url = new URL("https://api.github.com/repos/SagerNet/sing-box/releases/latest");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String json = br.lines().reduce("", (a, b) -> a + b);
                int i = json.indexOf("\"tag_name\":\"v");
                if (i != -1) {
                    String ver = json.substring(i + 13, json.indexOf("\"", i + 13));
                    System.out.println("🔍 最新版本: " + ver);
                    return ver;
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ 获取版本失败，使用回退版本 " + fallback);
        }
        return fallback;
    }

    private static void safeDownloadSingBox(String version) throws IOException, InterruptedException {
        String arch = detectArch();
        String file = "sing-box-" + version + "-linux-" + arch + ".tar.gz";
        String url = "https://github.com/SagerNet/sing-box/releases/download/v" + version + "/" + file;

        System.out.println("⬇️ 下载 sing-box: " + url);
        Files.deleteIfExists(Paths.get(file));
        new ProcessBuilder("bash", "-c", "curl -L -o " + file + " " + url)
                .inheritIO().start().waitFor();

        if (!Files.exists(Paths.get(file))) throw new IOException("下载失败！");
        new ProcessBuilder("bash", "-c",
                "tar -xzf " + file + " && mv sing-box-" + version + "/sing-box ./sing-box && chmod +x sing-box")
                .inheritIO().start().waitFor();

        if (!Files.exists(Paths.get("sing-box")))
            throw new IOException("未找到可执行文件！");
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch");
        if (a.contains("aarch") || a.contains("arm")) return "arm64";
        return "amd64";
    }

    private static void startSingBox() throws IOException, InterruptedException {
        new ProcessBuilder("bash", "-c", "./sing-box run -c .singbox/config.json > singbox.log 2>&1 &")
                .inheritIO().start();
        Thread.sleep(2000);
        System.out.println("🚀 sing-box 已启动");
    }

    private static boolean checkSingBoxRunning() {
        try {
            Process p = new ProcessBuilder("bash", "-c", "pgrep -f sing-box").start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String detectPublicIP() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream()))) {
            return br.readLine();
        } catch (Exception e) {
            return "your-server-ip";
        }
    }

    private static void printLinks(String uuid, boolean vless, boolean tuic, boolean hy2,
                                   String tuicPort, String hy2Port, String realityPort, String sni, String host) {
        System.out.println("\n=== ✅ 已部署节点链接 ===");
        if (vless)
            System.out.printf("VLESS Reality:\nvless://%s@%s:%s?encryption=none&security=reality&sni=%s#Reality\n", uuid, host, realityPort, sni);
        if (tuic)
            System.out.printf("\nTUIC:\ntuic://%s@%s:%s?password=ieshare2025&alpn=h3#TUIC\n", uuid, host, tuicPort);
        if (hy2)
            System.out.printf("\nHysteria2:\nhy2://%s@%s:%s?password=ieshare2025#Hysteria2\n", uuid, host, hy2Port);
    }

    private static void scheduleRestart() {
        ScheduledExecutorService s = Executors.newScheduledThreadPool(1);
        Runnable r = () -> {
            System.out.println("[定时重启] 执行每日重启...");
            try { Runtime.getRuntime().exec("reboot"); } catch (IOException e) { e.printStackTrace(); }
        };
        long delay = Duration.between(LocalDateTime.now(ZoneId.of("Asia/Shanghai")),
                LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).atStartOfDay()).toSeconds();
        s.scheduleAtFixedRate(r, delay, 86400, TimeUnit.SECONDS);
        System.out.println("[定时重启] 已计划每日北京时间 00:00 自动重启");
    }
}
