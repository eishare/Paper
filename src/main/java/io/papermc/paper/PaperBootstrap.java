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
            Files.setPosixFilePermissions(bin, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE
            ));

            // 生成配置
            generateSingBoxConfig(
                    configJson, bin, uuid, deployReality, deployTUIC, deployHY2,
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
        if (!Files.isExecutable(singBoxBin)) {
            throw new IOException("sing-box 无执行权限！");
        }

        ProcessBuilder pb = new ProcessBuilder(singBoxBin.toString(), "generate", "reality-keypair");
        pb.redirectErrorStream(true);
        Process p = pb.start();

        String output = new String(p.getInputStream().readAllBytes(), "UTF-8").trim();
        int exitCode = p.waitFor();

        if (exitCode != 0 || output.isEmpty()) {
            System.err.println("sing-box 命令执行失败，exitCode=" + exitCode);
            System.err.println("命令输出: " + output);
            throw new RuntimeException("sing-box generate reality-keypair 失败！");
        }

        System.out.println("sing-box 输出:\n" + output);

        Map<String, String> keys = new HashMap<>();
        for (String line : output.lines()) {
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
            Path configFile, Path singBoxBin, String uuid,
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

            inbounds.add("""
              {
                "type": "vless",
                "tag": "vless-in",
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
                    "short_id": ["%s"]
                  }
                }
              }
              """.formatted(realityPort, uuid, sni, sni, privateKey, realityShortId));
        }

        String tuicListenPort = tuicPort.isEmpty() ? realityPort : tuicPort;
        if (tuic && !tuicListenPort.isEmpty()) {
            inbounds.add("""
              {
                "type": "tuic",
                "tag": "tuic-in",
                "listen": "::",
                "listen_port": %s,
                "users": [{"uuid": "%s", "password": "%s"}],
                "congestion_control": "bbr",
                "alpn": ["h3"],
                "udp_relay_mode": "native"
              }
              """.formatted(tuicListenPort, uuid, PASSWORD));
        }

        String hy2ListenPort = hy2Port.isEmpty() ? realityPort : hy2Port;
        if (hy2 && !hy2ListenPort.isEmpty()) {
            inbounds.add("""
              {
                "type": "hysteria2",
                "tag": "hy2-in",
                "listen": "::",
                "listen_port": %s,
                "password": "%s",
                "up_mbps": 100,
                "down_mbps": 100
              }
              """.formatted(hy2ListenPort, PASSWORD));
        }

        String json = """
        {
          "log": {"level": "info"},
          "inbounds": [%s],
          "outbounds": [{"type": "direct"}]
        }
        """.formatted(String.join(", ", inbounds));

        Files.writeString(configFile, json);
        System.out.println("sing-box 配置生成完成");

        // 缓存公钥用于链接输出
        if (reality) {
            Files.writeString(configFile.getParent().resolve("reality_pub"), realityPublicKey);
            Files.writeString(configFile.getParent().resolve("reality_sid"), realityShortId);
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
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String json = br.lines().reduce("", String::concat);
                int i = json.indexOf("\"tag_name\":\"v");
                if (i != -1) {
                    String v = json.substring(i + 13, json.indexOf("\"", i + 13));
                    System.out.println("最新版本: " + v);
                    return v;
                }
            }
        } catch (Exception e) {
            System.out.println("获取版本失败，使用回退版本 " + fallback);
        }
        return fallback;
    }

    private static void safeDownloadSingBox(String version, Path bin, Path dir) throws IOException, InterruptedException {
        if (Files.exists(bin) && Files.isExecutable(bin)) {
            System.out.println("sing-box 已存在且可执行，跳过下载");
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
                        "find . -type f -name 'sing-box' -exec mv {} sing-box \\; && chmod +x sing-box && rm -rf sing-box-*")
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
            String publicKey = Files.readString(baseDir.resolve("reality_pub")).trim();
            String shortId = Files.readString(baseDir.resolve("reality_sid")).trim();
            System.out.printf("VLESS Reality:\nvless://%s@%s:%s?encryption=none&security=reality&sni=%s&fp=chrome&pbk=%s&sid=%s&flow=xtls-rprx-vision&type=tcp#Reality\n",
                    uuid, host, realityPort, sni, publicKey, shortId);
        }

        if (tuic) {
            String auth = uuid + ":" + PASSWORD;
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(auth.getBytes());
            String port = tuicPort.isEmpty() ? realityPort : tuicPort;
            System.out.printf("\nTUIC:\ntuic://%s@%s:%s?alpn=h3&congestion_control=bbr#TUIC\n",
                    encoded, host, port);
        }

        if (hy2) {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(PASSWORD.getBytes());
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
