private static void generateSingBoxConfig(Path file, String uuid,
                                          boolean tuic, boolean hy2,
                                          String tuicPort, String hy2Port,
                                          String xhttpPort, String anytlsPort,
                                          String sni, Path cert, Path key, String privateKey) throws IOException {

    List<String> inbounds = new ArrayList<>();

    if (tuic) inbounds.add(String.format("""
        {
          "type": "tuic",
          "listen": "::",
          "listen_port": %s,
          "users": [{"uuid": "%s", "password": "admin"}],
          "congestion_control": "bbr",
          "zero_rtt_handshake": true,
          "udp_relay_mode": "native",
          "heartbeat": "10s",
          "tls": {"enabled": true, "alpn": ["h3"], "insecure": true, "certificate_path": "%s", "key_path": "%s"}
        }""", tuicPort, uuid, cert, key));

    if (hy2) inbounds.add(String.format("""
        {
          "type": "hysteria2",
          "listen": "::",
          "listen_port": %s,
          "users": [{"password": "%s"}],
          "masquerade": "https://bing.com",
          "ignore_client_bandwidth": true,
          "up_mbps": 1000,
          "down_mbps": 1000,
          "tls": {"enabled": true, "alpn": ["h3"], "insecure": true, "certificate_path": "%s", "key_path": "%s"}
        }""", hy2Port, uuid, cert, key));

    // ✅ 新增 xhttp + Reality + Multiplex
    if (!xhttpPort.isEmpty()) inbounds.add(String.format("""
        {
          "type": "xhttp",
          "listen": "::",
          "listen_port": %s,
          "users": [{"uuid": "%s"}],
          "multiplex": {"enabled": true, "protocol": "h2"},
          "tls": {
            "enabled": true,
            "server_name": "%s",
            "reality": {
              "enabled": true,
              "handshake": {"server": "%s", "server_port": 443},
              "private_key": "%s",
              "short_id": [""]
            }
          }
        }""", xhttpPort, uuid, sni, sni, privateKey));

    // ✅ 新增 AnyTLS Reality
    if (!anytlsPort.isEmpty()) inbounds.add(String.format("""
        {
          "type": "anytls",
          "listen": "::",
          "listen_port": %s,
          "users": [{"uuid": "%s"}],
          "tls": {
            "enabled": true,
            "server_name": "%s",
            "reality": {
              "enabled": true,
              "handshake": {"server": "%s", "server_port": 443},
              "private_key": "%s",
              "short_id": [""]
            }
          }
        }""", anytlsPort, uuid, sni, sni, privateKey));

    String json = """
    {"log": {"level": "info"}, "inbounds": [%s], "outbounds": [{"type": "direct"}]}"""
        .formatted(String.join(",", inbounds));

    Files.writeString(file, json);
    System.out.println("✅ sing-box 配置生成完成");
}
