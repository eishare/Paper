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

            String uuid = parseString(config.get("uuid"));
            String tuicPortStr = parseString(config.get("tuic_port"));
            String hy2PortStr = parseString(config.get("hy2_port"));
            String realityPortStr = parseString(config.get("reality_port"));
            String sni = parseString(config.getOrDefault("sni", "www.nazhumi.com"));

            if (uuid.isEmpty()) throw new RuntimeException("uuid 未设置！");

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
            Path realityKeyFile = baseDir.resolve("reality.key");

            System.out.println("config.yml 加载成功");

            // 1. 先下载 sing-box（关键！）
            String version = fetchLatestSingBoxVersion();
            safeDownloadSingBox(version, bin, baseDir);

            // 2. 生成证书
            generateSelfSignedCert(cert, key);

            // 3. Reality 密钥固定
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
                    privateKey = keys.get("private");
                    publicKey = keys.get("public");
                    Files.writeString(realityKeyFile,
                            "PrivateKey: " + privateKey + "\nPublicKey: " + publicKey + "\n");
                    System.out.println("已生成并保存 Reality 密钥");
                }
            }

            // 4. 生成配置
            generateSingBoxConfig(configJson, uuid, deployTUIC, deployHY2, deployReality,
                    tuicPort, hy2Port, realityPort, sni, cert, key, privateKey);

            // 5. 启动 sing-box
            startSingBox(bin, configJson);

            // 6. 输出链接
            String host = detectPublicIP();
            printDeployedLinks(uuid, host, tuicPort, hy2Port, realityPort, sni, publicKey);

            // 7. 非 root 重启
            scheduleNonRootRestart(baseDir);

        } catch (Exception e) {
            System.err.println("启动失败：");
            e.printStackTrace();
            System.exit(1);
        }
    }

    // === 解析双引号字符串 ===
    private static String parseString(Object obj) {
        if (obj == null) return "";
        String s = obj.toString().trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.trim();
    }

    // === 解析端口（支持 "443" 或 443）===
    private static int parsePort(String port) {
        if (port == null || port.isEmpty()) return 0;
        try {
            return Integer.parseInt(parseString(port));
        } catch (NumberFormatException e) {
            System.out.println("端口格式错误: " + port);
            return 0;
        }
    }

    // === 加载 config.yml ===
    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(Paths.get("config.yml"))) {
            Object o = yaml.load(in);
            return o instanceof Map ? (Map<String, Object>) o : new HashMap<>();
        }
    }

    // === 生成证书 ===
    private static void generateSelfSignedCert(Path cert, Path key) throws IOException, InterruptedException {
        if (Files.exists(cert) && Files.exists(key)) return;
        System.out.println("正在生成自签证书...");
        new ProcessBuilder("bash", "-c",
                "openssl ecparam -genkey -name prime256v1 -out " + key + " && " +
                "openssl req -new -x509 -days 3650 -key " + key +
                " -out " + cert + " -subj '/CN=www.nazhumi.com'")
                .inheritIO().start().waitFor();
        System.out.println("证书生成完成");
    }

    // === 下载 sing-box（关键修复）===
    private static void safeDownloadSingBox(String version, Path bin, Path dir)
            throws IOException, InterruptedException {
        if (Files.exists(bin) && Files.size(bin) > 100000) {
            new ProcessBuilder("chmod", "+x", bin.toString()).start().waitFor();
            System.out.println("sing-box 已存在");
            return;
        }

        String arch = detectArch();
        String file = "sing-box-" + version + "-linux-" + arch + ".tar.gz";
        String url = "https://github.com/SagerNet/sing-box/releases/download/v" + version + "/" + file;
        Path tar = dir.resolve(file);

        System.out.println("正在下载: " + url);
        new ProcessBuilder("bash", "-c", "curl -L -f -o " + tar + " \"" + url + "\"")
                .inheritIO().start().waitFor();

        if (!Files.exists(tar)) throw new IOException("下载失败");

        System.out.println("正在解压...");
        new ProcessBuilder("bash", "-c",
                "cd " + dir + " && tar -xzf " + file + " && " +
                "find . -name 'sing-box' -type f -exec mv {} ./sing-box \\; && " +
                "chmod +x ./sing-box")
                .inheritIO().start().waitFor();

        if (!Files.exists(bin)) throw new IOException("sing-box 解压失败！");
        System.out.println("sing-box 就绪");
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        return a.contains("arm") ? "arm64" : "amd64";
    }

    // === 生成 Reality 密钥 ===
    private static Map<String, String> generateRealityKeypair(Path bin)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(bin.toString(), "generate", "reality-keypair");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0)
            throw new IOException("密钥生成失败: " + out);

        Matcher priv = Pattern.compile("PrivateKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        Matcher pub = Pattern.compile("PublicKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        if (!priv.find() || !pub.find()) throw new IOException("解析密钥失败");

        Map<String, String> map = new HashMap<>();
        map.put("private", priv.group(1));
        map.put("public", pub.group(1));
        return map;
    }

    // === 生成 sing-box 配置 ===
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

    // === 启动 sing-box ===
    private static void startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        new ProcessBuilder("bash", "-c", bin + " run -c " + cfg + " > /tmp/singbox.log 2>&1 &").start();
        Thread.sleep(2000);
        System.out.println("sing-box 已启动");
    }

    // === 获取公网 IP ===
    private static String detectPublicIP() {
        try {
            return new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream())).readLine();
        } catch (Exception e) {
            return "your-ip";
        }
    }

    // === 输出链接 ===
    private static void printDeployedLinks(String uuid, String host, int tuic, int hy2, int reality, String sni, String pbk) {
        System.out.println("\n=== 部署成功 ===");
        if (tuic > 0) System.out.printf("TUIC:\ntuic://%s:admin@%s:%d?sni=%s&alpn=h3&congestion_control=bbr#TUIC\n", uuid, host, tuic, sni);
        if (hy2 > 0) System.out.printf("\nHY2:\nhysteria2://%s@%s:%d/?sni=%s#HY2\n", uuid, host, hy2, sni);
        if (reality > 0) System.out.printf("\nReality:\nvless://%s@%s:%d?security=reality&sni=%s&pbk=%s&flow=xtls-rprx-vision#Reality\n", uuid, host, reality, sni, pbk);
    }

    // === 非 root 每日重启（cron）===
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

    // === 获取最新版本 ===
    private static String fetchLatestSingBoxVersion() {
        try {
            URL url = new URL("https://api.github.com/repos/SagerNet/sing-box/releases/latest");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String json = br.lines().reduce("", String::concat);
                int i = json.indexOf("\"tag_name\":\"v");
                if (i != -1) return json.substring(i + 13, json.indexOf("\"", i + 13));
            }
        } catch (Exception ignored) {}
        return "1.12.12";
    }
}
