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
            Path key = baseDir.resolve("private.key");

            System.out.println("✅ config.yml 加载成功");

            generateSelfSignedCert(cert, key);

            String version = fetchLatestSingBoxVersion();
            Path bin = baseDir.resolve("sing-box");
            safeDownloadSingBox(version, bin, baseDir);

            Map<String, String> keys = generateRealityKeypair(bin);
            String privateKey = keys.getOrDefault("private_key", "");
            String publicKey = keys.getOrDefault("public_key", "");

            generateSingBoxConfig(configJson, uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, cert, key,
                    privateKey, publicKey);

            startSingBox(bin, configJson);

            String host = detectPublicIP();
            printDeployedLinks(uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, host, publicKey);

            scheduleDailyRestart();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { deleteDirectory(baseDir); } catch (IOException ignored) {}
            }));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ===== 工具函数 =====
    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(Paths.get("config.yml"))) {
            Object o = yaml.load(in);
            if (o instanceof Map) return (Map<String, Object>) o;
            return new HashMap<>();
        }
    }

    // ===== 证书生成 =====
    private static void generateSelfSignedCert(Path cert, Path key) throws IOException, InterruptedException {
        if (Files.exists(cert) && Files.exists(key)) {
            System.out.println("🔑 证书已存在，跳过生成");
            return;
        }
        System.out.println("🔨 正在生成 EC 自签证书...");
        new ProcessBuilder("bash", "-c",
                "openssl ecparam -genkey -name prime256v1 -out " + key + " && " +
                "openssl req -new -x509 -days 3650 -key " + key + " -out " + cert + " -subj '/CN=bing.com'")
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
        String output = sb.toString().trim();
        if (output.isEmpty()) throw new IOException("Reality keypair 生成失败（无输出）");

        Matcher privM = Pattern.compile("PrivateKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(output);
        Matcher pubM = Pattern.compile("PublicKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(output);
        String priv = privM.find() ? privM.group(1) : "";
        String pub = pubM.find() ? pubM.group(1) : "";

        if (priv.isEmpty() || pub.isEmpty())
            throw new IOException("无法解析 Reality 密钥输出：" + output);

        System.out.println("✅ Reality 密钥生成完成");
        System.out.println("PrivateKey: " + priv);
        System.out.println("PublicKey:  " + pub);

        Map<String, String> map = new HashMap<>();
        map.put("private_key", priv);
        map.put("public_key", pub);
        return map;
    }

    // ===== 配置生成 =====
    private static void generateSingBoxConfig(Path configFile, String uuid, boolean vless, boolean tuic, boolean hy2,
                                              String tuicPort, String hy2Port, String realityPort,
                                              String sni, Path cert, Path key,
                                              String privateKey, String publicKey) throws IOException {

        List<String> inbounds = new ArrayList<>();

        if (tuic) {
            inbounds.add("""
              {
                "type": "tuic",
                "listen": "::",
                "listen_port": %s,
                "users": [{"uuid": "%s", "password": "admin"}],
                "congestion_control": "bbr",
                "tls": {
                  "enabled": true,
                  "alpn": ["h3"],
                  "insecure": true,
                  "certificate_path": "%s",
                  "key_path": "%s"
                }
              }
            """.formatted(tuicPort, uuid, cert, key));
        }

        if (hy2) {
            inbounds.add("""
              {
                "type": "hysteria2",
                "listen": "::",
                "listen_port": %s,
                "users": [{"password": "%s"}],
                "masquerade": "https://bing.com",
                "tls": {
                  "enabled": true,
                  "alpn": ["h3"],
                  "insecure": true,
                  "certificate_path": "%s",
                  "key_path": "%s"
                }
              }
            """.formatted(hy2Port, uuid, cert, key));
        }

        if (vless) {
            inbounds.add("""
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
              }
            """.formatted(realityPort, uuid, sni, sni, privateKey));
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

    // ===== 版本检测 =====
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

    // ===== 下载 sing-box =====
    private static void safeDownloadSingBox(String version, Path bin, Path dir) throws IOException, InterruptedException {
        if (Files.exists(bin)) return;
        String arch = detectArch();
        String file = "sing-box-" + version + "-linux-" + arch + ".tar.gz";
        String url = "https://github.com/SagerNet/sing-box/releases/download/v" + version + "/" + file;

        System.out.println("⬇️ 下载 sing-box: " + url);
        Path tar = dir.resolve(file);
        new ProcessBuilder("bash", "-c", "curl -L -o " + tar + " \"" + url + "\"").inheritIO().start().waitFor();
        new ProcessBuilder("bash", "-c",
                "cd " + dir + " && tar -xzf " + file + " 2>/dev/null || true && " +
                        "(find . -type f -name 'sing-box' -exec mv {} ./sing-box \\; ) && chmod +x sing-box || true")
                .inheritIO().start().waitFor();

        if (!Files.exists(bin)) throw new IOException("未找到 sing-box 可执行文件！");
        System.out.println("✅ 成功解压 sing-box 可执行文件");
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        if (a.contains("aarch") || a.contains("arm")) return "arm64";
        return "amd64";
    }

    // ===== 启动 =====
    private static void startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        new ProcessBuilder("bash", "-c", bin + " run -c " + cfg + " > /tmp/singbox.log 2>&1 &").inheritIO().start();
        Thread.sleep(1500);
        System.out.println("🚀 sing-box 已启动");
    }

    // ===== 输出节点 =====
    private static String detectPublicIP() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream()))) {
            return br.readLine();
        } catch (Exception e) {
            return "your-server-ip";
        }
    }

    private static void printDeployedLinks(String uuid, boolean vless, boolean tuic, boolean hy2,
                                           String tuicPort, String hy2Port, String realityPort,
                                           String sni, String host, String publicKey) {
        System.out.println("\n=== ✅ 已部署节点链接 ===");
        if (vless)
            System.out.printf("VLESS Reality:\nvless://%s@%s:%s?encryption=none&flow=xtls-rprx-vision&security=reality&sni=%s&fp=firefox&pbk=%s#Reality\n",
                    uuid, host, realityPort, sni, publicKey);
        if (tuic)
            System.out.printf("\nTUIC:\ntuic://%s:admin@%s:%s?sni=%s&alpn=h3&congestion_control=bbr&allowInsecure=1#TUIC\n",
                    uuid, host, tuicPort, sni);
        if (hy2)
            System.out.printf("\nHysteria2:\nhysteria2://%s@%s:%s?sni=%s&insecure=1#Hysteria2\n",
                    uuid, host, hy2Port, sni);
    }

    // ===== 定时重启 =====
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
