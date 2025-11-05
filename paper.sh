#!/bin/bash
# deploy.sh - 自动部署 TUIC / Hysteria2 + 自动生成证书
# 手动设置端口 → 一键部署

set -euo pipefail

# ==================== 配置区（只需改这里）====================
TUIC_PORT="12345"         # 留空 "" = 不部署 TUIC
HYSTERIA2_PORT=""         # 留空 "" = 不部署 Hysteria2
# ===========================================================

IP=$(curl -s https://api.ipify.org || echo "127.0.0.1")
echo "Server IP: $IP"

# ==================== 证书自动生成 ====================
CERT_DIR="./certs"
CERT_PEM="$CERT_DIR/fullchain.pem"
KEY_PEM="$CERT_DIR/privkey.pem"

gen_cert() {
  mkdir -p "$CERT_DIR"
  if [[ ! -f "$CERT_PEM" || ! -f "$KEY_PEM" ]]; then
    echo "正在生成自签 TLS 证书到 $CERT_DIR ..."
    openssl req -x509 -newkey rsa:2048 \
      -keyout "$KEY_PEM" \
      -out "$CERT_PEM" \
      -days 365 \
      -nodes \
      -subj "/CN=$IP" \
      -addext "subjectAltName = IP:$IP" \
      >/dev/null 2>&1
    echo "证书生成完成！"
  else
    echo "检测到已有证书，使用现有证书。"
  fi
}
# =====================================================

# ==================== 下载二进制 ====================
download() {
  local name=$1 url=$2
  if [[ ! -x "$name" ]]; then
    echo "正在下载 $name ..."
    curl -L -o "$name" "$url" --fail --connect-timeout 15
    chmod +x "$name"
  fi
}
# =====================================================

# ==================== TUIC 部署 ====================
deploy_tuic() {
  local port=$1
  local uuid=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || openssl rand -hex 16)
  local pwd=$(openssl rand -hex 16)

  download "tuic-server" "https://github.com/EAimTY/tuic/releases/latest/download/tuic-server-x86_64-unknown-linux-gnu"

  cat > tuic.toml <<EOF
[server]
listen = "0.0.0.0:$port"
certificate = "$CERT_PEM"
private_key = "$KEY_PEM"

[users]
$uuid = "$pwd"
EOF

  ./tuic-server -c tuic.toml &
  local link="tuic://$uuid:$pwd@$IP:$port?sni=$IP&congestion_control=bbr&alpn=h3&allowInsecure=1#TUIC-$port"
  echo "$link" > tuic_link.txt
  echo "TUIC 启动成功 → $link"
}
# =====================================================

# ==================== Hysteria2 部署 ====================
deploy_hysteria2() {
  local port=$1
  local pwd=$(openssl rand -hex 16)

  download "hysteria2" "https://github.com/apernet/hysteria/releases/latest/download/hysteria-linux-amd64"

  cat > hy2.yaml <<EOF
listen: :$port

auth:
  type: password
  password: $pwd

tls:
  cert: $CERT_PEM
  key: $KEY_PEM

masquerade:
  type: proxy
  proxy:
    url: https://bing.com
    rewriteHost: true
EOF

  ./hysteria2 server -c hy2.yaml &
  local link="hysteria2://$pwd@$IP:$port/?sni=$IP&insecure=1#Hysteria2-$port"
  echo "$link" > hy2_link.txt
  echo "Hysteria2 启动成功 → $link"
}
# =====================================================

# ==================== 主逻辑 ====================
main() {
  # 1. 自动生成证书
  gen_cert

  # 2. 部署节点
  [[ -n "$TUIC_PORT" && "$TUIC_PORT" =~ ^[0-9]+$ ]] && deploy_tuic "$TUIC_PORT"
  [[ -n "$HYSTERIA2_PORT" && "$HYSTERIA2_PORT" =~ ^[0-9]+$ ]] && deploy_hysteria2 "$HYSTERIA2_PORT"

  # 3. 错误检查
  if [[ -z "$TUIC_PORT" && -z "$HYSTERIA2_PORT" ]]; then
    echo "错误：请至少填写一个端口！"
    exit 1
  fi

  echo "所有节点已启动，容器保持运行..."
  trap 'echo "停止信号，关闭子进程..."; pkill -P $$; exit' SIGTERM SIGINT
  wait
}
# =====================================================

main
