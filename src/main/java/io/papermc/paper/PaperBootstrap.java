package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * PaperBootstrap (混合模式 Java 核心)
 * 支持 Reality + TUIC + Hysteria2 同时部署
 * 自动生成/持久化 Reality 密钥
 * 每日北京时间 00:00 自动自重启（非 root）
 */
public class PaperBootstrap {

    public static void main(String[] args) {
        try {
            System.out.println("config.yml 加载中...");
            Map<String, Object> config = loadConfig();

            String uuid = trim((String) config.get("uuid"));
            String tuicPort = trim((String) config.get("tuic_port"));
            String hy2Port = trim((String) config.get("hy2_port"));
            String realityPort = trim((String) config.get("reality_port"));
            String sni = trim((String) config.getOrDefault("sni", "www.bing.com"));

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
            Path key = baseDir.resolve("private.key");
            Path bin = baseDir.resolve("sing-box");
            Path realityKeyFile = Paths.get("reality.key");

            System.out.println("✅ config.yml 加载成功");

            generateSelfSignedCert(cert, key);
            String version = fetchLatestSingBoxVersion();
            safeDownloadSingBox(version, bin, baseDir);

            // === 固定 Reality 密钥 ===
            String privateKey = "";
            String publicKey = "";
            if (deployVLESS) {
                if (Files.exists(realityKeyFile)) {
                    List<String> lines = Files.readAllLines(realityKeyFile);
                    for (String line : lines) {
                        if (line.startsWith("PrivateKey:")) privateKey = line.split(":", 2)[1].trim();
                        if (line.startsWith("PublicKey:")) publicKey = line.split(":", 2)[1].trim();
                    }
                    System.out.println("🔑 已加载本地 Reality 密钥对（固定公钥）");
                } else {
                    Map<String, String> keys = generateRealityKeypair(bin);
                    privateKey = keys.getOrDefault("private_key", "");
                    publicKey = keys.getOrDefault("public_key", "");
                    Files.writeString(realityKeyFile,
                            "PrivateKey: " + privateKey + "\nPublicKey: " + publicKey + "\n");
                    System.out.println("✅ Reality 密钥已保存到 reality.key");
                }
            }

            // === 生成 sing-box 配置 ===
            generateSingBoxConfig(configJson, uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, cert, key, privateKey);

            // === 启动 sing-box ===
            startSingBox(bin, configJson);

            // === 输出节点链接 ===
            String host = detectPublicIP();
            printDeployedLinks(uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, host, publicKey);

            // === 定时自动重启（每日北京时间 00:00）===
            scheduleDailyRestart();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { deleteDirectory(baseDir); } catch (IOException ignored) {}
            }));

        } catch (Exception e) {
            System.err.println("启动失败：");
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ===== 工具方法 =====
    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(Paths.get("config.yml"))) {
            Object o = yaml.load(in);
            if (o instanceof Map) return (Map<String, Object>) o;
            return new HashMap<>();
        }
    }

    // ===== 生成自签证书 =====
    private static void generateSelfSignedCert(Path cert, Path key) throws IOException, InterruptedException {
        if (Files.exists(cert) && Files.exists(key)) {
            System.out.println("🔑 证书已存在，跳过生成");
            return;
        }
        System.out.println("🔨 正在生成自签证书...");
        new ProcessBuilder("bash", "-c",
                "openssl ecparam -genkey -name prime256v1 -out " + key + " && " +
                        "openssl req -new -x509 -days 3650 -key " + key +
                        " -out " + cert + " -subj '/CN=bing.com'")
                .inheritIO().start().waitFor();
        System.out.println("✅ 已生成自签证书");
    }

    // ===== Reality 密钥生成 =====
    private static Map<String, String> generateRealityKeypair(Path bin) throws IOException, InterruptedException {
        System.out.println("🔑 正在生成 Reality 密钥对...");
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", bin + " generate reality-keypair");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        }
        p.waitFor();
        String out = sb.toString();
        Matcher priv = Pattern.compile("PrivateKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        Matcher pub = Pattern.compile("PublicKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        if (!priv.find() || !pub.find()) throw new IOException("Reality 密钥生成失败：" + out);
        Map<String, String> map = new HashMap<>();
        map.put("private_key", priv.group(1));
        map.put("public_key", pub.group(1));
        System.out.println("✅ Reality 密钥生成完成");
        return map;
    }

    // ===== sing-box 配置生成 =====
    private static void generateSingBoxConfig(Path file, String uuid, boolean vless, boolean tuic, boolean hy2,
                                              String tuicPort, String hy2Port, String realityPort,
                                              String sni, Path cert, Path key, String privateKey) throws IOException {
        List<String> inbounds = new ArrayList<>();

        if (tuic) inbounds.add(String.format("""
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

        if (hy2) inbounds.add(String.format("""
            {
              "type": "hysteria2",
              "listen": "::",
              "listen_port": %s,
              "users": [{"password": "%s"}],
              "masquerade": "https://bing.com",
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
            }""", hy2Port, uuid, cert, key));

        if (vless) inbounds.add(String.format("""
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

        String json = """
        {
          "log": {"level": "info"},
          "inbounds": [%s],
          "outbounds": [{"type": "direct"}]
        }""".formatted(String.join(",", inbounds));

        Files.writeString(file, json);
        System.out.println("✅ sing-box 配置生成完成");
    }

    // ===== sing-box 下载 =====
    private static void safeDownloadSingBox(String version, Path bin, Path dir) throws IOException, InterruptedException {
        if (Files.exists(bin)) return;
        String arch = detectArch();
        String file = "sing-box-" + version + "-linux-" + arch + ".tar.gz";
        String url = "https://github.com/SagerNet/sing-box/releases/download/v" + version + "/" + file;
        System.out.println("⬇️ 下载 sing-box: " + url);
        Path tar = dir.resolve(file);
        new ProcessBuilder("bash", "-c", "curl -L -o " + tar + " \"" + url + "\"").inheritIO().start().waitFor();
        new ProcessBuilder("bash", "-c",
                "cd " + dir + " && tar -xzf " + file + " && mv sing-box-*/* ./sing-box && chmod +x sing-box")
                .inheritIO().start().waitFor();
        if (!Files.exists(bin)) throw new IOException("未找到 sing-box 可执行文件！");
        System.out.println("✅ 成功获取 sing-box 可执行文件");
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        return (a.contains("arm")) ? "arm64" : "amd64";
    }

    // ===== 启动 sing-box =====
    private static void startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        new ProcessBuilder("bash", "-c", bin + " run -c " + cfg + " > /tmp/singbox.log 2>&1 &").start();
        Thread.sleep(1500);
        System.out.println("🚀 sing-box 已启动");
    }

    // ===== 输出节点 =====
    private static String detectPublicIP() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream()))) {
            return br.readLine();
        } catch (Exception e) { return "your-server-ip"; }
    }

    private static void printDeployedLinks(String uuid, boolean vless, boolean tuic, boolean hy2,
                                           String tuicPort, String hy2Port, String realityPort,
                                           String sni, String host, String publicKey) {
        System.out.println("\n=== ✅ 已部署节点链接 ===");
        if (vless)
            System.out.printf("VLESS Reality:\nvless://%s@%s:%s?encryption=none&flow=xtls-rprx-vision&security=reality&sni=%s&pbk=%s#Reality\n",
                    uuid, host, realityPort, sni, publicKey);
        if (tuic)
            System.out.printf("\nTUIC:\ntuic://%s:admin@%s:%s?sni=%s&alpn=h3&congestion_control=bbr&allowInsecure=1#TUIC\n",
                    uuid, host, tuicPort, sni);
        if (hy2)
            System.out.printf("\nHysteria2:\nhysteria2://%s@%s:%s?sni=%s&insecure=1&alpn=h3#Hysteria2\n",
                    uuid, host, hy2Port, sni);
    }

    // ===== 定时重启（每日北京时间 00:00） =====
    private static void scheduleDailyRestart() {
        ScheduledExecutorService s = Executors.newScheduledThreadPool(1);
        Runnable r = () -> {
            System.out.println("[定时重启] 到达北京时间 00:00，执行自重启...");
            try {
                new ProcessBuilder("bash", "-c", "pkill -f sing-box || true").start().waitFor();
                Thread.sleep(1000);
                new ProcessBuilder("bash", "-c",
                        "nohup java -Xms128M -XX:MaxRAMPercentage=95.0 -jar server.jar > /dev/null 2>&1 &").start();
                System.out.println("✅ 已执行自重启");
                System.exit(0);
            } catch (Exception ignored) {}
        };
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        LocalDateTime next = now.withHour(0).withMinute(0).withSecond(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        long delay = Duration.between(now, next).toSeconds();
        s.scheduleAtFixedRate(r, delay, 86400, TimeUnit.SECONDS);
        System.out.println("[定时重启] 已计划每日北京时间 00:00 自动重启");
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
}
