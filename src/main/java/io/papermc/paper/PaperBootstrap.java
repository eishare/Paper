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
            String tuicPortStr = trim((String) config.get("tuic_port"));
            String hy2PortStr = trim((String) config.get("hy2_port"));
            String realityPortStr = trim((String) config.get("reality_port"));
            String sni = trim((String) config.getOrDefault("sni", "www.nazhumi.com")); // 推荐！

            if (uuid.isEmpty()) throw new RuntimeException("uuid 未设置！");

            // === 修复：端口转整数 ===
            int tuicPort = parsePort(tuicPortStr);
            int hy2Port = parsePort(hy2PortStr);
            int realityPort = parsePort(realityPortStr);

            boolean deployTUIC = tuicPort > 0;
            boolean deployHY2 = hy2Port > 0;
            boolean deployReality = realityPort > 0;

            if (!deployTUIC && !deployHY2 && !deployReality)
                throw new RuntimeException("未配置任何协议端口！");

            Path baseDir = Paths.get("/tmp/.singbox");
            Files.createDirectories(baseDir);

            Path configJson = baseDir.resolve("config.json");
            Path cert = baseDir.resolve("cert.pem");
            Path key = baseDir.resolve("private.key");
            Path bin = baseDir.resolve("sing-box");
            Path realityKeyFile = baseDir.resolve("reality.key"); // 修复路径

            System.out.println("config.yml 加载成功");

            generateSelfSignedCert(cert, key);
            String version = fetchLatestSingBoxVersion();
            safeDownloadSingBox(version, bin, baseDir);

            String privateKey = "", publicKey = "";
            if (deployReality) {
                if (Files.exists(realityKeyFile)) {
                    List<String> lines = Files.readAllLines(realityKeyFile);
                    for (String line : lines) {
                        if (line.startsWith("PrivateKey:")) privateKey = line.split(":", 2)[1].trim();
                        if (line.startsWith("PublicKey:")) publicKey = line.split(":", 2)[1].trim();
                    }
                    System.out.println("已加载固定 Reality 密钥对");
                } else {
                    Map<String, String> keys = generateRealityKeypair(bin);
                    privateKey = keys.get("private");   // 修复键名
                    publicKey = keys.get("public");
                    Files.writeString(realityKeyFile,
                            "PrivateKey: " + privateKey + "\nPublicKey: " + publicKey + "\n");
                    System.out.println("已生成并保存 Reality 密钥");
                }
            }

            generateSingBoxConfig(configJson, uuid, deployTUIC, deployHY2, deployReality,
                    tuicPort, hy2Port, realityPort, sni, cert, key, privateKey);

            startSingBox(bin, configJson);
            String host = detectPublicIP();
            printDeployedLinks(uuid, host, tuicPort, hy2Port, realityPort, sni, publicKey);
            scheduleNonRootRestart(baseDir); // 非 root 重启

        } catch (Exception e) {
            System.err.println("启动失败：");
            e.printStackTrace();
            System.exit(1);
        }
    }

    // === 新增：端口解析 ===
    private static int parsePort(String port) {
        if (port == null || port.trim().isEmpty()) return 0;
        try {
            return Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            System.out.println("端口格式错误: " + port);
            return 0;
        }
    }

    // === 修复：密钥生成返回键名统一 ===
    private static Map<String, String> generateRealityKeypair(Path bin) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(bin.toString(), "generate", "reality-keypair");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();

        Matcher priv = Pattern.compile("PrivateKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        Matcher pub = Pattern.compile("PublicKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        if (!priv.find() || !pub.find()) throw new IOException("密钥生成失败");

        Map<String, String> map = new HashMap<>();
        map.put("private", priv.group(1));
        map.put("public", pub.group(1));
        return map;
    }

    // === 修复：使用整数端口 + 强制 bbr ===
    private static void generateSingBoxConfig(Path file, String uuid,
                                              boolean tuic, boolean hy2, boolean reality,
                                              int tuicPort, int hy2Port, int realityPort,
                                              String sni, Path cert, Path key, String privateKey) throws IOException {
        List<String> inbounds = new ArrayList<>();

        if (tuic) inbounds.add(String.format("""
            {
              "type": "tuic",
              "listen": "::",
              "listen_port": %d,
              "users": [{"uuid": "%s", "password": "admin"}],
              "congestion_control": "bbr",
              "udp_relay_mode": "native",
              "zero_rtt_handshake": true,
              "heartbeat": "10s",
              "tls": {
                "enabled": true,
                "alpn": ["h3"],
                "certificate_path": "%s",
                "key_path": "%s"
              }
            }""", tuicPort, uuid, cert, key));

        if (hy2) inbounds.add(String.format("""
            {
              "type": "hysteria2",
              "listen": "::",
              "listen_port": %d,
              "users": [{"password": "%s"}],
              "masquerade": "https://%s",
              "tls": {
                "enabled": true,
                "alpn": ["h3"],
                "certificate_path": "%s",
                "key_path": "%s"
              }
            }""", hy2Port, uuid, sni, cert, key));

        if (reality) inbounds.add(String.format("""
            {
              "type": "vless",
              "listen": "::",
              "listen_port": %d,
              "users": [{"uuid": "%s", "flow": "xtls-rprx-vision"}],
              "tls": {
                "enabled": true,
                "server_name": "%s",
                "reality": {
                  "enabled": true,
                  "handshake": {"server": "%s", "server_port": 443},
                  "private_key": "%s",
                  "short_id": ["eishare2"]
                }
              },
              "multiplex": {"enabled": true, "protocol": "smux"}
            }""", realityPort, uuid, sni, sni, privateKey));

        String json = """
            {"log": {"level": "info"}, "inbounds": [%s], "outbounds": [{"type": "direct"}]}"""
            .formatted(String.join(",", inbounds));
        Files.writeString(file, json);
        System.out.println("sing-box 配置生成完成");
    }

    // === 修复：非 root 重启（使用 cron）===
    private static void scheduleNonRootRestart(Path baseDir) throws IOException {
        Path restartScript = baseDir.resolve("restart.sh");
        String jarPath = System.getProperty("user.dir") + "/server.jar";
        Files.writeString(restartScript, """
            #!/bin/bash
            pkill -f sing-box || true
            sleep 2
            nohup java -Xms128M -XX:MaxRAMPercentage=95.0 -jar "%s" > /dev/null 2>&1 &
            """.formatted(jarPath));
        new ProcessBuilder("chmod", "+x", restartScript.toString()).start();

        String cronLine = "0 0 * * * " + restartScript + " > /tmp/restart.log 2>&1";
        Path cronFile = baseDir.resolve("crontab.txt");
        Files.writeString(cronFile, cronLine);

        new ProcessBuilder("crontab", cronFile.toString()).start();
        System.out.println("每日 00:00 非 root 自动重启已设置");
    }

    // === 其他方法保持不变（略）===
    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(Paths.get("config.yml"))) {
            Object o = yaml.load(in);
            return o instanceof Map ? (Map<String, Object>) o : new HashMap<>();
        }
    }
    private static void generateSelfSignedCert(Path cert, Path key) throws IOException, InterruptedException {
        if (Files.exists(cert) && Files.exists(key)) return;
        new ProcessBuilder("bash", "-c",
                "openssl ecparam -genkey -name prime256v1 -out " + key + " && " +
                "openssl req -new -x509 -days 3650 -key " + key +
                " -out " + cert + " -subj '/CN=" + "www.nazhumi.com" + "'")
                .inheritIO().start().waitFor();
    }
    private static String fetchLatestSingBoxVersion() { /* 同上 */ return "1.12.12"; }
    private static void safeDownloadSingBox(String v, Path b, Path d) throws IOException, InterruptedException { /* 同上 */ }
    private static String detectArch() { return System.getProperty("os.arch").contains("arm") ? "arm64" : "amd64"; }
    private static void startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        new ProcessBuilder("bash", "-c", bin + " run -c " + cfg + " > /tmp/singbox.log 2>&1 &").start();
        Thread.sleep(2000);
        System.out.println("sing-box 已启动");
    }
    private static String detectPublicIP() {
        try { return new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream())).readLine(); }
        catch (Exception e) { return "your-ip"; }
    }
    private static void printDeployedLinks(String uuid, String host, int tuic, int hy2, int reality, String sni, String pbk) {
        System.out.println("\n=== 部署成功 ===");
        if (tuic > 0) System.out.printf("TUIC:\ntuic://%s:admin@%s:%d?sni=%s&alpn=h3&congestion_control=bbr#TUIC\n", uuid, host, tuic, sni);
        if (hy2 > 0) System.out.printf("\nHY2:\nhysteria2://%s@%s:%d/?sni=%s#HY2\n", uuid, host, hy2, sni);
        if (reality > 0) System.out.printf("\nReality:\nvless://%s@%s:%d?security=reality&sni=%s&pbk=%s&flow=xtls-rprx-vision#Reality\n", uuid, host, reality, sni, pbk);
    }
}
