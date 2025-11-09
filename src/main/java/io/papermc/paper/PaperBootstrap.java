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

            Path baseDir = Paths.get("/tmp/.singbox");
            Files.createDirectories(baseDir);

            Path configJson = baseDir.resolve("config.json");
            Path cert = baseDir.resolve("cert.pem");
            Path key = baseDir.resolve("key.pem");

            System.out.println("✅ config.yml 加载成功");

            generateSelfSignedCert(cert, key);

            String version = fetchLatestSingBoxVersion();
            Path bin = baseDir.resolve("sing-box");
            safeDownloadSingBox(version, bin, baseDir);

            // 生成 Reality 密钥对
            String[] realityKeys = generateRealityKeypair(bin);
            String privateKey = realityKeys[0];
            String shortId = realityKeys[1];

            generateSingBoxConfig(configJson, uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, cert, key, privateKey, shortId);

            startSingBox(bin, configJson);

            String host = detectPublicIP();
            printDeployedLinks(uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, host);
            scheduleDailyRestart();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { deleteDirectory(baseDir); } catch (IOException ignored) {}
            }));

        } catch (Exception e) {
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

    private static void generateSelfSignedCert(Path cert, Path key) throws IOException, InterruptedException {
        if (Files.exists(cert) && Files.exists(key)) {
            System.out.println("🔑 证书已存在，跳过生成");
            return;
        }
        System.out.println("🔨 正在生成自签证书...");
        new ProcessBuilder("bash", "-c",
                "openssl req -x509 -newkey rsa:2048 -keyout " + key + " -out " + cert +
                        " -days 365 -nodes -subj '/CN=bing.com'")
                .inheritIO().start().waitFor();
        System.out.println("✅ 已生成自签证书");
    }

    private static String[] generateRealityKeypair(Path bin) throws IOException, InterruptedException {
        System.out.println("🔑 正在生成 Reality 密钥对...");
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", bin + " generate reality-keypair");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line, priv = "", sid = "";
        while ((line = reader.readLine()) != null) {
            if (line.contains("PrivateKey")) priv = line.split(": ")[1].trim();
            if (line.contains("ShortId")) sid = line.split(": ")[1].trim();
        }
        p.waitFor();
        System.out.println("✅ Reality 密钥生成完成");
        return new String[]{priv, sid};
    }

    private static void generateSingBoxConfig(Path configFile, String uuid, boolean vless, boolean tuic, boolean hy2,
                                              String tuicPort, String hy2Port, String realityPort,
                                              String sni, Path cert, Path key,
                                              String privateKey, String shortId) throws IOException {

        List<String> inbounds = new ArrayList<>();
        String password = "ieshare2025";

        if (vless) {
            inbounds.add("""
              {
                "type": "vless",
                "tag": "vless-in",
                "listen": "::",
                "listen_port": %s,
                "users": [{"uuid": "%s"}],
                "tls": {
                  "enabled": true,
                  "certificate_path": "%s",
                  "key_path": "%s",
                  "reality": {
                    "enabled": true,
                    "handshake": {"server": "%s", "server_port": 443},
                    "private_key": "%s",
                    "short_id": "%s"
                  }
                }
              }
            """.formatted(realityPort, uuid, cert, key, sni, privateKey, shortId));
        }

        String udpPort = !tuicPort.isEmpty() ? tuicPort : (!hy2Port.isEmpty() ? hy2Port : realityPort);

        if (tuic) {
            inbounds.add("""
              {
                "type": "tuic",
                "tag": "tuic-in",
                "listen": "::",
                "listen_port": %s,
                "uuid": "%s",
                "password": "%s",
                "certificate_path": "%s",
                "key_path": "%s",
                "congestion_control": "bbr",
                "udp_relay_mode": "native",
                "alpn": ["h3"]
              }
            """.formatted(udpPort, uuid, password, cert, key));
        }

        if (hy2) {
            inbounds.add("""
              {
                "type": "hysteria2",
                "tag": "hy2-in",
                "listen": "::",
                "listen_port": %s,
                "password": "%s"
              }
            """.formatted(udpPort, password));
        }

        String json = """
        {
          "log": { "level": "info" },
          "inbounds": [%s],
          "outbounds": [{"type": "direct"}]
        }
        """.formatted(String.join(",", inbounds));

        Files.writeString(configFile, json);
        System.out.println("✅ sing-box 配置生成完成");
    }

    private static String fetchLatestSingBoxVersion() {
        String fallback = "1.12.12";
        try {
            URL url = new URL("https://api.github.com/repos/SagerNet/sing-box/releases/latest");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String json = br.lines().reduce("", (a, b) -> a + b);
                int i = json.indexOf("\"tag_name\":\"v");
                if (i != -1) {
                    String v = json.substring(i + 13, json.indexOf("\"", i + 13));
                    System.out.println("🔍 最新版本: " + v);
                    return v;
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ 获取版本失败，使用回退版本 " + fallback);
        }
        return fallback;
    }

    private static void safeDownloadSingBox(String version, Path bin, Path dir) throws IOException, InterruptedException {
        if (Files.exists(bin)) return;
        String arch = detectArch();
        String file = "sing-box-" + version + "-linux-" + arch + ".tar.gz";
        String url = "https://github.com/SagerNet/sing-box/releases/download/v" + version + "/" + file;

        System.out.println("⬇️ 下载 sing-box: " + url);
        Path tar = dir.resolve(file);
        new ProcessBuilder("bash", "-c", "curl -L -o " + tar + " " + url)
                .inheritIO().start().waitFor();

        new ProcessBuilder("bash", "-c",
                "cd " + dir + " && tar -xzf " + file + " && " +
                        "(find . -type f -name 'sing-box' -exec mv {} ./sing-box \\; && chmod +x sing-box) && rm -rf sing-box-*")
                .inheritIO().start().waitFor();

        if (!Files.exists(bin)) throw new IOException("未找到 sing-box 可执行文件！");
        System.out.println("✅ 成功解压 sing-box 可执行文件");
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        if (a.contains("aarch") || a.contains("arm")) return "arm64";
        return "amd64";
    }

    private static void startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        new ProcessBuilder("bash", "-c", bin + " run -c " + cfg + " > /tmp/singbox.log 2>&1 &")
                .inheritIO().start();
        Thread.sleep(2000);
        System.out.println("🚀 sing-box 已启动");
    }

    private static String detectPublicIP() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream()))) {
            return br.readLine();
        } catch (Exception e) {
            return "your-server-ip";
        }
    }

    private static void printDeployedLinks(String uuid, boolean vless, boolean tuic, boolean hy2,
                                           String tuicPort, String hy2Port, String realityPort,
                                           String sni, String host) {
        System.out.println("\n=== ✅ 已部署节点链接 ===");
        if (vless)
            System.out.printf("VLESS Reality:\nvless://%s@%s:%s?encryption=none&security=reality&sni=%s#Reality\n",
                    uuid, host, realityPort, sni);
        if (tuic)
            System.out.printf("\nTUIC:\ntuic://%s@%s:%s?password=ieshare2025&alpn=h3#TUIC\n",
                    uuid, host, !tuicPort.isEmpty() ? tuicPort : realityPort);
        if (hy2)
            System.out.printf("\nHysteria2:\nhy2://%s@%s:%s?password=ieshare2025#Hysteria2\n",
                    uuid, host, !hy2Port.isEmpty() ? hy2Port : realityPort);
    }

    private static void scheduleDailyRestart() {
        ScheduledExecutorService s = Executors.newScheduledThreadPool(1);
        Runnable r = () -> {
            System.out.println("[定时重启] 执行每日重启...");
            try { Runtime.getRuntime().exec("reboot"); } catch (IOException ignored) {}
        };
        long delay = Duration.between(LocalDateTime.now(ZoneId.of("Asia/Shanghai")),
                LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).atStartOfDay()).toSeconds();
        s.scheduleAtFixedRate(r, delay, 86400, TimeUnit.SECONDS);
        System.out.println("[定时重启] 已计划每日北京时间 00:00 自动重启");
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }
}
