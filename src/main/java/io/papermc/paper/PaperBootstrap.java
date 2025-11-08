package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

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

            System.out.println("✅ config.yml 加载成功");
            Files.createDirectories(Paths.get(".singbox"));

            generateSelfSignedCert();
            generateSingBoxConfig(uuid, deployVLESS, deployTUIC, deployHY2, tuicPort, hy2Port, realityPort, sni);

            String rawVersion = fetchLatestSingBoxVersion(); // 可能为 "v1.12.12"
            String version = rawVersion.startsWith("v") ? rawVersion.substring(1) : rawVersion; // 去掉前导 v
            safeDownloadSingBox(version);

            startSingBox();

            if (!checkSingBoxRunning()) {
                throw new IOException("❌ sing-box 启动失败，请检查文件权限或配置错误！");
            }

            String host = detectPublicIP();
            printDeployedLinks(uuid, deployVLESS, deployTUIC, deployHY2, tuicPort, hy2Port, realityPort, sni, host);
            scheduleDailyRestart();

        } catch (Exception e) {
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

    // ---------- 生成自签证书 ----------
    private static void generateSelfSignedCert() throws IOException, InterruptedException {
        Path certDir = Paths.get(".singbox");
        Path cert = certDir.resolve("cert.pem");
        Path key = certDir.resolve("key.pem");

        if (Files.exists(cert) && Files.exists(key)) {
            System.out.println("🔑 证书已存在，跳过生成");
            return;
        }

        System.out.println("🔨 正在生成自签证书 (OpenSSL)...");
        new ProcessBuilder("bash", "-c",
                "openssl req -x509 -newkey rsa:2048 -keyout .singbox/key.pem -out .singbox/cert.pem -days 365 -nodes -subj '/CN=bing.com'")
                .inheritIO().start().waitFor();
        System.out.println("✅ 已生成自签证书 (OpenSSL)");
    }

    // ---------- 生成 sing-box 配置 ----------
    private static void generateSingBoxConfig(String uuid, boolean vless, boolean tuic, boolean hy2,
                                              String tuicPort, String hy2Port, String realityPort, String sni) throws IOException {

        List<String> inbounds = new ArrayList<>();

        if (vless) {
            inbounds.add("""
              {
                "type": "vless",
                "listen": "::",
                "listen_port": %s,
                "users": [{"uuid": "%s"}],
                "tls": {
                  "enabled": true,
                  "server_name": "%s",
                  "reality": {
                    "enabled": true,
                    "handshake": {"server": "%s", "server_port": 443},
                    "private_key": "",
                    "short_id": ""
                  }
                }
              }
            """.formatted(realityPort, uuid, sni, sni));
        }

        if (tuic) {
            inbounds.add("""
              {
                "type": "tuic",
                "listen": "::",
                "listen_port": %s,
                "uuid": "%s",
                "password": "%s"
              }
            """.formatted(tuicPort, uuid, uuid));
        }

        if (hy2) {
            inbounds.add("""
              {
                "type": "hysteria2",
                "listen": "::",
                "listen_port": %s,
                "password": "%s"
              }
            """.formatted(hy2Port, uuid));
        }

        String json = """
        {
          "log": { "level": "info" },
          "inbounds": [%s],
          "outbounds": [{"type": "direct"}]
        }
        """.formatted(String.join(",", inbounds));

        Files.writeString(Paths.get(".singbox/config.json"), json);
        System.out.println("✅ sing-box 配置生成完成");
    }

    // ---------- 获取最新版本 (tag_name) ----------
    private static String fetchLatestSingBoxVersion() {
        String fallback = "v1.12.12";
        try {
            URL url = new URL("https://api.github.com/repos/SagerNet/sing-box/releases/latest");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String json = reader.lines().reduce("", (a, b) -> a + b);
                int tagIndex = json.indexOf("\"tag_name\":\"");
                if (tagIndex != -1) {
                    String tag = json.substring(tagIndex + 12, json.indexOf("\"", tagIndex + 12));
                    System.out.println("🔍 检测到最新 sing-box 版本: " + tag);
                    return tag;
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ 无法访问 GitHub API，使用回退版本 " + fallback);
        }
        return fallback;
    }

    // ---------- 下载并解压 sing-box (.tar.gz)，注意 filename 使用无 v 的版本号 ----------
    private static void safeDownloadSingBox(String versionNoV) throws IOException, InterruptedException {
        Path bin = Paths.get("sing-box");
        if (Files.exists(bin) && Files.size(bin) > 5_000_000) {
            System.out.println("🟢 sing-box 已存在且正常，跳过下载");
            return;
        }

        String arch = detectArch(); // "amd64" or "arm64"
        // versionNoV expected to be like "1.12.12" (we already stripped leading v in caller)
        String filename = "sing-box-" + versionNoV + "-linux-" + arch + ".tar.gz";

        String[] urls = {
            "https://github.com/SagerNet/sing-box/releases/download/v" + versionNoV + "/" + filename,
            "https://mirror.ghproxy.com/https://github.com/SagerNet/sing-box/releases/download/v" + versionNoV + "/" + filename
        };

        boolean success = false;
        for (String url : urls) {
            System.out.println("⬇️ 尝试下载 sing-box 压缩包: " + url);
            Files.deleteIfExists(Paths.get(filename));
            Files.deleteIfExists(bin);

            // 下载
            ProcessBuilder dlPb = new ProcessBuilder("bash", "-c",
                "curl -L --retry 3 -o \"" + filename + "\" \"" + url + "\"");
            dlPb.inheritIO();
            dlPb.start().waitFor();

            // 验证文件存在且大小合理，然后解压并移动可执行文件出来
            Path tar = Paths.get(filename);
            if (Files.exists(tar) && Files.size(tar) > 1_000_000) {
                // 解压到临时目录并寻找 sing-box 可执行
                ProcessBuilder tarPb = new ProcessBuilder("bash", "-c",
                    "tar -xzf \"" + filename + "\" && " +
                    "shopt -s nullglob; for d in sing-box-*; do if [ -f \"$d/sing-box\" ]; then mv \"$d/sing-box\" ./sing-box; fi; done");
                tarPb.inheritIO();
                tarPb.start().waitFor();

                if (Files.exists(bin) && Files.size(bin) > 5_000_000 && isELFFile(bin)) {
                    // chmod +x 确保权限
                    Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
                    success = true;
                    System.out.println("✅ 成功下载并解压 sing-box 可执行文件");
                    break;
                } else {
                    System.out.println("⚠️ 解压后未找到 sing-box 可执行或文件不合法，继续尝试下一个源...");
                }
            } else {
                System.out.println("⚠️ 下载文件缺失或太小，继续尝试下一个源...");
            }
        }

        if (!success) {
            throw new IOException("❌ sing-box 下载失败或文件损坏！请检查网络或手动上传 sing-box 可执行到容器。");
        }
    }

    private static String detectArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("aarch") || arch.contains("arm")) return "arm64";
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

    // ---------- 启动与检测 ----------
    private static void startSingBox() throws IOException, InterruptedException {
        new ProcessBuilder("bash", "-c", "./sing-box run -c .singbox/config.json > singbox.log 2>&1 &")
                .inheritIO().start();
        Thread.sleep(2000);
        System.out.println("🚀 sing-box 已启动");
    }

    private static boolean checkSingBoxRunning() {
        try {
            Process proc = new ProcessBuilder("bash", "-c", "pgrep -f sing-box").start();
            proc.waitFor();
            return proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 输出节点 ----------
    private static String detectPublicIP() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream()))) {
            return br.readLine();
        } catch (Exception e) {
            return "your-server-ip";
        }
    }

    private static void printDeployedLinks(String uuid, boolean vless, boolean tuic, boolean hy2,
                                           String tuicPort, String hy2Port, String realityPort,
                                           String sni, String host) {
        System.out.println("\n=== ✅ 已部署节点链接 ===");
        if (vless)
            System.out.printf("VLESS Reality:\nvless://%s@%s:%s?encryption=none&security=reality&sni=%s#Reality\n",
                    uuid, host, realityPort, sni);
        if (tuic)
            System.out.printf("\nTUIC:\ntuic://%s@%s:%s?alpn=h3#TUIC\n", uuid, host, tuicPort);
        if (hy2)
            System.out.printf("\nHysteria2:\nhy2://%s@%s:%s?insecure=1#Hysteria2\n", uuid, host, hy2Port);
    }

    // ---------- 每日北京时间 00:00 自动重启 ----------
    private static void scheduleDailyRestart() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        Runnable restartTask = () -> {
            System.out.println("[定时重启] 正在执行每日重启任务...");
            try { Runtime.getRuntime().exec("reboot"); }
            catch (IOException e) { e.printStackTrace(); }
        };
        long delay = computeSecondsUntilMidnightBeijing();
        scheduler.scheduleAtFixedRate(restartTask, delay, 86400, TimeUnit.SECONDS);
        System.out.println("[定时重启] 已计划每日北京时间 00:00 自动重启");
    }

    private static long computeSecondsUntilMidnightBeijing() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        LocalDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return Duration.between(now, midnight).toSeconds();
    }
}
