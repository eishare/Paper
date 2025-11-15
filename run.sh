#!/bin/bash
cd server.properties

RESTART_HOUR=0
RESTART_MINUTE=3

echo "[守护进程] 启动，计划每日 ${RESTART_HOUR}:${RESTART_MINUTE} 重启"

while true; do
    echo "[启动] $(date '+%Y-%m-%d %H:%M:%S') - 启动服务"
    java -Xms128m -Xmx512m -jar PaperBootstrap.jar
    CODE=$?

    if [ $CODE -eq 100 ]; then
        NOW=$(date +%s)
        TARGET=$(date -d "today ${RESTART_HOUR}:${RESTART_MINUTE}" +%s 2>/dev/null || date -d "tomorrow ${RESTART_HOUR}:${RESTART_MINUTE}" +%s)
        [ $NOW -gt $TARGET ] && TARGET=$(date -d "tomorrow ${RESTART_HOUR}:${RESTART_MINUTE}" +%s)
        SLEEP=$((TARGET - NOW))
        echo "[计划重启] 等待 ${SLEEP}s 后于 $(date -d "@$TARGET" '+%H:%M') 重启"
        sleep $SLEEP
    else
        echo "[异常退出] 退出码: $CODE，5秒后重试"
        sleep 5
    fi
done
