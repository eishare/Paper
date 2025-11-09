package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class PaperBootstrap {
    public static void main(String[] args) {
        try {
            System.out.println("🧩 正在加载 config.yml ...");
            Map<String, Object> config = loadConfig();

            String uuid = trim((String) config.get("uuid"));
            String tuicPort = trim((String) config.get("tuic_port"));
            String hy2Port = trim((String) config.get("hy2_port"));
            String realityPort = trim((String) config.get("reality_port"));
            String sni = trim((String) config.getOrDefault("sni", "www.bing.com"));

            if (uuid.isEmpty()) throw new RuntimeException("❌ uuid 未设置！");
            if (tuicPort.isEmpty() && hy2Port.isEmpty() && realityPort.isEmpty())
                throw new RuntimeException("❌ 未配置任何协议端口！");

            Path baseDir = Paths.get("/tmp/.singbox");
            Files.createDirectories(baseDir);
            Path configJson = baseDir.resolve("config.json");
            Path cert = baseDir.resolve("cert.pem");
            Path key = baseDir.resolve("private.key");
            Path bin = baseDir.resolve("sing-box");
            Path realityKeyFile = baseDir.resolve("reality.key");

            generateSelfSignedCert(cert, key);
            String version = fetchLatestSingBoxVersion();
            safeDownloadSingBox(version, bin, baseDir);

            String privateKey = "";
            String publicKey = "";
            if (Files.exists(realityKeyFile)) {
                List<String> lines = Files.readAllLines(realityKeyFile);
                for (String line : lines) {
                    if (line.startsWith("PrivateKey:")) privateKey = line.split(":", 2)[1].trim();
                    if (line.startsWith("PublicKey:")) publicKey = line.split(":", 2)[1].trim();
                }
                System.out.println("🔑 已加载 Reality 固定密钥对");
            } else {
                Map<String, String> keys = generateRealityKeypair(bin);
                privateKey = keys.get("private_key");
                publicKey = keys.get("public_key");
                Files.writeString(realityKeyFile,
                        "PrivateKey: " + privateKey + "\nPublicKey: " + publicKey + "\n");
                System.out.println("✅ 已生成 Reality 密钥并固定保存");
            }

            generateSingBoxConfig(configJson, uuid, tuicPort, hy2Port, realityPort, sni, cert, key, privateKey);
            startSingBox(bin, configJson);

            String host = detectPublicIP();
            printDeployedLinks(uuid, host, tuicPort, hy2Port, realityPort, sni, publicKey);

            scheduleDailyRestart();

        } catch (Exception e) {
            System.err.println("❌ 启动失败：");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(Paths.get("config.yml"))) {
            Object o = yaml.load(in);
            return (Map<String, Object>) o;
        }
    }

    private static void generateSelfSignedCert(Path cert, Path key) throws IOException, InterruptedException {
        if (Files.exists(cert) && Files.exists(key)) return;
        new ProcessBuilder("bash", "-c",
                "openssl ecparam -genkey -name prime256v1 -out " + key + " && " +
                        "openssl req -new -x509 -days 3650 -key " + key +
                        " -out " + cert + " -subj '/CN=bing.com'")
                .inheritIO().start().waitFor();
    }

    private static Map<String, String> generateRealityKeypair(Path bin) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", bin + " generate reality-keypair");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        Matcher priv = Pattern.compile("PrivateKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        Matcher pub = Pattern.compile("PublicKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        if (!priv.find() || !pub.find()) throw new IOException("生成 Reality 密钥失败");
        Map<String, String> map = new HashMap<>();
        map.put("private_key", priv.group(1));
        map.put("public_key", pub.group(1));
        return map;
    }

    private static void generateSingBoxConfig(Path file, String uuid, String tuicPort, String hy2Port,
                                              String realityPort, String sni, Path cert, Path key, String privateKey)
            throws IOException {

        List<String> inbounds = new ArrayList<>();

        // TUIC
        if (!tuicPort.isEmpty()) {
            inbounds.add(String.format("""
                {
                  "type": "tuic",
                  "listen": "::",
                  "listen_port": %s,
                  "users": [{"uuid": "%s", "password": "admin"}],
                  "congestion_control": "bbr",
                  "zero_rtt_handshake": true,
                  "udp_relay_mode": "native",
                  "heartbeat": "10s",
                  "tls": {
                    "enabled": true,
                    "alpn": ["h3"],
                    "insecure": true,
                    "certificate_path": "%s",
                    "key_path": "%s"
                  }
                }""", tuicPort, uuid, cert, key));
        }

        // HY2
        if (!hy2Port.isEmpty()) {
            inbounds.add(String.format("""
                {
                  "type": "hysteria2",
                  "listen": "::",
                  "listen_port": %s,
                  "users": [{"password": "%s"}],
                  "masquerade": "https://%s",
                  "ignore_client_bandwidth": true,
                  "up_mbps": 1000,
                  "down_mbps": 1000,
                  "tls": {
                    "enabled": true,
                    "alpn": ["h3"],
                    "insecure": true,
                    "certificate_path": "%s",
                    "key_path": "%s"
                  }
                }""", hy2Port, uuid, sni, cert, key));
        }

        // Reality
        if (!realityPort.isEmpty()) {
            inbounds.add(String.format("""
                {
                  "type": "vless",
                  "listen": "::",
                  "listen_port": %s,
                  "users": [{"uuid": "%s", "flow": "xtls-rprx-vision"}],
                  "tls": {
                    "enabled": true,
                    "server_name": "%s",
                    "reality": {
                      "enabled": true,
                      "handshake": {"server": "%s", "server_port": 443},
                      "private_key": "%s",
                      "short_id": [""]
                    }
                  }
                }""", realityPort, uuid, sni, sni, privateKey));
        }

        String json = """
            {
              "log": {"level": "info"},
              "inbounds": [%s],
              "outbounds": [{"type": "direct"}]
            }""".formatted(String.join(",", inbounds));

        Files.writeString(file, json);
        System.out.println("✅ sing-box 配置生成完成");
    }

    private static String fetchLatestSingBoxVersion() {
        String fallback = "1.12.12";
        try {
            URL url = new URL("https://api.github.com/repos/SagerNet/sing-box/releases/latest");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String json = br.lines().reduce("", (a, b) -> a + b);
                int i = json.indexOf("\"tag_name\":\"v");
                if (i != -1)
                    return json.substring(i + 13, json.indexOf("\"", i + 13));
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static void safeDownloadSingBox(String version, Path bin, Path dir) throws IOException, InterruptedException {
        if (Files.exists(bin)) return;
        String arch = detectArch();
        String file = "sing-box-" + version + "-linux-" + arch + ".tar.gz";
        String url = "https://github.com/SagerNet/sing-box/releases/download/v" + version + "/" + file;
        new ProcessBuilder("bash", "-c",
                "curl -L -o " + dir.resolve(file) + " \"" + url + "\" && " +
                        "cd " + dir + " && tar -xzf " + file + " && " +
                        "mv $(find . -name sing-box -type f | head -1) ./sing-box && chmod +x ./sing-box")
                .inheritIO().start().waitFor();
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        return a.contains("arm") ? "arm64" : "amd64";
    }

    private static void startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        new ProcessBuilder("bash", "-c", bin + " run -c " + cfg + " > /tmp/singbox.log 2>&1 &").start();
        Thread.sleep(2000);
        System.out.println("🚀 Sing-box 已启动");
    }

    private static String detectPublicIP() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream()))) {
            return br.readLine();
        } catch (Exception e) {
            return "your-server-ip";
        }
    }

    private static void printDeployedLinks(String uuid, String host, String tuicPort, String hy2Port,
                                           String realityPort, String sni, String publicKey) {
        System.out.println("\n=== ✅ 部署成功节点 ===");
        if (!tuicPort.isEmpty())
            System.out.printf("TUIC:\ntuic://%s:admin@%s:%s?sni=%s&alpn=h3&allowInsecure=1#TUIC\n",
                    uuid, host, tuicPort, sni);
        if (!hy2Port.isEmpty())
            System.out.printf("\nHysteria2:\nhysteria2://%s@%s:%s?sni=%s&insecure=1&alpn=h3#HY2\n",
                    uuid, host, hy2Port, sni);
        if (!realityPort.isEmpty())
            System.out.printf("\nVLESS Reality:\nvless://%s@%s:%s?encryption=none&flow=xtls-rprx-vision&security=reality&sni=%s&pbk=%s#Reality\n",
                    uuid, host, realityPort, sni, publicKey);
    }

    private static void scheduleDailyRestart() {
        ScheduledExecutorService s = Executors.newScheduledThreadPool(1);
        Runnable r = () -> {
            System.out.println("[定时重启] 到达北京时间 00:00，准备重启...");
            try {
                new ProcessBuilder("bash", "-c", "pkill -f sing-box || true").start().waitFor();
                Thread.sleep(1000);
                new ProcessBuilder("bash", "-c",
                        "nohup java -Xms128M -XX:MaxRAMPercentage=95.0 -jar server.jar > /dev/null 2>&1 &").start();
                System.exit(0);
            } catch (Exception ignored) {}
        };
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        LocalDateTime next = now.withHour(0).withMinute(0).withSecond(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        long delay = Duration.between(now, next).toSeconds();
        s.scheduleAtFixedRate(r, delay, 86400, TimeUnit.SECONDS);
        System.out.printf("[定时重启] 每日北京时间 00:00 自动重启（首次：%s）%n", next);
    }
}
