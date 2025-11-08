package io.papermc.paper;

import joptsimple.OptionSet;
import net.minecraft.SharedConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaperBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger("bootstrap");
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET = "\033[0m";

    private static Process singBoxProcess;
    private static ScheduledExecutorService restartScheduler;
    private static Map<String, Object> config;

    private PaperBootstrap() {}

    // ==================== 主启动入口 ====================
    public static void boot(final OptionSet options) {
        try {
            loadConfig();
            downloadSingBox();
            generateSingBoxConfig();
            startSingBox();
            scheduleDailyRestart();

            Runtime.getRuntime().addShutdownHook(new Thread(PaperBootstrap::stopSingBox));
            System.out.println(ANSI_GREEN + "✅ Sing-box 启动完成！TUIC + HY2 + VLESS-Reality 就绪" + ANSI_RESET);

            SharedConstants.tryDetectVersion();
            getStartupVersionMessages().forEach(LOGGER::info);
            net.minecraft.server.Main.main(options);

        } catch (Exception e) {
            System.err.println(ANSI_RED + "❌ 启动失败: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
            stopSingBox();
            System.exit(1);
        }
    }

    // ==================== 载入配置 ====================
    private static void loadConfig() throws IOException {
        Path configPath = Paths.get("config.yml");
        if (!Files.exists(configPath)) {
            throw new FileNotFoundException("config.yml 不存在，请上传到根目录！");
        }
        try (InputStream in = Files.newInputStream(configPath)) {
            Yaml yaml = new Yaml();
            config = yaml.load(in);
        }
        System.out.println(ANSI_GREEN + "config.yml 加载成功" + ANSI_RESET);
    }

    // ==================== 下载 sing-box ====================
    private static void downloadSingBox() throws IOException, InterruptedException {
        Path binDir = Paths.get(".singbox");
        Path binPath = binDir.resolve("sing-box");
        if (Files.exists(binPath)) return; // 已存在则跳过

        String osArch = System.getProperty("os.arch").toLowerCase();
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) throw new UnsupportedOperationException("暂不支持 Windows 环境运行 sing-box");

        String url;
        if (osArch.contains("amd64") || osArch.contains("x86_64"))
            url = "https://github.com/SagerNet/sing-box/releases/download/v1.8.13/sing-box-1.8.13-linux-amd64.tar.gz";
        else if (osArch.contains("aarch64") || osArch.contains("arm64"))
            url = "https://github.com/SagerNet/sing-box/releases/download/v1.8.13/sing-box-1.8.13-linux-arm64.tar.gz";
        else throw new RuntimeException("不支持的架构: " + osArch);

        Files.createDirectories(binDir);
        Path tarPath = binDir.resolve("sing-box.tar.gz");
        System.out.println(ANSI_YELLOW + "正在下载 sing-box ..." + ANSI_RESET);

        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, tarPath, StandardCopyOption.REPLACE_EXISTING);
        }

        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", tarPath.toString(), "-C", binDir.toString());
        pb.inheritIO().start().waitFor();

        try (Stream<Path> stream = Files.list(binDir)) {
            Optional<Path> extracted = stream.filter(p -> Files.isDirectory(p)
                    && p.getFileName().toString().startsWith("sing-box-")).findFirst();
            if (extracted.isEmpty()) throw new IOException("解压失败，未找到 sing-box 可执行文件！");
            Path extractedDir = extracted.get();
            Files.move(extractedDir.resolve("sing-box"), binPath, StandardCopyOption.REPLACE_EXISTING);
            // 删除其余文件
            try (Stream<Path> cleanup = Files.walk(binDir)) {
                cleanup.filter(p -> !p.equals(binPath))
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }

        binPath.toFile().setExecutable(true);
        System.out.println(ANSI_GREEN + "sing-box 下载并安装完成" + ANSI_RESET);
    }

    // ==================== 生成配置 ====================
    private static void generateSingBoxConfig() throws IOException, InterruptedException {
        String uuid = String.valueOf(config.getOrDefault("uuid", ""));
        String tuicPort = String.valueOf(config.getOrDefault("tuic_port", ""));
        String hy2Port = String.valueOf(config.getOrDefault("hy2_port", ""));
        String realityPort = String.valueOf(config.getOrDefault("reality_port", ""));
        String sni = String.valueOf(config.getOrDefault("sni", "www.bing.com"));
        String certCN = String.valueOf(config.getOrDefault("cert_cn", "bing.com"));
        boolean sharePort = Boolean.parseBoolean(String.valueOf(config.getOrDefault("share_port", "false")));

        Path sbDir = Paths.get(".singbox");
        Files.createDirectories(sbDir);

        // 检查 openssl 是否存在
        if (new ProcessBuilder("which", "openssl").start().waitFor() != 0)
            throw new IOException("系统未安装 openssl，请先安装！");

        // Reality 密钥生成
        Path keyFile = sbDir.resolve("reality_key.txt");
        String privateKey = "";
        if (!Files.exists(keyFile)) {
            ProcessBuilder pb = new ProcessBuilder("./.singbox/sing-box", "generate", "reality-keypair");
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            Files.writeString(keyFile, output);

            Matcher m = Pattern.compile("(?i)Private.?key:\\s*([A-Za-z0-9+/=]+)").matcher(output);
            if (m.find()) privateKey = m.group(1);
            else throw new IOException("未能解析 Reality 私钥输出");
        } else {
            List<String> lines = Files.readAllLines(keyFile);
            for (String line : lines)
                if (line.toLowerCase().contains("private")) {
                    privateKey = line.split(": ")[1].trim();
                    break;
                }
        }

        // 生成证书
        Path cert = sbDir.resolve("cert.pem");
        Path key = sbDir.resolve("private.key");
        if (!Files.exists(cert) || !Files.exists(key)) {
            System.out.println(ANSI_YELLOW + "生成自签证书中..." + ANSI_RESET);
            new ProcessBuilder("openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt",
                    "ec_paramgen_curve:prime256v1", "-keyout", key.toString(),
                    "-out", cert.toString(), "-subj", "/CN=" + certCN,
                    "-days", "3650", "-nodes").inheritIO().start().waitFor();
        }

        String configJson;
        if (sharePort) {
            // 单端口多协议模式 (需要 sing-box ≥ 1.10)
            configJson = String.format("""
                {
                  "log": {"level": "warn"},
                  "inbounds": [
                    {
                      "type": "mixed",
                      "tag": "multi-in",
                      "listen": "::",
                      "listen_port": %s,
                      "sniff": true,
                      "detectors": [
                        {
                          "protocol": "vless",
                          "users": [{"uuid": "%s", "flow": "xtls-rprx-vision"}],
                          "tls": {
                            "enabled": true,
                            "server_name": "%s",
                            "reality": {
                              "enabled": true,
                              "private_key": "%s",
                              "short_id": ["01234567"],
                              "handshake": {"server": "%s", "server_port": 443}
                            }
                          }
                        },
                        {
                          "protocol": "tuic",
                          "users": [{"uuid": "%s", "password": "admin"}],
                          "tls": {
                            "enabled": true,
                            "alpn": ["h3"],
                            "certificate_path": ".singbox/cert.pem",
                            "key_path": ".singbox/private.key"
                          }
                        }
                      ]
                    }
                  ],
                  "outbounds": [{"type": "direct"}]
                }
            """, realityPort, uuid, sni, privateKey, sni, uuid);
        } else {
            // 多端口独立模式
            StringBuilder inbounds = new StringBuilder();
            if (!tuicPort.isEmpty() && !"0".equals(tuicPort)) {
                inbounds.append(String.format("""
                    {
                      "type": "tuic",
                      "tag": "tuic-in",
                      "listen": "::",
                      "listen_port": %s,
                      "users": [{"uuid": "%s", "password": "admin"}],
                      "congestion_control": "bbr",
                      "tls": {
                        "enabled": true,
                        "alpn": ["h3"],
                        "certificate_path": ".singbox/cert.pem",
                        "key_path": ".singbox/private.key"
                      }
                    },
                """, tuicPort, uuid));
            }
            if (!hy2Port.isEmpty() && !"0".equals(hy2Port)) {
                inbounds.append(String.format("""
                    {
                      "type": "hysteria2",
                      "tag": "hy2-in",
                      "listen": "::",
                      "listen_port": %s,
                      "users": [{"password": "%s"}],
                      "tls": {
                        "enabled": true,
                        "alpn": ["h3"],
                        "certificate_path": ".singbox/cert.pem",
                        "key_path": ".singbox/private.key"
                      }
                    },
                """, hy2Port, uuid));
            }
            if (!realityPort.isEmpty() && !"0".equals(realityPort)) {
                inbounds.append(String.format("""
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
                          "short_id": ["01234567"]
                        }
                      }
                    }
                """, realityPort, uuid, sni, sni, privateKey));
            }

            String inboundJson = inbounds.toString().replaceAll(",\\s*$", ""); // 去掉尾逗号
            configJson = String.format("""
                {
                  "log": {"level": "warn"},
                  "inbounds": [%s],
                  "outbounds": [{"type": "direct"}]
                }
            """, inboundJson);
        }

        Files.writeString(sbDir.resolve("config.json"), configJson);
        System.out.println(ANSI_GREEN + "sing-box 配置生成完成" + ANSI_RESET);
    }

    // ==================== 启动 sing-box ====================
    private static void startSingBox() throws IOException {
        ProcessBuilder pb = new ProcessBuilder("./.singbox/sing-box", "run", "-c", ".singbox/config.json");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        singBoxProcess = pb.start();
        System.out.println(ANSI_GREEN + "sing-box 已启动" + ANSI_RESET);
    }

    // ==================== 停止 sing-box ====================
    private static void stopSingBox() {
        if (singBoxProcess != null && singBoxProcess.isAlive()) {
            singBoxProcess.destroy();
            try { singBoxProcess.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            System.out.println(ANSI_RED + "sing-box 已停止" + ANSI_RESET);
        }
        if (restartScheduler != null) restartScheduler.shutdownNow();
    }

    // ==================== 每日重启 ====================
    private static void scheduleDailyRestart() {
        restartScheduler = Executors.newSingleThreadScheduledExecutor();
        Runnable task = () -> {
            System.out.println(ANSI_YELLOW + "\n[定时重启] 北京时间 00:00，执行重启！" + ANSI_RESET);
            stopSingBox();
            try {
                Thread.sleep(3000);
                generateSingBoxConfig();
                startSingBox();
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        ZonedDateTime next = now.toLocalDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai"));
        long delay = Duration.between(now, next).getSeconds();
        if (delay < 0) delay += 24 * 3600;

        System.out.printf(ANSI_YELLOW + "[定时重启] 下次重启在 %s (%d 小时 %d 分钟后)%n" + ANSI_RESET,
                next.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                delay / 3600, (delay % 3600) / 60);

        restartScheduler.scheduleAtFixedRate(task, delay, 24 * 3600, TimeUnit.SECONDS);
    }

    private static List<String> getStartupVersionMessages() {
        return List.of(
                "Java: " + System.getProperty("java.version") + " on " + System.getProperty("os.name"),
                "Loading Paper for Minecraft..."
        );
    }
}
