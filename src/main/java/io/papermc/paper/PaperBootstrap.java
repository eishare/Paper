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
            System.out.println("📦 读取配置文件 config.yml ...");
            Map<String, Object> config = loadConfig();

            String uuid = get(config, "uuid");
            String tuicPort = get(config, "tuic_port");
            String hy2Port = get(config, "hy2_port");
            String realityPort = get(config, "reality_port");

            if (uuid.isEmpty()) throw new RuntimeException("❌ uuid 未设置");
            if (tuicPort.isEmpty() && hy2Port.isEmpty() && realityPort.isEmpty())
                throw new RuntimeException("❌ 未设置任何协议端口");

            Path base = Paths.get("/tmp/.npm");
            Files.createDirectories(base);
            Path bin = base.resolve("sing-box");
            Path keyFile = base.resolve("key.txt");
            Path cert = base.resolve("cert.pem");
            Path priv = base.resolve("private.key");
            Path cfg = base.resolve("config.json");
            Path list = base.resolve("list.txt");
            Path sub = base.resolve("sub.txt");

            // 架构判断与下载
            String arch = detectArch();
            String baseUrl = switch (arch) {
                case "arm64" -> "https://arm64.ssss.nyc.mn";
                case "amd64" -> "https://amd64.ssss.nyc.mn";
                case "s390x" -> "https://s390x.ssss.nyc.mn";
                default -> throw new RuntimeException("不支持架构: " + arch);
            };
            downloadSingBox(bin, baseUrl);

            // 生成/加载 Reality 密钥
            String privateKey, publicKey;
            if (Files.exists(keyFile)) {
                System.out.println("🔑 已检测到 reality.key，加载中...");
                List<String> lines = Files.readAllLines(keyFile);
                privateKey = lines.stream().filter(l -> l.contains("PrivateKey"))
                        .map(l -> l.split(":")[1].trim()).findFirst().orElse("");
                publicKey = lines.stream().filter(l -> l.contains("PublicKey"))
                        .map(l -> l.split(":")[1].trim()).findFirst().orElse("");
            } else {
                System.out.println("🔑 首次生成 Reality 密钥对...");
                Process p = new ProcessBuilder(bin.toString(), "generate", "reality-keypair")
                        .redirectErrorStream(true).start();
                p.waitFor();
                String out = new String(p.getInputStream().readAllBytes());
                Files.writeString(keyFile, out);
                privateKey = extractKey(out, "PrivateKey");
                publicKey = extractKey(out, "PublicKey");
            }

            // 生成证书
            if (!Files.exists(cert) || !Files.exists(priv)) {
                generateSelfSignedCert(cert, priv);
            }

            // 生成 config.json
            generateConfig(cfg, uuid, tuicPort, hy2Port, realityPort, privateKey, cert, priv);

            // 启动 sing-box
            startSingBox(bin, cfg);

            // 输出节点信息
            String ip = detectIP();
            String isp = detectISP();
            List<String> links = new ArrayList<>();
            if (!tuicPort.isEmpty())
                links.add(String.format("tuic://%s:admin@%s:%s?sni=www.bing.com&alpn=h3&congestion_control=bbr&allowInsecure=1#TUIC-%s", uuid, ip, tuicPort, isp));
            if (!hy2Port.isEmpty())
                links.add(String.format("hysteria2://%s@%s:%s/?sni=www.bing.com&insecure=1#Hysteria2-%s", uuid, ip, hy2Port, isp));
            if (!realityPort.isEmpty())
                links.add(String.format("vless://%s@%s:%s?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.nazhumi.com&fp=firefox&pbk=%s&type=tcp#Reality-%s",
                        uuid, ip, realityPort, publicKey, isp));

            Files.write(list, links);
            Files.writeString(sub, Base64.getEncoder().encodeToString(String.join("\n", links).getBytes()));

            System.out.println("\n✅ 节点生成成功：");
            links.forEach(System.out::println);
            System.out.println("\n📄 订阅文件: " + sub);

            scheduleDailyRestart();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String get(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(Paths.get("config.yml"))) {
            Object o = yaml.load(in);
            if (o instanceof Map) return (Map<String, Object>) o;
        }
        return new HashMap<>();
    }

    private static void downloadSingBox(Path bin, String baseUrl) throws IOException, InterruptedException {
        if (Files.exists(bin)) return;
        System.out.println("⬇️ 下载 sing-box ...");
        Path tmp = Files.createTempFile("sb", "");
        new ProcessBuilder("bash", "-c", "curl -L -sS -o " + tmp + " \"" + baseUrl + "/sb\"")
                .inheritIO().start().waitFor();
        Files.move(tmp, bin, StandardCopyOption.REPLACE_EXISTING);
        bin.toFile().setExecutable(true);
        System.out.println("✅ 下载完成");
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        if (a.contains("arm")) return "arm64";
        if (a.contains("x86")) return "amd64";
        if (a.contains("s390x")) return "s390x";
        return "unknown";
    }

    private static String extractKey(String text, String key) {
        for (String line : text.split("\n"))
            if (line.contains(key + ":")) return line.split(":")[1].trim();
        return "";
    }

    private static void generateSelfSignedCert(Path cert, Path key) throws IOException, InterruptedException {
        System.out.println("🔨 生成自签证书...");
        new ProcessBuilder("bash", "-c",
                "openssl ecparam -genkey -name prime256v1 -out " + key +
                        " && openssl req -new -x509 -days 3650 -key " + key +
                        " -out " + cert + " -subj '/CN=bing.com'")
                .inheritIO().start().waitFor();
    }

    private static void generateConfig(Path cfg, String uuid, String tuic, String hy2,
                                       String reality, String privateKey,
                                       Path cert, Path key) throws IOException {
        List<String> inbounds = new ArrayList<>();

        if (!tuic.isEmpty())
            inbounds.add(String.format("""
                {
                  "type": "tuic",
                  "listen": "::",
                  "listen_port": %s,
                  "users": [{"uuid": "%s", "password": "admin"}],
                  "congestion_control": "bbr",
                  "tls": {"enabled": true, "alpn": ["h3"], "certificate_path": "%s", "key_path": "%s"}
                }""", tuic, uuid, cert, key));

        if (!hy2.isEmpty())
            inbounds.add(String.format("""
                {
                  "type": "hysteria2",
                  "listen": "::",
                  "listen_port": %s,
                  "users": [{"password": "%s"}],
                  "masquerade": "https://bing.com",
                  "tls": {"enabled": true, "alpn": ["h3"], "certificate_path": "%s", "key_path": "%s"}
                }""", hy2, uuid, cert, key));

        if (!reality.isEmpty())
            inbounds.add(String.format("""
                {
                  "type": "vless",
                  "listen": "::",
                  "listen_port": %s,
                  "users": [{"uuid": "%s", "flow": "xtls-rprx-vision"}],
                  "tls": {"enabled": true, "server_name": "www.nazhumi.com",
                    "reality": {"enabled": true,
                      "handshake": {"server": "www.nazhumi.com", "server_port": 443},
                      "private_key": "%s", "short_id": [""]}}
                }""", reality, uuid, privateKey));

        String json = """
            {"log":{"disabled":true},"inbounds":[%s],"outbounds":[{"type":"direct"}]}
            """.formatted(String.join(",", inbounds));
        Files.writeString(cfg, json);
        System.out.println("✅ sing-box 配置生成完成");
    }

    private static void startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        new ProcessBuilder("bash", "-c", bin + " run -c " + cfg + " > /tmp/singbox.log 2>&1 &").start();
        Thread.sleep(2000);
        System.out.println("🚀 sing-box 已启动");
    }

    private static String detectIP() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream()))) {
            return br.readLine();
        } catch (Exception e) {
            return "IP_ERROR";
        }
    }

    private static String detectISP() {
        try {
            Process p = new ProcessBuilder("bash", "-c",
                    "curl -s --max-time 2 https://speed.cloudflare.com/meta | awk -F'\"' '{print $26\"-\"$18}'").start();
            p.waitFor();
            return new String(p.getInputStream().readAllBytes()).trim();
        } catch (Exception e) {
            return "0.0";
        }
    }

    private static void scheduleDailyRestart() {
        ScheduledExecutorService s = Executors.newScheduledThreadPool(1);
        Runnable r = () -> {
            try {
                new ProcessBuilder("bash", "-c", "pkill -f sing-box || true").start().waitFor();
                Thread.sleep(1000);
                new ProcessBuilder("bash", "-c", "nohup java -jar server.jar > /dev/null 2>&1 &").start();
                System.out.println("♻️ 已触发每日自动重启");
                System.exit(0);
            } catch (Exception ignored) {}
        };
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        LocalDateTime next = now.withHour(0).withMinute(0).withSecond(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        long delay = java.time.Duration.between(now, next).toSeconds();
        s.scheduleAtFixedRate(r, delay, 86400, TimeUnit.SECONDS);
        System.out.println("🕛 定时重启已设定（北京时间每日 00:00）");
    }
}
