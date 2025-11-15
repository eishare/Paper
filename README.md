### Java-Paper说明-无需再fork本项目！

### （已精简为：Java 启动器 + Sing-box 多协议内核伪装方案）


* Server.jar仅有340kb，极速完成部署

* 去除：哪吒、argo隧道；保留3种协议：tuic、hy2、vless+xtls+reality
  
* 自动保活：每天夜间零时3分（00:03）自动执行一次singbox重启，清除缓存
  
* TCP/UDP端口可共用
--------------------------------------------------------------
  
### 使用方法：

1：下载Release中的Server.jar

2：下载config.yml，输入tuic/hy2/vless端口（uuid无需输入，自动生成）

3：上传至File、开机
