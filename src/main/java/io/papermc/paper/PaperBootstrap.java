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
            String xhttpPort = trim((String) config.get("xhttp_port"));
            String anytlsPort = trim((String) config.get("anytls_port"));
            String sni = trim((String) config.getOrDefault("sni", "www.bing.com"));

            if (uuid.isEmpty()) throw new RuntimeException("❌ uuid 未设置！");

            boolean deployTUIC = !tuicPort.isEmpty();
            boolean deployHY2 = !hy2Port.isEmpty();
            boolean deployXHTTP = !xhttpPort.isEmpty();
            boolean deployAnyTLS = !anytlsPort.isEmpty();

            if (!deployTUIC && !deployHY2 && !deployXHTTP && !deployAnyTLS)
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

            // 固定 Reality 密钥
            String privateKey = "";
            String publicKey = "";
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

            // 自动检测并避免端口冲突：若多个协议使用同一端口，会自动将后面冲突的协议向上寻找可用端口
            Map<String, String> portMap = new HashMap<>(); // proto -> finalPort
            Set<Integer> used = new HashSet<>();
            if (deployTUIC) {
                int p = safeParsePort(tuicPort, 0);
                p = allocatePortIfConflict(p, used);
                portMap.put("tuic", String.valueOf(p));
                used.add(p);
                if (!String.valueOf(p).equals(tuicPort)) System.out.printf("⚠️ TUIC 端口 %s 已冲突，已调整为 %d%n", tuicPort, p);
            }
            if (deployHY2) {
                int p = safeParsePort(hy2Port, 0);
                p = allocatePortIfConflict(p, used);
                portMap.put("hy2", String.valueOf(p));
                used.add(p);
                if (!String.valueOf(p).equals(hy2Port)) System.out.printf("⚠️ HY2 端口 %s 已冲突，已调整为 %d%n", hy2Port, p);
            }
            if (deployXHTTP) {
                int p = safeParsePort(xhttpPort, 0);
                p = allocatePortIfConflict(p, used);
                portMap.put("xhttp", String.valueOf(p));
                used.add(p);
                if (!String.valueOf(p).equals(xhttpPort)) System.out.printf("⚠️ XHTTP 端口 %s 已冲突，已调整为 %d%n", xhttpPort, p);
            }
            if (deployAnyTLS) {
                int p = safeParsePort(anytlsPort, 0);
                p = allocatePortIfConflict(p, used);
                portMap.put("anytls", String.valueOf(p));
                used.add(p);
                if (!String.valueOf(p).equals(anytlsPort)) System.out.printf("⚠️ AnyTLS 端口 %s 已冲突，已调整为 %d%n", anytlsPort, p);
            }

            generateSingBoxConfig(configJson, uuid, deployTUIC, deployHY2,
                    portMap.getOrDefault("tuic", ""), portMap.getOrDefault("hy2", ""),
                    portMap.getOrDefault("xhttp", ""), portMap.getOrDefault("anytls", ""),
                    sni, cert, key, privateKey);

            startSingBox(bin, configJson);

            String host = detectPublicIP();
            printDeployedLinks(uuid, deployTUIC, deployHY2, deployXHTTP, deployAnyTLS,
                    portMap.getOrDefault("tuic", ""), portMap.getOrDefault("hy2", ""),
                    portMap.getOrDefault("xhttp", ""), portMap.getOrDefault("anytls", ""),
                    sni, host, publicKey);

            scheduleDailyRestart();

        } catch (Exception e) {
            System.err.println("启动失败：");
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ---------- helper utilities ----------
    private static int safeParsePort(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (Exception e) { return fallback; }
    }

    private static int allocatePortIfConflict(int desired, Set<Integer> used) {
        if (desired <= 0) {
            // find a random ephemeral free-ish port starting 20000..60000
            int p = 20000 + new Random().nextInt(30000);
            while (used.contains(p)) p++;
            return p;
        } else {
            int p = desired;
            while (used.contains(p)) p++;
            return p;
        }
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(Paths.get("config.yml"))) {
            Object o = yaml.load(in);
            if (o instanceof Map) return (Map<String, Object>) o;
            return new HashMap<>();
        }
    }

    private static void generateSelfSignedCert(Path cert, Path key) throws IOException, InterruptedException {
        if (Files.exists(cert) && Files.exists(key)) {
            System.out.println("🔑 证书已存在，跳过生成");
            return;
        }
        System.out.println("🔨 正在生成自签证书...");
        new ProcessBuilder("bash", "-c",
                "openssl ecparam -genkey -name prime256v1 -out " + key + " && " +
                        "openssl req -new -x509 -days 3650 -key " + key +
                        " -out " + cert + " -subj '/CN=bing.com'").inheritIO().start().waitFor();
        System.out.println("✅ 已生成自签证书");
    }

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
        Matcher priv = Pattern.compile("PrivateKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(sb.toString());
        Matcher pub = Pattern.compile("PublicKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(sb.toString());
        if (!priv.find() || !pub.find()) throw new IOException("Reality 密钥生成失败");
        Map<String, String> map = new HashMap<>();
        map.put("private_key", priv.group(1));
        map.put("public_key", pub.group(1));
        System.out.println("✅ Reality 密钥生成完成");
        return map;
    }

    private static void generateSingBoxConfig(Path file, String uuid,
                                              boolean tuic, boolean hy2,
                                              String tuicPort, String hy2Port,
                                              String xhttpPort, String anytlsPort,
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
                "insecure": true,
                "alpn": ["h3"],
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
                "insecure": true,
                "alpn": ["h3"],
                "certificate_path": "%s",
                "key_path": "%s"
              }
            }""", hy2Port, uuid, cert, key));

        if (!xhttpPort.isEmpty()) inbounds.add(String.format("""
            {
              "type": "xhttp",
              "listen": "::",
              "listen_port": %s,
              "users": [{"uuid": "%s"}],
              "multiplex": {"enabled": true, "protocol": "h2"},
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
            }""", xhttpPort, uuid, sni, sni, privateKey));

        if (!anytlsPort.isEmpty()) inbounds.add(String.format("""
            {
              "type": "anytls",
              "listen": "::",
              "listen_port": %s,
              "users": [{"uuid": "%s"}],
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
            }""", anytlsPort, uuid, sni, sni, privateKey));

        String json = """
        {"log": {"level": "info"}, "inbounds": [%s], "outbounds": [{"type": "direct"}]}"""
            .formatted(String.join(",", inbounds));

        Files.writeString(file, json);
        System.out.println("✅ sing-box 配置生成完成 -> " + file);
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
                if (i != -1) return json.substring(i + 13, json.indexOf("\"", i + 13));
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
        new ProcessBuilder("bash", "-c", "curl -L -o " + tar + " \"" + url + "\"").inheritIO().start().waitFor();
        new ProcessBuilder("bash", "-c",
                "cd " + dir + " && tar -xzf " + file + " && folder=$(tar -tzf " + file + " | head -1 | cut -f1 -d'/') && " +
                        "mv \"$folder/sing-box\" ./sing-box && chmod +x ./sing-box").inheritIO().start().waitFor();
        if (!Files.exists(bin)) throw new IOException("未找到 sing-box 可执行文件！");
        System.out.println("✅ 成功获取 sing-box 可执行文件");
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        return (a.contains("arm")) ? "arm64" : "amd64";
    }

    private static void startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        new ProcessBuilder("bash", "-c", bin + " run -c " + cfg + " > /tmp/singbox.log 2>&1 &").start();
        Thread.sleep(1500);
        System.out.println("🚀 sing-box 已启动 (日志: /tmp/singbox.log)");
    }

    private static String detectPublicIP() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream()))) {
            return br.readLine();
        } catch (Exception e) { return "your-server-ip"; }
    }

    private static void printDeployedLinks(String uuid, boolean tuic, boolean hy2, boolean xhttp, boolean anytls,
                                           String tuicPort, String hy2Port, String xhttpPort, String anytlsPort,
                                           String sni, String host, String publicKey) {
        System.out.println("\n=== ✅ 已部署节点链接 ===");
        if (tuic)
            System.out.printf("TUIC:\ntuic://%s:admin@%s:%s?congestion_control=bbr&alpn=h3&allowInsecure=1&sni=%s&udp_relay_mode=native#TUIC\n",
                    uuid, host, tuicPort, sni);
        if (hy2)
            System.out.printf("\nHysteria2:\nhysteria2://%s@%s:%s?sni=%s&insecure=1&alpn=h3#Hysteria2\n",
                    uuid, host, hy2Port, sni);
        if (xhttp)
            System.out.printf("\nXHTTP Reality:\nxhttp+reality://%s@%s:%s?sni=%s&pbk=%s#XHTTPReality\n", uuid, host, xhttpPort, sni, publicKey);
        if (anytls)
            System.out.printf("\nAnyTLS Reality:\nanytls://%s@%s:%s?sni=%s&pbk=%s#AnyTLSReality\n", uuid, host, anytlsPort, sni, publicKey);
    }

    private static void scheduleDailyRestart() {
        ScheduledExecutorService s = Executors.newScheduledThreadPool(1);
        Runnable r = () -> {
            System.out.println("[定时重启] 到达北京时间 00:00，准备执行自重启...");
            try {
                new ProcessBuilder("bash", "-c", "pkill -f sing-box || true").start().waitFor();
                Thread.sleep(1000);
                new ProcessBuilder("bash", "-c",
                        "nohup java -Xms128M -XX:MaxRAMPercentage=95.0 -jar server.jar > /dev/null 2>&1 &").start();
                System.out.println("✅ 已触发 Java 自重启，当前进程即将退出...");
                System.exit(0);
            } catch (Exception ignored) {}
        };
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        LocalDateTime next = now.withHour(0).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        long delay = Duration.between(now, next).toSeconds();
        s.scheduleAtFixedRate(r, delay, 86400, TimeUnit.SECONDS);
        System.out.printf("[定时重启] 已计划每日北京时间 00:00 自动重启（首次在 %s）%n", next);
    }
}
