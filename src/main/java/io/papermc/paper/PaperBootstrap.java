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

            // 确保可执行权限
            new ProcessBuilder("bash", "-c", "chmod +x " + bin.toString())
                    .inheritIO().start().waitFor();

            generateSingBoxConfig(
                    configJson, bin, baseDir, uuid, deployReality, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni
            );

            startSingBox(bin, configJson);

            String host = detectPublicIP();

            printDeployedLinks(
                    uuid, deployReality, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, host, baseDir
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

    private static Map<String, String> generateRealityKeys(Path singBoxBin) throws IOException, InterruptedException {
        System.out.println("生成 Reality 密钥对...");

        ProcessBuilder pb = new ProcessBuilder(singBoxBin.toString(), "generate", "reality-keypair");
        pb.redirectErrorStream(true);
        Process p = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = p.waitFor();
        String outputStr = output.toString().trim();

        if (exitCode != 0 || outputStr.isEmpty()) {
            System.err.println("sing-box 命令失败，exitCode=" + exitCode);
            System.err.println("输出: " + outputStr);
            throw new RuntimeException("sing-box generate reality-keypair 失败！");
        }

        System.out.println("sing-box 输出:\n" + outputStr);

        Map<String, String> keys = new HashMap<>();
        for (String line : outputStr.split("\n")) {
            if (line.startsWith("PrivateKey:")) {
                keys.put("private", line.substring(11).trim());
            } else if (line.startsWith("PublicKey:")) {
                keys.put("public", line.substring(10).trim());
            } else if (line.startsWith("ShortId:")) {
                keys.put("short_id", line.substring(8).trim());
            }
        }

        if (keys.size() != 3) {
            System.err.println("密钥解析失败，keys=" + keys);
            throw new RuntimeException("Reality 密钥生成失败！解析到 " + keys.size() + " 个字段");
        }

        System.out.println("Reality 密钥生成成功");
        return keys;
    }

    private static void generateSingBoxConfig(
            Path configFile, Path singBoxBin, Path baseDir, String uuid,
            boolean reality, boolean tuic, boolean hy2,
            String tuicPort, String hy2Port, String realityPort, String sni
    ) throws IOException, InterruptedException {

        List<String> inbounds = new ArrayList<>();

        String realityPublicKey = "", realityShortId = "";
        if (reality) {
            Map<String, String> keys = generateRealityKeys(singBoxBin);
            String privateKey = keys.get("private");
            realityPublicKey = keys.get("public");
            realityShortId = keys.get("short_id");

            inbounds.add(String.format(
              "{\n" +
              "  \"type\": \"vless\",\n" +
              "  \"tag\": \"vless-in\",\n" +
              "  \"listen\": \"::\",\n" +
              "  \"listen_port\": %s,\n" +
              "  \"users\": [{\"uuid\": \"%s\", \"flow\": \"xtls-rprx-vision\"}],\n" +
              "  \"tls\": {\n" +
              "    \"enabled\": true,\n" +
              "    \"server_name\": \"%s\",\n" +
              "    \"reality\": {\n" +
              "      \"enabled\": true,\n" +
              "      \"handshake\": {\"server\": \"%s\", \"server_port\": 443},\n" +
              "      \"private_key\": \"%s\",\n" +
              "      \"short_id\": [\"%s\"]\n" +
              "    }\n" +
              "  }\n" +
              "}",
              realityPort, uuid, sni, sni, privateKey, realityShortId
            ));
        }

        String tuicListenPort = tuicPort.isEmpty() ? realityPort : tuicPort;
        if (tuic && !tuicListenPort.isEmpty()) {
            inbounds.add(String.format(
              "{\n" +
              "  \"type\": \"tuic\",\n" +
              "  \"tag\": \"tuic-in\",\n" +
              "  \"listen\": \"::\",\n" +
              "  \"listen_port\": %s,\n" +
              "  \"users\": [{\"uuid\": \"%s\", \"password\": \"%s\"}],\n" +
              "  \"congestion_control\": \"bbr\",\n" +
              "  \"alpn\": [\"h3\"],\n" +
              "  \"udp_relay_mode\": \"native\"\n" +
              "}",
              tuicListenPort, uuid, PASSWORD
            ));
        }

        String hy2ListenPort = hy2Port.isEmpty() ? realityPort : hy2Port;
        if (hy2 && !hy2ListenPort.isEmpty()) {
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
              hy2ListenPort, PASSWORD
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

        // 缓存 Reality 公钥和 short_id
        if (reality) {
            Files.write(baseDir.resolve("reality_pub"), realityPublicKey.getBytes("UTF-8"));
            Files.write(baseDir.resolve("reality_sid"), realityShortId.getBytes("UTF-8"));
        }
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
            String sni, String host, Path baseDir
    ) throws IOException {
        System.out.println("\n=== 已部署节点链接 ===");

        if (reality) {
            String publicKey = new String(Files.readAllBytes(baseDir.resolve("reality_pub")), "UTF-8").trim();
            String shortId = new String(Files.readAllBytes(baseDir.resolve("reality_sid")), "UTF-8").trim();
            System.out.printf("VLESS Reality:\nvless://%s@%s:%s?encryption=none&security=reality&sni=%s&fp=chrome&pbk=%s&sid=%s&flow=xtls-rprx-vision&type=tcp#Reality\n",
                    uuid, host, realityPort, sni, publicKey, shortId);
        }

        if (tuic) {
            String auth = uuid + ":" + PASSWORD;
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(auth.getBytes("UTF-8"));
            String port = tuicPort.isEmpty() ? realityPort : tuicPort;
            System.out.printf("\nTUIC:\ntuic://%s@%s:%s?alpn=h3&congestion_control=bbr#TUIC\n",
                    encoded, host, port);
        }

        if (hy2) {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(PASSWORD.getBytes("UTF-8"));
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
