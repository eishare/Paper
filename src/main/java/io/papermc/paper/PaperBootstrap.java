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
 * PaperBootstrap — sing-box 自动部署启动器（优化版）
 *
 * 特性：
 * - 支持 VLESS/Reality、TUIC、Hysteria2，允许用户在 config.yml 中任意组合启用
 * - TUIC/Hysteria2 性能优化（alpn=h3, tuic: bbr/zero_rtt/udp_native/heartbeat）
 * - Reality 密钥对自动生成并注入
 * - 下载最新 sing-box release（自动检测版本），解压可执行文件
 * - 将临时文件放入 /tmp/.singbox，退出时清理
 * - 非 root 环境下每日北京时间 12:45 自动自重启（杀 sing-box + 重启 server.jar）
 *
 * config.yml 示例（放在 server.jar 同目录）：
 * uuid: "xxxx-xxxx-xxxx-xxxx"
 * tuic_port: "25690"
 * hy2_port: ""
 * reality_port: "25690"
 * sni: "www.bing.com"
 *
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

            // 基础目录（放在 tmp，避免根目录污染）
            Path baseDir = Paths.get("/tmp/.singbox");
            Files.createDirectories(baseDir);

            Path configJson = baseDir.resolve("config.json");
            Path cert = baseDir.resolve("cert.pem");
            Path key = baseDir.resolve("private.key");
            Path bin = baseDir.resolve("sing-box");

            System.out.println("✅ config.yml 加载成功");

            // 生成证书（如果不存在）
            generateSelfSignedCert(cert, key);

            // 获取版本并下载 sing-box 可执行
            String version = fetchLatestSingBoxVersion();
            safeDownloadSingBox(version, bin, baseDir);

            // 生成 Reality keypair（如果用户启用了 reality）
            String privateKey = "";
            String publicKey = "";
            if (deployVLESS) {
                Map<String, String> keys = generateRealityKeypair(bin);
                privateKey = keys.getOrDefault("private_key", "");
                publicKey = keys.getOrDefault("public_key", "");
            }

            // 生成 sing-box config（含优化项：tuic/hy2 performance）
            generateSingBoxConfig(configJson, uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, cert, key, privateKey);

            // 启动 sing-box
            startSingBox(bin, configJson);

            // 输出订阅/节点信息（只输出启用的）
            String host = detectPublicIP();
            printDeployedLinks(uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, host, publicKey);

            // 定时每日北京时间 12:45 自重启（非 root，自重启模式）
            scheduleDailyRestart();

            // 退出时清理 baseDir
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { deleteDirectory(baseDir); } catch (IOException ignored) {}
            }));

        } catch (Exception e) {
            System.err.println("启动失败：");
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ===== utils =====
    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        Path cfg = Paths.get("config.yml");
        if (!Files.exists(cfg)) throw new FileNotFoundException("config.yml 不存在，请放到 server.jar 同目录");
        try (InputStream in = Files.newInputStream(cfg)) {
            Object o = yaml.load(in);
            if (o instanceof Map) return (Map<String, Object>) o;
            return new HashMap<>();
        }
    }

    // ===== 证书 =====
    private static void generateSelfSignedCert(Path cert, Path key) throws IOException, InterruptedException {
        if (Files.exists(cert) && Files.exists(key)) {
            System.out.println("🔑 证书已存在，跳过生成");
            return;
        }
        System.out.println("🔨 正在生成 EC 自签证书...");
        ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "openssl ecparam -genkey -name prime256v1 -out " + key + " 2>/dev/null && " +
                "openssl req -new -x509 -days 3650 -key " + key + " -out " + cert + " -subj '/CN=bing.com' 2>/dev/null");
        pb.inheritIO().start().waitFor();
        System.out.println("✅ 已生成自签证书");
    }

    // ===== Reality 密钥 =====
    private static Map<String, String> generateRealityKeypair(Path bin) throws IOException, InterruptedException {
        System.out.println("🔑 正在生成 Reality 密钥对...");
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", bin.toString() + " generate reality-keypair");
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

        // 解析 PrivateKey, PublicKey, ShortId（可能无）
        Matcher privM = Pattern.compile("PrivateKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(output);
        Matcher pubM = Pattern.compile("PublicKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(output);
        Matcher sidM = Pattern.compile("ShortId[:\\s]*([A-Za-z0-9_\\-+/=]*)").matcher(output);

        String priv = privM.find() ? privM.group(1) : "";
        String pub = pubM.find() ? pubM.group(1) : "";
        String sid = sidM.find() ? sidM.group(1) : "";

        if (priv.isEmpty() || pub.isEmpty()) {
            System.out.println("Reality 输出（原文）:\n" + output);
            throw new IOException("无法解析 Reality 密钥输出");
        }

        System.out.println("✅ Reality 密钥生成完成");
        System.out.println("PrivateKey: " + priv);
        System.out.println("PublicKey:  " + pub);
        if (!sid.isBlank()) System.out.println("ShortId:    " + sid);

        Map<String, String> map = new HashMap<>();
        map.put("private_key", priv);
        map.put("public_key", pub);
        map.put("short_id", sid);
        return map;
    }

    // ===== 生成 sing-box 配置（含性能优化） =====
    private static void generateSingBoxConfig(Path configFile, String uuid, boolean vless, boolean tuic, boolean hy2,
                                              String tuicPort, String hy2Port, String realityPort,
                                              String sni, Path cert, Path key, String privateKey) throws IOException {
        List<String> inbounds = new ArrayList<>();

        // TUIC - 优化：bbr, zero_rtt_handshake, udp_native, heartbeat, alpn=h3
        if (tuic) {
            inbounds.add(String.format("""
              {
                "type": "tuic",
                "tag": "tuic-in",
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
              }
            """, tuicPort, uuid, cert.toString(), key.toString()));
        }

        // Hysteria2 - 优化：alpn=h3, ignore_client_bandwidth, up/down limits
        if (hy2) {
            inbounds.add(String.format("""
              {
                "type": "hysteria2",
                "tag": "hy2-in",
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
              }
            """, hy2Port, uuid, cert.toString(), key.toString()));
        }

        // VLESS Reality - keep reality enabled with private key
        if (vless) {
            inbounds.add(String.format("""
              {
                "type": "vless",
                "tag": "reality-in",
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
            """, realityPort, uuid, sni, sni, privateKey));
        }

        String json = String.format("""
        {
          "log": {"level":"info"},
          "inbounds": [%s],
          "outbounds": [{"type":"direct","tag":"direct"}]
        }
        """, String.join(",", inbounds));

        Files.writeString(configFile, json);
        System.out.println("✅ sing-box 配置生成完成 -> " + configFile);
    }

    // ===== 获取最新 sing-box 版本 tag（无 v 前缀） =====
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
                int idx = json.indexOf("\"tag_name\":\"v");
                if (idx != -1) {
                    String t = json.substring(idx + 13, json.indexOf("\"", idx + 13));
                    System.out.println("🔍 检测到最新 sing-box 版本: " + t);
                    return t;
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ 无法访问 GitHub API，使用回退版本 " + fallback);
        }
        return fallback;
    }

    // ===== 下载并提取 sing-box 可执行（放到 bin） =====
    private static void safeDownloadSingBox(String version, Path bin, Path dir) throws IOException, InterruptedException {
        if (Files.exists(bin)) {
            System.out.println("🟢 sing-box 已存在，跳过下载");
            return;
        }

        String arch = detectArch();
        String filename = "sing-box-" + version + "-linux-" + arch + ".tar.gz";
        String url = "https://github.com/SagerNet/sing-box/releases/download/v" + version + "/" + filename;

        System.out.println("⬇️ 下载 sing-box: " + url);
        Path tar = dir.resolve(filename);
        // 下载
        ProcessBuilder dl = new ProcessBuilder("bash", "-c",
                "set -e; " +
                "if command -v curl >/dev/null 2>&1; then curl -L -s -o '" + tar + "' '" + url + "'; " +
                "elif command -v wget >/dev/null 2>&1; then wget -q -O '" + tar + "' '" + url + "'; else echo 'no-curl-wget'; fi");
        dl.inheritIO().start().waitFor();

        // 解压并寻找 sing-box 可执行
        ProcessBuilder extract = new ProcessBuilder("bash", "-c",
                "cd " + dir + " && tar -xzf " + filename + " 2>/dev/null || true && " +
                        "shopt -s nullglob || true; for d in sing-box-*; do if [ -f \"$d/sing-box\" ]; then mv \"$d/sing-box\" ./sing-box; fi; done || true; chmod +x sing-box || true");
        extract.inheritIO().start().waitFor();

        if (!Files.exists(bin) || !isELFFile(bin)) {
            throw new IOException("未找到 sing-box 可执行文件或文件不合法，请手动检查：" + bin);
        }
        System.out.println("✅ 成功获取 sing-box 可执行: " + bin);
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        if (a.contains("aarch") || a.contains("arm")) return "arm64";
        return "amd64";
    }

    private static boolean isELFFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] header = new byte[4];
            if (in.read(header) != 4) return false;
            return header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    // ===== start sing-box =====
    private static void startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", bin.toString() + " run -c " + cfg.toString() + " > /tmp/singbox.log 2>&1 &");
        pb.inheritIO().start();
        Thread.sleep(1500);
        System.out.println("🚀 sing-box 已启动（日志：/tmp/singbox.log）");
    }

    // ===== 输出订阅/节点 =====
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
        if (vless) {
            // 向客户端提供 public key(pbk) 以便 Reality 配置填写
            System.out.printf("VLESS Reality:%nvless://%s@%s:%s?encryption=none&flow=xtls-rprx-vision&security=reality&sni=%s&pbk=%s#Reality%n",
                    uuid, host, realityPort, sni, publicKey == null ? "" : publicKey);
        }
        if (tuic) {
            // tuic 使用 password=admin（配置中也一致）
            System.out.printf("%nTUIC:%ntuic://%s:admin@%s:%s?sni=%s&alpn=h3&congestion_control=bbr&zero_rtt=1&udp_native=1#TUIC%n",
                    uuid, host, tuicPort, sni);
        }
        if (hy2) {
            System.out.printf("%nHysteria2:%nhy2://%s@%s:%s?sni=%s&insecure=1&alpn=h3#Hysteria2%n",
                    uuid, host, hy2Port, sni);
        }
    }

    // ===== 定时重启：每日北京时间 12:45（非 root 自重启） =====
    private static void scheduleDailyRestart() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        Runnable task = () -> {
            System.out.println("[定时重启] 到达北京时间 12:45，准备执行自重启...");
            try {
                // 停止 sing-box
                new ProcessBuilder("bash", "-c", "pkill -f sing-box || true").start().waitFor();
                Thread.sleep(1200);

                // 触发 Java 自重启：在后台启动新进程，然后退出当前
                new ProcessBuilder("bash", "-c",
                        "nohup java -Xms128M -XX:MaxRAMPercentage=95.0 -jar server.jar > /dev/null 2>&1 &").start();

                System.out.println("✅ 已触发 Java 自重启，当前进程即将退出...");
                System.exit(0);
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        // 计算今天/明天的 12:45（北京时间）
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        LocalDateTime next = now.withHour(12).withMinute(45).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        long delay = Duration.between(now, next).toSeconds();

        scheduler.scheduleAtFixedRate(task, delay, 24 * 3600L, TimeUnit.SECONDS);
        System.out.printf("[定时重启] 已计划每日北京时间 12:45 自动重启（首次在 %s，%d 秒后）%n",
                next.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), delay);
    }

    // ===== 清理 =====
    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }
}
