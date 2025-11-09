package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.Base64;

public class PaperBootstrap {
    private static final String PASSWORD = "eishare2025";  // 固定密码

    public static void main(String[] args) {
        try {
            System.out.println("config.yml 加载中...");
            Map<String, Object> config = loadConfig();

            String uuid = trim((String) config.get("uuid"));
            String tuicPort = trim((String) config.get("tuic_port"));
            String hy2Port = trim((String) config.get("hy2_port"));
            String realityPort = trim((String) config.get("reality_port"));
            String sni = trim((String) config.getOrDefault("sni", "www.bing.com"));

            if (uuid.isEmpty()) throw new RuntimeException("uuid 未设置！");

            boolean deployReality = !realityPort.isEmpty();
            boolean deployTUIC = !tuicPort.isEmpty();
            boolean deployHY2 = !hy2Port.isEmpty();

            if (!deployReality && !deployTUIC && !deployHY2) {
                throw new RuntimeException("未设置任何协议端口！");
            }

            Path baseDir = Paths.get("/tmp/.singbox");
            Files.createDirectories(baseDir);
            Path configJson = baseDir.resolve("config.json");
            Path bin = baseDir.resolve("sing-box");

            System.out.println("config.yml 加载成功");

            String version = fetchLatestSingBoxVersion();
            safeDownloadSingBox(version, bin, baseDir);

            // 确保可执行
            new ProcessBuilder("bash", "-c", "chmod +x " + bin.toString())
                    .inheritIO().start().waitFor();

            generateSingBoxConfig(
                    configJson, uuid, deployReality, deployTUIC, deployHY2,
                    tuicPort.isEmpty() ? realityPort : tuicPort,
                    hy2Port.isEmpty() ? realityPort : hy2Port,
                    realityPort, sni
            );

            startSingBox(bin, configJson);

            String host = detectPublicIP();

            printDeployedLinks(
                    uuid, deployReality, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, host
            );

            scheduleDailyRestart();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { deleteDirectory(baseDir); } catch (IOException ignored) {}
            }));

        } catch (Exception e) {
            System.err.println("部署失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(Paths.get("config.yml"))) {
            return yaml.load(in);
        }
    }

    private static void generateSingBoxConfig(
            Path configFile, String uuid,
            boolean reality, boolean tuic, boolean hy2,
            String tuicPort, String hy2Port, String realityPort, String sni
    ) throws IOException {

        List<String> inbounds = new ArrayList<>();

        // === VLESS + Reality + 密码认证（无需密钥）===
        if (reality) {
            inbounds.add(String.format(
              "{\n" +
              "  \"type\": \"vless\",\n" +
              "  \"tag\": \"vless-in\",\n" +
              "  \"listen\": \"::\",\n" +
              "  \"listen_port\": %s,\n" +
              "  \"users\": [{\"uuid\": \"%s\", \"password\": \"%s\"}],\n" +
              "  \"tls\": {\n" +
              "    \"enabled\": true,\n" +
              "    \"server_name\": \"%s\",\n" +
              "    \"reality\": {\n" +
              "      \"enabled\": true,\n" +
              "      \"handshake\": {\"server\": \"%s\", \"server_port\": 443}\n" +
              "    }\n" +
              "  }\n" +
              "}",
              realityPort, uuid, PASSWORD, sni, sni
            ));
        }

        // === TUIC + 密码认证（无需证书）===
        if (tuic) {
            inbounds.add(String.format(
              "{\n" +
              "  \"type\": \"tuic\",\n" +
              "  \"tag\": \"tuic-in\",\n" +
              "  \"listen\": \"::\",\n" +
              "  \"listen_port\": %s,\n" +
              "  \"users\": [{\"uuid\": \"%s\", \"password\": \"%s\"}],\n" +
              "  \"congestion_control\": \"bbr\",\n" +
              "  \"alpn\": [\"h3\"],\n" +
              "  \"udp_relay_mode\": \"native\",\n" +
              "  \"disable_sni\": true\n" +
              "}",
              tuicPort, uuid, PASSWORD
            ));
        }

        // === Hysteria2 ===
        if (hy2) {
            inbounds.add(String.format(
              "{\n" +
              "  \"type\": \"hysteria2\",\n" +
              "  \"tag\": \"hy2-in\",\n" +
              "  \"listen\": \"::\",\n" +
              "  \"listen_port\": %s,\n" +
              "  \"password\": \"%s\",\n" +
              "  \"up_mbps\": 100,\n" +
              "  \"down_mbps\": 100\n" +
              "}",
              hy2Port, PASSWORD
            ));
        }

        String json = String.format(
            "{\n" +
            "  \"log\": {\"level\": \"info\"},\n" +
            "  \"inbounds\": [%s],\n" +
            "  \"outbounds\": [{\"type\": \"direct\"}]\n" +
            "}",
            String.join(", ", inbounds)
        );

        Files.write(configFile, json.getBytes("UTF-8"));
        System.out.println("sing-box 配置生成完成");
    }

    private static String fetchLatestSingBoxVersion() {
        String fallback = "1.12.12";
        try {
            URL url = new URL("https://api.github.com/repos/SagerNet/sing-box/releases/latest");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            StringBuilder json = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) json.append(line);
            }
            int i = json.indexOf("\"tag_name\":\"v");
            if (i != -1) {
                String v = json.substring(i + 13, json.indexOf("\"", i + 13));
                System.out.println("最新版本: " + v);
                return v;
            }
        } catch (Exception e) {
            System.out.println("获取版本失败，使用回退版本 " + fallback);
        }
        return fallback;
    }

    private static void safeDownloadSingBox(String version, Path bin, Path dir) throws IOException, InterruptedException {
        if (Files.exists(bin)) {
            System.out.println("sing-box 已存在，跳过下载");
            return;
        }

        String arch = detectArch();
        String file = "sing-box-" + version + "-linux-" + arch + ".tar.gz";
        String url = "https://github.com/SagerNet/sing-box/releases/download/v" + version + "/" + file;
        System.out.println("下载 sing-box: " + url);
        Path tar = dir.resolve(file);

        new ProcessBuilder("bash", "-c", "curl -L -o " + tar + " " + url)
                .inheritIO().start().waitFor();

        new ProcessBuilder("bash", "-c",
                "cd " + dir + " && tar -xzf " + file + " && " +
                        "find . -type f -name 'sing-box' -exec mv {} sing-box \\; && rm -rf sing-box-*")
                .inheritIO().start().waitFor();

        if (!Files.exists(bin)) throw new IOException("sing-box 下载失败！");
        System.out.println("sing-box 下载并解压成功");
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        return (a.contains("aarch") || a.contains("arm")) ? "arm64" : "amd64";
    }

    private static void startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        new ProcessBuilder("bash", "-c", bin + " run -c " + cfg + " > /tmp/singbox.log 2>&1 &")
                .inheritIO().start();
        Thread.sleep(3000);
        System.out.println("sing-box 已启动");
    }

    private static String detectPublicIP() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream()))) {
            return br.readLine();
        } catch (Exception e) {
            return "your-server-ip";
        }
    }

    private static void printDeployedLinks(
            String uuid, boolean reality, boolean tuic, boolean hy2,
            String tuicPort, String hy2Port, String realityPort,
            String sni, String host
    ) {
        System.out.println("\n=== 已部署节点链接 ===");

        if (reality) {
            System.out.printf("VLESS Reality:\nvless://%s@%s:%s?encryption=none&security=reality&password=%s&sni=%s&fp=chrome&type=tcp#Reality\n",
                    uuid, host, realityPort, PASSWORD, sni);
        }

        if (tuic) {
            String auth = uuid + ":" + PASSWORD;
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(auth.getBytes());
            String port = tuicPort.isEmpty() ? realityPort : tuicPort;
            System.out.printf("\nTUIC:\ntuic://%s@%s:%s?alpn=h3&congestion_control=bbr#TUIC\n",
                    encoded, host, port);
        }

        if (hy2) {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(PASSWORD.getBytes());
            String port = hy2Port.isEmpty() ? realityPort : hy2Port;
            System.out.printf("\nHysteria2:\nhy2://%s@%s:%s?#Hysteria2\n",
                    encoded, host, port);
        }
    }

    private static void scheduleDailyRestart() {
        ScheduledExecutorService s = Executors.newScheduledThreadPool(1);
        Runnable r = () -> {
            System.out.println("[定时重启] 执行每日重启...");
            try { Runtime.getRuntime().exec("reboot"); } catch (IOException ignored) {}
        };
        long delay = Duration.between(
                LocalDateTime.now(ZoneId.of("Asia/Shanghai")),
                LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).atStartOfDay()
        ).toSeconds();
        s.scheduleAtFixedRate(r, delay, 86400, TimeUnit.SECONDS);
        System.out.println("[定时重启] 已计划每日 00:00 自动重启");
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }
}
