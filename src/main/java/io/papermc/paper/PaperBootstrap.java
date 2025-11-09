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
            Path realityKeyFile = baseDir.resolve("key.txt");

            System.out.println("config.yml 加载成功");

            // 1. 下载 sing-box
            String version = fetchLatestSingBoxVersion();
            safeDownloadSingBox(version, bin, baseDir);

            // 2. 生成证书（与 Bash 一致）
            generateFixedCert(cert, key);

            // 3. Reality 密钥（与 Bash 一致）
            String privateKey = "", publicKey = "";
            if (deployReality) {
                if (Files.exists(realityKeyFile)) {
                    List<String> lines = Files.readAllLines(realityKeyFile);
                    for (String line : lines) {
                        if (line.contains("PrivateKey:")) privateKey = line.split(":")[1].trim();
                        if (line.contains("PublicKey:")) publicKey = line.split(":")[1].trim();
                    }
                } else {
                    Map<String, String> keys = generateRealityKeypair(bin);
                    privateKey = keys.get("private");
                    publicKey = keys.get("public");
                    Files.writeString(realityKeyFile, "PrivateKey: " + privateKey + "\nPublicKey: " + publicKey + "\n");
                }
            }

            // 4. 生成配置（完全对标 Bash）
            generateSingBoxConfig(configJson, uuid, deployTUIC, deployHY2, deployReality,
                    tuicPort, hy2Port, realityPort, cert, key, privateKey);

            // 5. 启动 sing-box
            startSingBox(bin, configJson);

            // 6. 输出链接（与 Bash 一致）
            String host = detectPublicIP();
            printDeployedLinks(uuid, host, tuicPort, hy2Port, realityPort, publicKey);

            // 7. 定时重启
            scheduleJavaRestart();

            // 8. 阻塞主线程（关键！）
            System.out.println("按 Ctrl+C 退出，节点将继续运行");
            Thread.sleep(Long.MAX_VALUE);

        } catch (Exception e) {
            System.err.println("启动失败：");
            e.printStackTrace();
            System.exit(1);
        }
    }

    // === 解析双引号 ===
    private static String parseString(Object obj) {
        if (obj == null) return "";
        String s = obj.toString().trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) {
            s = s.substring(1, s.length() - 1);
        }
        return s.trim();
    }

    private static int parsePort(String port) {
        if (port == null || port.isEmpty()) return 0;
        try {
            return Integer.parseInt(parseString(port));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(Paths.get("config.yml"))) {
            Object o = yaml.load(in);
            return o instanceof Map ? (Map<String, Object>) o : new HashMap<>();
        }
    }

    // === 固定证书（与 Bash 一致）===
    private static void generateFixedCert(Path cert, Path key) throws IOException {
        if (Files.exists(cert) && Files.exists(key)) return;

        String privKey = """
            -----BEGIN EC PARAMETERS-----
            BgqghkjOPQQBw==
            -----END EC PARAMETERS-----
            -----BEGIN EC PRIVATE KEY-----
            MHcCAQEEIM4792SEtPqIt1ywqTd/0bYidBqpYV/+siNnfBYsdUYsAoGCCqGSM49
            AwEHoUQDQgAE1kHafPj07rJG+HboH2ekAI4r+e6TL38GWASAnngZreoQDF16ARa
            /TsyLyFoPkhTxSbehH/OBEjHtSZGaDhMqQ==
            -----END EC PRIVATE KEY-----
            """;
        String certPem = """
            -----BEGIN CERTIFICATE-----
            MIIBejCCASGgAwIBAgIUFWeQL3556PNJLp/veCFxGNj9crkwCgYIKoZIzj0EAwIw
            EzERMA8GA1UEAwwIYmluZy5jb20wHhcNMjUwMTAxMDEwMTAwWhcNMzUwMTAxMDEw
            MTAwWjATMREwDwYDVQQDDAhiaW5nLmNvbTBNBgqgGzM9AgEGCCqGSM49AwEHA0IA
            BNZB2nz49O6yRvh26B9npACOK/nuky9/BlgEgDZ54Ga3qEAxdeWv07Mi8h
            d5IR8Um3oR/zQRIx7UmRmg4TKmjUzBRMB0GA1UdDgQWBQTV1cFID7UISE7PLTBR
            BfGbgrkMNzAfBgNVHSMEGDAWgBTV1cFID7UISE7PLTBRBfGbgrkMNzAPBgNVHRMB
            Af8EBTADAQH/MAoGCCqGSM49BAMCA0cAMEQCIARDAJvg0vd/ytrQVvEcSm6XTlB+
            eQ6OFb9LbLYL9Zi+AiffoMbi4y/0YUQlTtz7as9S8/lciBF5VCUoVIKS+vX2g==
            -----END CERTIFICATE-----
            """;

        Files.writeString(key, privKey);
        Files.writeString(cert, certPem);
        System.out.println("固定证书已写入");
    }

    private static void safeDownloadSingBox(String version, Path bin, Path dir)
            throws IOException, InterruptedException {
        if (Files.exists(bin) && Files.size(bin) > 100000) {
            new ProcessBuilder("chmod", "+x", bin.toString()).start().waitFor();
            return;
        }
        String arch = detectArch();
        String file = "sing-box-" + version + "-linux-" + arch + ".tar.gz";
        String url = "https://github.com/SagerNet/sing-box/releases/download/v" + version + "/" + file;
        Path tar = dir.resolve(file);
        new ProcessBuilder("bash", "-c", "curl -L -f -o " + tar + " \"" + url + "\"")
                .inheritIO().start().waitFor();
        new ProcessBuilder("bash", "-c",
                "cd " + dir + " && tar -xzf " + file + " && " +
                "find . -name 'sing-box' -type f -exec mv {} ./sing-box \\; && " +
                "chmod +x ./sing-box")
                .inheritIO().start().waitFor();
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        return a.contains("arm") ? "arm64" : "amd64";
    }

    private static Map<String, String> generateRealityKeypair(Path bin)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(bin.toString(), "generate", "reality-keypair");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(10, TimeUnit.SECONDS);
        Matcher priv = Pattern.compile("PrivateKey:\\s*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        Matcher pub = Pattern.compile("PublicKey:\\s*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        if (!priv.find() || !pub.find()) throw new IOException("密钥生成失败");
        Map<String, String> map = new HashMap<>();
        map.put("private", priv.group(1));
        map.put("public", pub.group(1));
        return map;
    }

    // === 生成配置（完全对标 Bash）===
    private static void generateSingBoxConfig(Path file, String uuid,
                                              boolean tuic, boolean hy2, boolean reality,
                                              int tuicPort, int hy2Port, int realityPort,
                                              Path cert, Path key, String privateKey) throws IOException {
        List<String> inbounds = new ArrayList<>();

        if (tuic) inbounds.add(String.format("""
            {
              "type": "tuic",
              "listen": "::",
              "listen_port": %d,
              "users": [{"uuid": "%s", "password": "admin"}],
              "congestion_control": "bbr",
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
              "masquerade": "https://bing.com",
              "tls": {
                "enabled": true,
                "alpn": ["h3"],
                "certificate_path": "%s",
                "key_path": "%s"
              }
            }""", hy2Port, uuid, cert, key));

        if (reality) inbounds.add(String.format("""
            {
              "type": "vless",
              "listen": "::",
              "listen_port": %d,
              "users": [{"uuid": "%s", "flow": "xtls-rprx-vision"}],
              "tls": {
                "enabled": true,
                "server_name": "www.nazhumi.com",
                "reality": {
                  "enabled": true,
                  "handshake": {"server": "www.nazhumi.com", "server_port": 443},
                  "private_key": "%s",
                  "short_id": [""]
                }
              }
            }""", realityPort, uuid, privateKey));

        String json = """
            {"log": {"disabled": true}, "inbounds": [%s], "outbounds": [{"type": "direct"}]}"""
            .formatted(String.join(",", inbounds));
        Files.writeString(file, json);
        System.out.println("sing-box 配置生成完成");
    }

    private static void startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        new ProcessBuilder("bash", "-c", bin + " run -c " + cfg + " > /tmp/singbox.log 2>&1 &").start();
        Thread.sleep(2000);
        System.out.println("sing-box 已启动");
    }

    private static String detectPublicIP() {
        try {
            return new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream())).readLine();
        } catch (Exception e) {
            return "IP_ERROR";
        }
    }

    // === 输出链接（与 Bash 一致）===
    private static void printDeployedLinks(String uuid, String host, int tuic, int hy2, int reality, String pbk) {
        System.out.println("\n=== 部署成功 ===");
        if (tuic > 0) System.out.printf("tuic://%s:admin@%s:%d?sni=www.bing.com&alpn=h3&congestion_control=bbr&allowInsecure=1#TUIC\n", uuid, host, tuic);
        if (hy2 > 0) System.out.printf("hysteria2://%s@%s:%d/?sni=www.bing.com&insecure=1#Hysteria2\n", uuid, host, hy2);
        if (reality > 0) System.out.printf("vless://%s@%s:%d?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.nazhumi.com&fp=firefox&pbk=%s&type=tcp#Reality\n", uuid, host, reality, pbk);
    }

    private static void scheduleJavaRestart() {
        System.out.println("设置每日 00:00 重启...");
        ScheduledExecutorService s = Executors.newScheduledThreadPool(1);
        Runnable r = () -> {
            try {
                new ProcessBuilder("bash", "-c", "pkill -f sing-box || true").start().waitFor();
                Thread.sleep(2000);
                String jar = System.getProperty("user.dir") + "/server.jar";
                new ProcessBuilder("bash", "-c", "nohup java -Xms128M -XX:MaxRAMPercentage=95.0 -jar \"" + jar + "\" > /dev/null 2>&1 &").start();
                System.exit(0);
            } catch (Exception ignored) {}
        };
        ZoneId z = ZoneId.of("Asia/Shanghai");
        LocalDateTime n = LocalDateTime.now(z).withHour(0).withMinute(0).withSecond(0);
        if (!n.isAfter(LocalDateTime.now(z))) n = n.plusDays(1);
        long d = Duration.between(LocalDateTime.now(z), n).getSeconds();
        s.scheduleAtFixedRate(r, d, 86400, TimeUnit.SECONDS);
    }

    private static String fetchLatestSingBoxVersion() {
        try {
            URL u = new URL("https://api.github.com/repos/SagerNet/sing-box/releases/latest");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String j = br.lines().reduce("", String::concat);
                int i = j.indexOf("\"tag_name\":\"v");
                if (i != -1) return j.substring(i + 13, j.indexOf("\"", i + 13));
            }
        } catch (Exception ignored) {}
        return "1.12.12";
    }
}
