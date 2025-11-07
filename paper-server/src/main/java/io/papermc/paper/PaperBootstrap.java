Run chmod +x gradlew 

Welcome to Gradle 8.14.3!

Here are the highlights of this release:
 - Java 24 support
 - GraalVM Native Image toolchain selection
 - Enhancements to test reporting
 - Build Authoring improvements

For more details see https://docs.gradle.org/8.14.3/release-notes.html

Starting a Gradle Daemon (subsequent builds will be faster)
Calculating task graph as no cached configuration is available for tasks: clean

> Configure project :paper-server
paperweight-core v2.0.0-beta.18 (running on 'Linux')

> Task :paper-api:clean UP-TO-DATE
> Task :paper-server:clean UP-TO-DATE

BUILD SUCCESSFUL in 8s
2 actionable tasks: 2 up-to-date
Configuration cache entry stored.
Calculating task graph as no cached configuration is available for tasks: applyPatches

> Configure project :paper-server
paperweight-core v2.0.0-beta.18 (running on 'Linux')

> Task :paper-server:collectPaperATsFromPatches
> Task :paper-server:mergePaperATs
> Task :paper-server:downloadMcManifest
> Task :paper-server:downloadMcVersionManifest
> Task :paper-server:downloadMappings
> Task :paper-server:downloadServerJar
> Task :paper-server:downloadRuntimeClasspathSources
> Task :paper-server:extractFromBundler
> Task :paper-server:downloadMcLibrariesSources
> Task :paper-server:indexLibraryFiles

> Task :paper-server:importPaperLibraryFiles
Importing 13 classes from library sources...

> Task :paper-server:setupMacheResources
Copy initial sources...
Setup git repo...

> Task :paper-server:extractMacheResources

> Task :paper-server:applyResourcePatches
Applied 6 patches

> Task :paper-server:macheRemapJar
> Task :paper-server:macheDecompileJar

> Task :paper-server:setupMacheSources
Copy initial sources...
Setup git repo...
Applying mache patches...
Applied 80 mache patches
Applying access transformers...

> Task :paper-server:extractMacheSources

> Task :paper-server:applySourcePatches
Applied 882 patches

> Task :paper-server:applyFilePatches
> Task :paper-server:applyFeaturePatches
> Task :paper-server:applyPatches

BUILD SUCCESSFUL in 2m 2s
20 actionable tasks: 20 executed
Configuration cache entry stored.
Calculating task graph as no cached configuration is available for tasks: createMojmapPaperclipJar

> Configure project :paper-server
paperweight-core v2.0.0-beta.18 (running on 'Linux')

> Task :paper-api:generateApiVersioningFile
> Task :paper-api:processResources NO-SOURCE
> Task :paper-server:downloadMcManifest
> Task :paper-server:downloadMcVersionManifest UP-TO-DATE
> Task :paper-server:downloadServerJar UP-TO-DATE
> Task :paper-server:extractFromBundler UP-TO-DATE
> Task :paper-server:downloadMappings UP-TO-DATE
> Task :paper-server:processLog4jPluginsResources NO-SOURCE
> Task :paper-server:filterVanillaJar
> Task :paper-server:cloneSpigotBuildData
> Task :paper-server:unpackSpigotBuildData
> Task :paper-server:processResources
/home/runner/work/Paper/Paper/paper-api/src/main/java/org/bukkit/World.java:2522: warning: [dep-ann] deprecated item is not annotated with @Deprecated
    public FallingBlock spawnFallingBlock(@NotNull Location location, @NotNull BlockData data) throws IllegalArgumentException;
                        ^

