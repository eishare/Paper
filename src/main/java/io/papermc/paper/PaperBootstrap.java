package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * PaperBootstrap - 混合模式启动器
 *
 * - 优先尝试 sing-box（自动下载/解压/启动/检测监听）
 * - 若 sing-box 无法使用，回退到 Java 内嵌轻量监听（功能有限）
 *
 * 注意：Reality / TUIC 的完整协议实现复杂，实际生产环境应优先使用 sing-box/xray 等成熟内核。
 */
public final class PaperBootstrap {
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET = "\033[0m";

    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Path SINGBOX_DIR = Paths.get(".singbox");
    private static final Path SINGBOX_EXEC = Paths.get("sing-box");
    private static final Path SINGBOX_LOG = Paths.get("singbox.log");
    private static final Path REALITY_KEY_FILE = SINGBOX_DIR.resolve("reality_key.txt");

    private static volatile Process singBoxProcess;
    private static ScheduledExecutorService restartScheduler;
    private static ExecutorService fallbackExecutor = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        try {
            println("[启动] " + DATE_TIME_FMT.format(LocalDateTime.now()));
            System.out.println("config.yml 加载中...");
            Map<String, Object> cfg = loadConfig(Paths.get("config.yml"));

            String uuid = opt(cfg.get("uuid"));
            String tuicPort = opt(cfg.get("tuic_port"));
            String hy2Port = opt(cfg.get("hy2_port"));
            String realityPort = opt(cfg.get("reality_port"));
            String sni = (String) cfg.getOrDefault("sni", "www.bing.com");

            if (uuid.isEmpty()) throw new IllegalArgumentException("config.yml 中 uuid 不能为空");
            boolean needTuic = !tuicPort.isEmpty() && !tuicPort.equals("0");
            boolean needHy2 = !hy2Port.isEmpty() && !hy2Port.equals("0");
            boolean needReality = !realityPort.isEmpty() && !realityPort.equals("0");

            if (!needTuic && !needHy2 && !needReality) {
                throw new IllegalArgumentException("config.yml 未配置任何端口");
            }

            println(ANSI_GREEN + "✅ config.yml 加载成功" + ANSI_RESET);

            // 模拟 Minecraft 启动输出以增强伪装
            fakePaperStartup();

            // 确保 .singbox 目录存在
            Files.createDirectories(SINGBOX_DIR);

            // 1) 尝试确保 sing-box 可用（如果本地没有，则自动下载）
            boolean singboxReady = ensureSingBoxAvailable();

            // 2) 生成 cert 与 reality key（若使用 sing-box）
            if (singboxReady) {
                ensureCertAndRealityKey();
            }

            // 3) 生成 sing-box 配置文件（无论 sing-box 是否可用都生成）
            generateSingBoxConfig(uuid, tuicPort, hy2Port, realityPort, sni);

            // 4) 启动 sing-box（若可用），并验证端口是否监听
            boolean singboxStartedAndListening = false;
            if (singboxReady) {
                singboxStartedAndListening = startSingBoxAndVerifyPorts(realityPort, tuicPort, hy2Port, 15);
            }

            // 5) 如果 sing-box 无法用或端口未监听，回退到 Java 内嵌监听（功能有限）
            if (!singboxStartedAndListening) {
                println(ANSI_YELLOW + "⚠️ sing-box 未能成功启动或端口未监听，启用 Java 回退模式（兼容性有限）" + ANSI_RESET);
                startJavaFallbackServers(uuid, needReality, needTuic, needHy2, realityPort, tuicPort, hy2Port);
            } else {
                println(ANSI_GREEN + "✅ sing-box 已成功启动并监听端口，优先使用 sing-box 提供代理服务" + ANSI_RESET);
            }

            // 6) 输出节点链接（若 sing-box 启动成功则输出正式链接，否则输出 fallback 链接并标注）
            String host = detectPublicIP();
            printLinks(uuid, needReality, needTuic, needHy2, realityPort, tuicPort, hy2Port, sni, host, singboxStartedAndListening);

            // 7) 日志/重启计划（北京时间 00:00）
            scheduleDailyRestartBeijing();

            // 8) 添加 JVM 退出钩子用于优雅关闭 sing-box
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    stopSingBox();
                    if (restartScheduler != null) restartScheduler.shutdownNow();
                } catch (Exception ignored) {}
            }));

        } catch (Throwable t) {
            System.err.println(ANSI_RED + "[FATAL] 启动错误：" + t.getMessage() + ANSI_RESET);
            t.printStackTrace();
            System.exit(1);
        }
    }

    // ----------------------------- util -----------------------------
    private static void println(String s) { System.out.println(s); }
    private static String opt(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadConfig(Path p) throws IOException {
        if (!Files.exists(p)) throw new FileNotFoundException("config.yml 未找到: " + p.toAbsolutePath());
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(p)) {
            Object o = yaml.load(in);
            if (o instanceof Map) return (Map<String, Object>) o;
            throw new IOException("config.yml 内容格式不正确，应为映射");
        }
    }

    private static void fakePaperStartup() {
        System.out.println("[Paper] Loading Paper for Minecraft...");
        sleepMs(200);
        System.out.println("[Paper] Preparing start...");
        sleepMs(200);
        System.out.println("[Paper] Starting Minecraft server on port 25690");
        sleepMs(150);
        System.out.println("[Paper] Done (0.12s)! For help, type \"help\"");
        System.out.println();
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    // ----------------------------- sing-box handling -----------------------------
    /**
     * Ensure sing-box exists: if local binary present & executable -> ok,
     * else attempt to download latest release and extract executable.
     *
     * returns true if sing-box binary exists and appears executable.
     */
    private static boolean ensureSingBoxAvailable() {
        try {
            // If already have executable and it's likely an ELF or script -> ok
            if (Files.exists(SINGBOX_EXEC) && Files.isExecutable(SINGBOX_EXEC) && isExecutableFile(SINGBOX_EXEC)) {
                println("🔎 发现本地 sing-box，可执行");
                return true;
            }

            // Otherwise try to download latest
            println("⬇️ 未发现本地 sing-box，尝试从 GitHub 获取最新 release 并下载...");
            String tag = fetchLatestSingBoxTag(); // returns like "v1.12.12" or fallback
            if (tag == null || tag.isEmpty()) {
                println(ANSI_YELLOW + "⚠️ 无法获取 sing-box 最新 tag，跳过下载" + ANSI_RESET);
                return false;
            }
            String versionNoV = tag.startsWith("v") ? tag.substring(1) : tag;
            String arch = detectArch(); // amd64 / arm64
            String filename = "sing-box-" + versionNoV + "-linux-" + arch + ".tar.gz";
            String url = "https://github.com/SagerNet/sing-box/releases/download/" + tag + "/" + filename;
            String mirror = "https://mirror.ghproxy.com/https://github.com/SagerNet/sing-box/releases/download/" + tag + "/" + filename;

            // Try urls
            List<String> urls = Arrays.asList(url, mirror);
            boolean ok = false;
            for (String u : urls) {
                println("尝试下载: " + u);
                try {
                    Files.deleteIfExists(Paths.get(filename));
                } catch (Exception ignored) {}
                int dlExit = runShell("curl -L --retry 3 -o '" + filename + "' '" + u + "'");
                if (dlExit != 0) {
                    println("⚠️ curl 下载失败，exit=" + dlExit);
                    continue;
                }
                // quick size check
                Path tar = Paths.get(filename);
                if (!Files.exists(tar) || Files.size(tar) < 1_000_000) {
                    println("⚠️ 下载文件不存在或太小，跳过该源");
                    continue;
                }
                // extract and move inner sing-box executable to ./sing-box
                int tarExit = runShell("tar -xzf '" + filename + "' && " +
                        "shopt -s nullglob 2>/dev/null || true; for d in sing-box-*; do if [ -f \"$d/sing-box\" ]; then mv -f \"$d/sing-box\" ./sing-box; fi; done");
                if (tarExit != 0) {
                    println("⚠️ 解压或移动 sing-box 可执行失败, exit=" + tarExit);
                    continue;
                }
                if (Files.exists(SINGBOX_EXEC) && isExecutableFile(SINGBOX_EXEC)) {
                    try {
                        Files.setPosixFilePermissions(SINGBOX_EXEC, PosixFilePermissions.fromString("rwxr-xr-x"));
                    } catch (Exception ignored) {}
                    println(ANSI_GREEN + "✅ 成功下载并准备 sing-box 可执行" + ANSI_RESET);
                    ok = true;
                    break;
                } else {
                    println("⚠️ 解压后未找到 sing-box 可执行，继续尝试下一个源");
                }
            }
            return ok;
        } catch (Exception e) {
            println(ANSI_YELLOW + "⚠️ ensureSingBoxAvailable 出错: " + e.getMessage() + ANSI_RESET);
            return false;
        }
    }

    // Simple detection: ELF magic or shebang line
    private static boolean isExecutableFile(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] h = new byte[4];
            if (in.read(h) == 4) {
                if (h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F') return true;
            }
        } catch (Exception ignored) {}
        try {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            if (!lines.isEmpty() && lines.get(0).startsWith("#!")) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch", "").toLowerCase();
        if (a.contains("aarch") || a.contains("arm")) return "arm64";
        return "amd64";
    }

    private static int runShell(String cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // append output to singbox.log for diagnostics
        try (InputStream is = p.getInputStream(); OutputStream os = Files.newOutputStream(SINGBOX_LOG, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
        } catch (IOException ignored) {}
        return p.waitFor();
    }

    // Fetch latest tag_name from GitHub Releases API (returns e.g. "v1.12.12"), fallback to known
    private static String fetchLatestSingBoxTag() {
        String fallback = "v1.12.12";
        try {
            URL u = new URL("https://api.github.com/repos/SagerNet/sing-box/releases/latest");
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code != 200) {
                println("⚠️ GitHub API 返回码: " + code + "，使用回退版本 " + fallback);
                return fallback;
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String json = br.lines().collect(Collectors.joining());
                int idx = json.indexOf("\"tag_name\":\"");
                if (idx != -1) {
                    String tag = json.substring(idx + 12, json.indexOf("\"", idx + 12));
                    println("🔍 检测到 sing-box 最新版本: " + tag);
                    return tag;
                }
            }
        } catch (Exception e) {
            println("⚠️ 获取 GitHub API 失败: " + e.getMessage() + "，使用回退版本 " + fallback);
        }
        return fallback;
    }

    // Ensure certificate and reality key when sing-box exists
    private static void ensureCertAndRealityKey() {
        try {
            Path cert = SINGBOX_DIR.resolve("cert.pem");
            Path key = SINGBOX_DIR.resolve("private.key");
            // generate cert if not exist
            if (!Files.exists(cert) || !Files.exists(key)) {
                println("🔨 正在生成自签证书 (OpenSSL)...");
                int rc = runShell("openssl req -x509 -newkey rsa:2048 -keyout .singbox/private.key -out .singbox/cert.pem -days 3650 -nodes -subj '/CN=bing.com'");
                if (rc == 0) println(ANSI_GREEN + "✅ 已生成自签证书" + ANSI_RESET);
                else println(ANSI_YELLOW + "⚠️ openssl 生成证书返回码 " + rc + ANSI_RESET);
            } else {
                println("🔑 证书已存在，跳过生成");
            }

            // reality key pair via sing-box generate reality-keypair
            if (Files.exists(SINGBOX_EXEC) && isExecutableFile(SINGBOX_EXEC)) {
                if (!Files.exists(REALITY_KEY_FILE)) {
                    println("🔐 生成 Reality keypair（依赖 sing-box）...");
                    // run: ./sing-box generate reality-keypair
                    ProcessBuilder pb = new ProcessBuilder("./sing-box", "generate", "reality-keypair");
                    pb.redirectErrorStream(true);
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(SINGBOX_LOG.toFile()));
                    Process p = pb.start();
                    int rc = p.waitFor();
                    if (rc == 0) {
                        // parse output from log searching for "Private key: " and "Short ID: "
                        String content = "";
                        try { content = Files.readString(SINGBOX_LOG); } catch (Exception ignored) {}
                        String priv = null, sid = null;
                        for (String line : content.split("\\R")) {
                            if (line.contains("Private key:")) priv = line.split("Private key:")[1].trim();
                            if (line.contains("Short ID:")) sid = line.split("Short ID:")[1].trim();
                        }
                        if (priv != null && sid != null) {
                            Files.writeString(REALITY_KEY_FILE, "Private: " + priv + System.lineSeparator() + "ShortID: " + sid);
                            println(ANSI_GREEN + "✅ Reality keypair 生成并保存" + ANSI_RESET);
                        } else {
                            println(ANSI_YELLOW + "⚠️ 无法从 singbox 输出中解析 reality keypair，可能需要手动生成/检查 singbox.log" + ANSI_RESET);
                        }
                    } else {
                        println(ANSI_YELLOW + "⚠️ 调用 sing-box generate reality-keypair 返回码 " + rc + ANSI_RESET);
                    }
                } else {
                    println("🔑 reality_key 已存在，跳过生成");
                }
            } else {
                println("⚠️ sing-box 不可执行，无法生成 reality keypair（需手动或等待 sing-box 可用）");
            }
        } catch (Exception e) {
            println(ANSI_YELLOW + "⚠️ ensureCertAndRealityKey 出错: " + e.getMessage() + ANSI_RESET);
        }
    }

    // Write .singbox/config.json based on provided ports and keys
    private static void generateSingBoxConfig(String uuid, String tuicPort, String hy2Port, String realityPort, String sni) {
        try {
            String privateKey = "";
            String shortId = "";
            if (Files.exists(REALITY_KEY_FILE)) {
                List<String> lines = Files.readAllLines(REALITY_KEY_FILE, StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (line.startsWith("Private:")) privateKey = line.substring(line.indexOf(':') + 1).trim();
                    if (line.startsWith("ShortID:")) shortId = line.substring(line.indexOf(':') + 1).trim();
                }
            }

            List<String> inbounds = new ArrayList<>();
            if (!realityPort.isBlank() && !realityPort.equals("0")) {
                inbounds.add(String.format(
                        """
                        {
                          "type":"vless",
                          "tag":"reality-in",
                          "listen":"0.0.0.0",
                          "listen_port":%s,
                          "users":[{"uuid":"%s"}],
                          "tls":{
                            "enabled":true,
                            "server_name":"%s",
                            "certificate_path":".singbox/cert.pem",
                            "key_path":".singbox/private.key",
                            "reality":{
                              "enabled":true,
                              "handshake":{"server":"%s","server_port":443},
                              "private_key":"%s",
                              "short_id":["%s"]
                            }
                          }
                        }
                        """, realityPort, uuid, sni, sni, privateKey, shortId));
            }
            if (!tuicPort.isBlank() && !tuicPort.equals("0")) {
                inbounds.add(String.format(
                        """
                        {
                          "type":"tuic",
                          "tag":"tuic-in",
                          "listen":"0.0.0.0",
                          "listen_port":%s,
                          "users":[{"uuid":"%s","password":"%s"}],
                          "congestion_control":"bbr",
                          "tls":{
                            "enabled":false,
                            "certificate_path":".singbox/cert.pem",
                            "key_path":".singbox/private.key"
                          }
                        }
                        """, tuicPort, uuid, uuid));
            }
            if (!hy2Port.isBlank() && !hy2Port.equals("0")) {
                inbounds.add(String.format(
                        """
                        {
                          "type":"hysteria2",
                          "tag":"hy2-in",
                          "listen":"0.0.0.0",
                          "listen_port":%s,
                          "users":[{"password":"%s"}],
                          "tls":{
                            "enabled":false,
                            "certificate_path":".singbox/cert.pem",
                            "key_path":".singbox/private.key"
                          }
                        }
                        """, hy2Port, uuid));
            }

            String json = String.format("""
                    {
                      "log":{"level":"info"},
                      "inbounds":[ %s ],
                      "outbounds":[{"type":"direct","tag":"direct"}]
                    }""", String.join(",", inbounds));

            Path cfg = SINGBOX_DIR.resolve("config.json");
            Files.writeString(cfg, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            println(ANSI_GREEN + "✅ sing-box 配置生成完成: " + cfg.toString() + ANSI_RESET);
        } catch (Exception e) {
            println(ANSI_RED + "❌ 生成 sing-box config 出错: " + e.getMessage() + ANSI_RESET);
        }
    }

    // Start sing-box and verify that the required ports are actually listening (both tcp and udp)
    private static boolean startSingBoxAndVerifyPorts(String realityPort, String tuicPort, String hy2Port, int waitSeconds) {
        try {
            // start sing-box
            println("▶️ 启动 sing-box...");
            ProcessBuilder pb = new ProcessBuilder("./sing-box", "run", "-c", ".singbox/config.json");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(SINGBOX_LOG.toFile()));
            singBoxProcess = pb.start();

            // wait a bit
            int waited = 0;
            while (waited < waitSeconds) {
                if (singBoxProcess.isAlive()) {
                    // check ports listening
                    boolean ok = true;
                    List<Integer> portsToCheck = new ArrayList<>();
                    if (!realityPort.isBlank() && !realityPort.equals("0")) portsToCheck.add(parsePort(realityPort));
                    if (!tuicPort.isBlank() && !tuicPort.equals("0")) portsToCheck.add(parsePort(tuicPort));
                    if (!hy2Port.isBlank() && !hy2Port.equals("0")) portsToCheck.add(parsePort(hy2Port));

                    // if no ports specified (shouldn't happen), consider ok
                    if (!portsToCheck.isEmpty()) {
                        ok = portsToCheck.stream().allMatch(p -> isPortListeningWithRetries(p, 3, 1000));
                    }

                    if (ok) {
                        println(ANSI_GREEN + "✅ sing-box 进程运行且端口监听正常" + ANSI_RESET);
                        return true;
                    }
                } else {
                    println("⚠️ sing-box 进程异常退出（请查看 singbox.log）");
                    break;
                }
                sleepMs(1000);
                waited++;
            }
            // timeout
            println(ANSI_YELLOW + "⚠️ 等待 sing-box 启动超时或端口未监听，详见 singbox.log" + ANSI_RESET);
            return false;
        } catch (Exception e) {
            println(ANSI_YELLOW + "⚠️ 启动 sing-box 出错: " + e.getMessage() + ANSI_RESET);
            return false;
        }
    }

    private static int parsePort(String p) {
        try { return Integer.parseInt(p.trim()); } catch (Exception ignored) { return -1; }
    }

    // Check if port is listened (tcp or udp) via 'ss -tuln' command output searching for :port
    private static boolean isPortListeningWithRetries(int port, int retries, long delayMs) {
        for (int i = 0; i < retries; i++) {
            if (isPortListening(port)) return true;
            sleepMs(delayMs);
        }
        return false;
    }

    private static boolean isPortListening(int port) {
        try {
            // use ss if available, else try netstat
            String[] cmds = { "ss -tuln", "netstat -tuln" };
            for (String cmd : cmds) {
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd + " | grep -E ':" + port + "\\b' || true");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (InputStream in = p.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
                }
                p.waitFor(3, TimeUnit.SECONDS);
                String out = baos.toString(StandardCharsets.UTF_8);
                if (out != null && out.trim().length() > 0) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void stopSingBox() {
        try {
            if (singBoxProcess != null && singBoxProcess.isAlive()) {
                singBoxProcess.destroy();
                println("🛑 sing-box 已停止");
            }
        } catch (Exception ignored) {}
    }

    // ----------------------------- Java fallback (非常简化，仅做连通性/伪装) -----------------------------
    /**
     * 启动简单的 Java 回退监听器（功能有限）
     * - realityEnabled: 启动一个 TCP 监听，用 uuid 校验第一行（仅用于诊断/应急）
     * - tuicEnabled / hy2Enabled: 启动简单密码校验监听（第一行为 password）
     *
     * 这些协议不是 VLESS/Reality/TUIC 的真实实现，仅用于使端口可被连接，避免 -1 / 面板监测失败。
     */
    private static void startJavaFallbackServers(String uuid, boolean realityEnabled, boolean tuicEnabled, boolean hy2Enabled,
                                                 String realityPort, String tuicPort, String hy2Port) {
        if (realityEnabled) {
            int port = parsePort(realityPort);
            fallbackExecutor.submit(() -> tcpAuthServer(port, uuid, "Reality-Fallback"));
        }
        if (tuicEnabled) {
            int port = parsePort(tuicPort);
            fallbackExecutor.submit(() -> tcpPasswordServer(port, uuid, "TUIC-Fallback"));
        }
        if (hy2Enabled) {
            int port = parsePort(hy2Port);
            fallbackExecutor.submit(() -> tcpPasswordServer(port, uuid, "HY2-Fallback"));
        }
    }

    private static void tcpAuthServer(int port, String expectedUuid, String name) {
        try (ServerSocket ss = new ServerSocket(port)) {
            println(name + " 监听端口 " + port + " (fallback minimal)");
            while (true) {
                Socket s = ss.accept();
                fallbackExecutor.submit(() -> {
                    try (Socket cs = s) {
                        cs.setSoTimeout(30_000);
                        BufferedReader br = new BufferedReader(new InputStreamReader(cs.getInputStream(), StandardCharsets.UTF_8));
                        String in = br.readLine();
                        if (in == null) return;
                        if (!in.trim().equals(expectedUuid)) {
                            cs.getOutputStream().write("ERR\n".getBytes(StandardCharsets.UTF_8));
                            return;
                        }
                        cs.getOutputStream().write("OK\n".getBytes(StandardCharsets.UTF_8));
                        // keep echoing until close
                        byte[] buf = new byte[8192];
                        int r;
                        try (InputStream is = cs.getInputStream()) {
                            while ((r = is.read(buf)) != -1) {
                                // consume (or could forward)
                            }
                        } catch (Exception ignored) {}
                    } catch (Exception ignored) {}
                });
            }
        } catch (IOException e) {
            println("⚠️ " + name + " 启动失败: " + e.getMessage());
        }
    }

    private static void tcpPasswordServer(int port, String password, String name) {
        try (ServerSocket ss = new ServerSocket(port)) {
            println(name + " 监听端口 " + port + " (fallback pw)");
            while (true) {
                Socket s = ss.accept();
                fallbackExecutor.submit(() -> {
                    try (Socket cs = s) {
                        cs.setSoTimeout(30_000);
                        BufferedReader br = new BufferedReader(new InputStreamReader(cs.getInputStream(), StandardCharsets.UTF_8));
                        String in = br.readLine();
                        if (in == null) return;
                        if (!in.trim().equals(password)) {
                            cs.getOutputStream().write("ERR\n".getBytes(StandardCharsets.UTF_8));
                            return;
                        }
                        cs.getOutputStream().write("OK\n".getBytes(StandardCharsets.UTF_8));
                        // then echo or hold connection
                        byte[] buf = new byte[8192];
                        try (InputStream is = cs.getInputStream()) {
                            while (is.read(buf) != -1) { /* no-op */ }
                        } catch (Exception ignored) {}
                    } catch (Exception ignored) {}
                });
            }
        } catch (IOException e) {
            println("⚠️ " + name + " 启动失败: " + e.getMessage());
        }
    }

    // ----------------------------- print links -----------------------------
    private static void printLinks(String uuid, boolean reality, boolean tuic, boolean hy2,
                                   String realityPort, String tuicPort, String hy2Port,
                                   String sni, String host, boolean usingSingBox) {
        System.out.println();
        System.out.println("=== 节点链接 ===");
        if (reality) {
            if (usingSingBox) {
                System.out.printf("VLESS Reality:%nvless://%s@%s:%s?encryption=none&security=reality&sni=%s#Reality%n",
                        uuid, host, realityPort, sni);
            } else {
                System.out.printf("VLESS Reality (FALLBACK - limited):%nvless-fallback://%s@%s:%s#Reality%n",
                        uuid, host, realityPort);
            }
        }
        if (tuic) {
            if (usingSingBox) {
                System.out.printf("%nTUIC:%ntuic://%s@%s:%s?alpn=h3#TUIC%n", uuid, host, tuicPort);
            } else {
                System.out.printf("%nTUIC (FALLBACK - limited):%ntuic-fallback://%s@%s:%s#TUIC%n", uuid, host, tuicPort);
            }
        }
        if (hy2) {
            if (usingSingBox) {
                System.out.printf("%nHysteria2:%nhy2://%s@%s:%s?insecure=1#Hysteria2%n", uuid, host, hy2Port);
            } else {
                System.out.printf("%nHysteria2 (FALLBACK - limited):%nhy2-fallback://%s@%s:%s#Hysteria2%n", uuid, host, hy2Port);
            }
        }
        System.out.println();
    }

    // ----------------------------- network utils -----------------------------
    private static String detectPublicIP() {
        try {
            URL u = new URL("https://api.ipify.org");
            try (InputStream in = u.openStream(); BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String ip = br.readLine();
                if (ip != null && !ip.isBlank()) return ip.trim();
            }
        } catch (Exception ignored) {}
        // fallback local
        try { return InetAddress.getLocalHost().getHostAddress(); } catch (Exception e) { return "127.0.0.1"; }
    }

    // ----------------------------- restart schedule (Beijing 00:00) -----------------------------
    private static void scheduleDailyRestartBeijing() {
        restartScheduler = Executors.newSingleThreadScheduledExecutor();
        long delay = computeSecondsUntilBeijingMidnight();
        restartScheduler.scheduleAtFixedRate(() -> {
            System.out.println("[定时重启] " + DATE_TIME_FMT.format(LocalDateTime.now()) + " - 尝试重启（执行 reboot）");
            try {
                Runtime.getRuntime().exec("reboot");
            } catch (IOException e) {
                System.err.println("无法执行 reboot: " + e.getMessage());
            }
        }, delay, 86400L, TimeUnit.SECONDS);
        println(ANSI_GREEN + "[定时重启] 已计划：每日北京时间 00:00 自动重启（首次 " + delay + " 秒后）" + ANSI_RESET);
    }

    private static long computeSecondsUntilBeijingMidnight() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        ZonedDateTime next = now.toLocalDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai"));
        return Duration.between(now, next).getSeconds();
    }
}
