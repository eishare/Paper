### Java-Paper更新说明（无需fork本项目）：

* Java 启动器 + Sing-box 多协议内核伪装方案；Server.jar仅有339kb，更便于部署

* 精简化：去除哪吒、argo隧道；保留3种协议：tuic、hy2、vless+xtls+reality
  
* 设置每日零时自动重启服务器，避免内存溢出停机
  
* 持久化运行，服务器重启节点不掉
  
* TCP/UDP端口可共用
  
### 使用说明：

1：下载Release中的Server.jar

2：下载主页config.yml，手动编辑uuid，输入tuic/hy2/vless端口

3：上传至File、开机