> Task :paper-api:compileJava
/home/runner/work/Paper/Paper/paper-api/src/main/java/org/bukkit/entity/Player.java:3550: warning: [dep-ann] deprecated item is not annotated with @Deprecated
    default @Nullable Firework boostElytra(final ItemStack firework) {
                               ^

> Task :paper-server:generateMappings
> Task :paper-server:generateSpigotMappings

> Task :paper-api:compileJava
Note: Some input files use or override a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
Note: Some input files use or override a deprecated API that is marked for removal.
Note: Recompile with -Xlint:removal for details.
Note: Some input files use unchecked or unsafe operations.
Note: Recompile with -Xlint:unchecked for details.
2 warnings

> Task :paper-api:classes
> Task :paper-api:jar
Note: Processing Log4j annotations
Note: Annotations processed

> Task :paper-server:compileLog4jPluginsJava
Note: Processing Log4j annotations
Note: No elements to process
Note: /home/runner/work/Paper/Paper/paper-server/src/log4jPlugins/java/io/papermc/paper/logging/DelegateLogEvent.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.

> Task :paper-server:log4jPluginsClasses

> Task :paper-server:compileJava
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:1: error: illegal character: '#'
#!/bin/bash  
^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:1: error: class, interface, enum, or record expected
#!/bin/bash  
  ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:3: error: unclosed character literal
export UUID=${UUID:-'fdeeda45-0a8e-4570-bcc6-d68c995f5830'} # 如开启哪吒v1,不同的平台需要改一下，否则会覆盖
                    ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:3: error: unclosed character literal
export UUID=${UUID:-'fdeeda45-0a8e-4570-bcc6-d68c995f5830'} # 如开启哪吒v1,不同的平台需要改一下，否则会覆盖
                                                         ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:3: error: illegal character: '#'
export UUID=${UUID:-'fdeeda45-0a8e-4570-bcc6-d68c995f5830'} # 如开启哪吒v1,不同的平台需要改一下，否则会覆盖
                                                            ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:3: error: illegal character: '\uff0c'
export UUID=${UUID:-'fdeeda45-0a8e-4570-bcc6-d68c995f5830'} # 如开启哪吒v1,不同的平台需要改一下，否则会覆盖
                                                                                ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:4: error: empty character literal
export NEZHA_SERVER=${NEZHA_SERVER:-''}       # v1哪吒填写形式：nezha.abc.com:8008,v0哪吒填写形式：nezha.abc.com
                                    ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:4: error: illegal character: '#'
export NEZHA_SERVER=${NEZHA_SERVER:-''}       # v1哪吒填写形式：nezha.abc.com:8008,v0哪吒填写形式：nezha.abc.com
                                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:4: error: illegal character: '\uff1a'
export NEZHA_SERVER=${NEZHA_SERVER:-''}       # v1哪吒填写形式：nezha.abc.com:8008,v0哪吒填写形式：nezha.abc.com
                                                        ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:4: error: illegal character: '\uff1a'
export NEZHA_SERVER=${NEZHA_SERVER:-''}       # v1哪吒填写形式：nezha.abc.com:8008,v0哪吒填写形式：nezha.abc.com
                                                                                    ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:5: error: empty character literal
export NEZHA_PORT=${NEZHA_PORT:-''}           # v1哪吒不要填写这个,v0哪吒agent端口为{443,8443,2053,2083,2087,2096}其中之一时自动开启tls
                                ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:5: error: illegal character: '#'
export NEZHA_PORT=${NEZHA_PORT:-''}           # v1哪吒不要填写这个,v0哪吒agent端口为{443,8443,2053,2083,2087,2096}其中之一时自动开启tls
                                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:6: error: empty character literal
export NEZHA_KEY=${NEZHA_KEY:-''}             # 哪吒v0-agent密钥或v1的NZ_CLIENT_SECRET
                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:6: error: illegal character: '#'
export NEZHA_KEY=${NEZHA_KEY:-''}             # 哪吒v0-agent密钥或v1的NZ_CLIENT_SECRET
                                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:7: error: empty character literal
export ARGO_DOMAIN=${ARGO_DOMAIN:-''}         # 固定隧道域名,留空即启用临时隧道
                                  ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:7: error: illegal character: '#'
export ARGO_DOMAIN=${ARGO_DOMAIN:-''}         # 固定隧道域名,留空即启用临时隧道
                                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:8: error: empty character literal
export ARGO_AUTH=${ARGO_AUTH:-''}             # 固定隧道token或json,留空即启用临时隧道
                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:8: error: illegal character: '#'
export ARGO_AUTH=${ARGO_AUTH:-''}             # 固定隧道token或json,留空即启用临时隧道
                                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:9: error: unclosed character literal
export CFIP=${CFIP:-'cf.877774.xyz'}          # argo节点优选域名或优选ip
                    ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:9: error: unclosed character literal
export CFIP=${CFIP:-'cf.877774.xyz'}          # argo节点优选域名或优选ip
                                  ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:9: error: illegal character: '#'
export CFIP=${CFIP:-'cf.877774.xyz'}          # argo节点优选域名或优选ip
                                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:10: error: unclosed character literal
export CFPORT=${CFPORT:-'443'}                # argo节点端口 
                        ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:10: error: unclosed character literal
export CFPORT=${CFPORT:-'443'}                # argo节点端口 
                            ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:10: error: illegal character: '#'
export CFPORT=${CFPORT:-'443'}                # argo节点端口 
                                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:11: error: empty character literal
export NAME=${NAME:-''}                       # 节点名称  
                    ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:11: error: illegal character: '#'
export NAME=${NAME:-''}                       # 节点名称  
                                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:12: error: unclosed character literal
export FILE_PATH=${FILE_PATH:-'./.npm'}       # 节点sub.txt保存路径  
                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:12: error: unclosed character literal
export FILE_PATH=${FILE_PATH:-'./.npm'}       # 节点sub.txt保存路径  
                                     ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:12: error: illegal character: '#'
export FILE_PATH=${FILE_PATH:-'./.npm'}       # 节点sub.txt保存路径  
                                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:13: error: unclosed character literal
export ARGO_PORT=${ARGO_PORT:-'8001'}         # argo端口 使用固定隧道token,cloudflare后台设置的端口需和这里对应
                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:13: error: unclosed character literal
export ARGO_PORT=${ARGO_PORT:-'8001'}         # argo端口 使用固定隧道token,cloudflare后台设置的端口需和这里对应
                                   ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:13: error: illegal character: '#'
export ARGO_PORT=${ARGO_PORT:-'8001'}         # argo端口 使用固定隧道token,cloudflare后台设置的端口需和这里对应
                                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:14: error: empty character literal
export TUIC_PORT=${TUIC_PORT:-''}             # Tuic 端口，支持多端口玩具可填写，否则不动
                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:14: error: illegal character: '#'
export TUIC_PORT=${TUIC_PORT:-''}             # Tuic 端口，支持多端口玩具可填写，否则不动
                                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:14: error: illegal character: '\uff0c'
export TUIC_PORT=${TUIC_PORT:-''}             # Tuic 端口，支持多端口玩具可填写，否则不动
                                                       ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:14: error: illegal character: '\uff0c'
export TUIC_PORT=${TUIC_PORT:-''}             # Tuic 端口，支持多端口玩具可填写，否则不动
                                                                  ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:15: error: empty character literal
export HY2_PORT=${HY2_PORT:-''}               # Hy2 端口，支持多端口玩具可填写，否则不动
                            ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:15: error: illegal character: '#'
export HY2_PORT=${HY2_PORT:-''}               # Hy2 端口，支持多端口玩具可填写，否则不动
                                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:15: error: illegal character: '\uff0c'
export HY2_PORT=${HY2_PORT:-''}               # Hy2 端口，支持多端口玩具可填写，否则不动
                                                      ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:15: error: illegal character: '\uff0c'
export HY2_PORT=${HY2_PORT:-''}               # Hy2 端口，支持多端口玩具可填写，否则不动
                                                                 ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:16: error: empty character literal
export REALITY_PORT=${REALITY_PORT:-''}       # reality 端口,支持多端口玩具可填写，否则不动   
                                    ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:16: error: illegal character: '#'
export REALITY_PORT=${REALITY_PORT:-''}       # reality 端口,支持多端口玩具可填写，否则不动   
                                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:16: error: illegal character: '\uff0c'
export REALITY_PORT=${REALITY_PORT:-''}       # reality 端口,支持多端口玩具可填写，否则不动   
                                                                     ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:17: error: empty character literal
export CHAT_ID=${CHAT_ID:-''}                 # TG chat_id，可在https://t.me/laowang_serv00_bot 获取
                          ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:17: error: illegal character: '#'
export CHAT_ID=${CHAT_ID:-''}                 # TG chat_id，可在https://t.me/laowang_serv00_bot 获取
                                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:17: error: illegal character: '\uff0c'
export CHAT_ID=${CHAT_ID:-''}                 # TG chat_id，可在https://t.me/laowang_serv00_bot 获取
                                                          ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:18: error: empty character literal
export BOT_TOKEN=${BOT_TOKEN:-''}             # TG bot_token, 使用自己的bot需要填写,使用上方的bot不用填写,不会给别人发送
                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:18: error: illegal character: '#'
export BOT_TOKEN=${BOT_TOKEN:-''}             # TG bot_token, 使用自己的bot需要填写,使用上方的bot不用填写,不会给别人发送
                                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:19: error: empty character literal
export UPLOAD_URL=${UPLOAD_URL:-''}  # 订阅自动上传地址,没有可不填,需要填部署Merge-sub项目后的首页地址,例如：https://merge.zabc.net
                                ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:19: error: illegal character: '#'
export UPLOAD_URL=${UPLOAD_URL:-''}  # 订阅自动上传地址,没有可不填,需要填部署Merge-sub项目后的首页地址,例如：https://merge.zabc.net
                                     ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:19: error: illegal character: '\uff1a'
export UPLOAD_URL=${UPLOAD_URL:-''}  # 订阅自动上传地址,没有可不填,需要填部署Merge-sub项目后的首页地址,例如：https://merge.zabc.net
                                                                               ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:20: error: unclosed character literal
export DISABLE_ARGO=${DISABLE_ARGO:-'false'}  # 是否禁用argo, true为禁用,false为不禁用
                                    ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:20: error: unclosed character literal
export DISABLE_ARGO=${DISABLE_ARGO:-'false'}  # 是否禁用argo, true为禁用,false为不禁用
                                          ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:20: error: illegal character: '#'
export DISABLE_ARGO=${DISABLE_ARGO:-'false'}  # 是否禁用argo, true为禁用,false为不禁用
                                              ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:24: error: illegal character: '#'
# tail -f /dev/null  # 若只单独运行此文件并希望保持运行,去掉此行开头的#号
^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:24: error: illegal character: '#'
# tail -f /dev/null  # 若只单独运行此文件并希望保持运行,去掉此行开头的#号
                     ^
/home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:24: error: illegal character: '#'
# tail -f /dev/null  # 若只单独运行此文件并希望保持运行,去掉此行开头的#号
                                               ^
57 errors

> Task :paper-server:compileJava FAILED
> Task :paper-server:spigotRemapJar

[Incubating] Problems report is available at: file:///home/runner/work/Paper/Paper/build/reports/problems/problems-report.html

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':paper-server:compileJava'.
> Compilation failed; see the compiler output below.
  /home/runner/work/Paper/Paper/paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java:1: error: illegal character: '#'
  #!/bin/bash  
  ^
  57 errors

* Try:
> Check your code and dependencies to fix the compilation error(s)
> Run with --scan to get full insights.

BUILD FAILED in 54s
17 actionable tasks: 13 executed, 4 up-to-date
Configuration cache entry stored.
Error: Process completed with exit code 1.
