# QQ 音乐精简

面向 QQ 音乐的 LSPosed 模块。已在 Android 16 真机上的 QQ 音乐 **20.6.5.8** 与 **20.9.0.8** 验证。模块只作用于 `com.tencent.qqmusic` 主进程，LSPosed 推荐作用域也仅包含这个包。

设置页提供开屏广告、推送通知广告、底部标签、首页只保留推荐、首页推广区、听歌识曲入口、福利入口和预加载开关。开屏广告、推送通知广告与首页推广区默认拦截；其他项目按需选择。关闭诊断时，没有持续遍历界面、轮询播放器或网络请求。

## 推送通知广告闸门（push_notify）

挂在 `android.app.NotificationManager` 上，四个入口：`notify(int,Notification)`、`notify(String,int,Notification)`、`createNotificationChannel(NotificationChannel)`、`createNotificationChannels(List)`。

**为什么挂这里**：`notify(...)` 是 QQ 音乐进程内所有通知的**唯一出口**——厂商推送通道、自建长连接、轮询拉回来的推广最终都要调它；而且它是**平台类、不参与 R8 混淆**，QQ 音乐改版改名的是它自己的类，这里不受影响。**这一条与首页推广区那类按混淆名查找的规则不同：它不依赖任何 App 内部符号，换版本不会失效。**

**判定顺序**（先便宜后昂贵，命中即停）：渠道 id → 渠道名/描述 → 标题 / 正文 / 长文 / 副标题 / 附加文本 / 滚动文本 / tag 上的广告词。

**这里最容易出事的是误伤**：播放中通知的标题就是**歌名**、副标题是**歌手名**，下载完成通知也在同一进程下发。因此词表只用商务/促销话术（绿钻、超级会员、领券、限时、折扣……），**刻意避开**歌曲、歌手、专辑、歌单、下载、直播、播放；渠道表也**不含** `play` / `media` / `download`，避免误伤媒体渠道。

**性能**：`notify` 是低频事件（按小时计），不是 `onDraw` 那种热路径。拦截体内只做 `String.indexOf` 和取已有对象，零反射、零分配、零逐条日志；放行路径直接 `proceed()`。任何异常 fail-open 放行，行为与没装模块一致。

**判据表可改**：`app/src/io/github/qqmusicclean/NotifyGate.java` 里的 `CHANNEL_TOKENS` / `CHANNEL_NAME_TOKENS` / `TEXT_TOKENS`，改完强停 QQ 音乐生效。

**可观测**：模块页「适配诊断」里这一项显示 `通知下发与渠道创建入口已挂接（4/4）；self_test 7/7 passed`。`self_test` 是安装时用固定样本跑判定函数的结论，**负样本特意放了歌名、歌手、下载完成、关注更新**——证明播放侧通知不会被拦。真正拦到广告后这一行会变成 `已拦广告通知 N 条 / 共见到 M 条；广告渠道 A/B`。

**`createNotificationChannel` 只观测不拦截**：拦掉渠道会让后续 `notify` 抛异常，反而更糟。四个入口只挂上部分时会报 `partial` 并写明 `N/4`，不会被算成“全部生效”。

## 跨版本检测

模块不按版本号整体停用。启动时，开屏和入口规则分别检查类、方法及参数；底部标签按控件结构识别。首页频道优先读取当前安装包对应的缓存与已知结构；未命中时，使用 [DexKit](https://github.com/LuckyPray/DexKit) 在后台按代码特征查找。首次深度读取不会阻塞 QQ 音乐启动，找到后会缓存方法，**重新打开 QQ 音乐时生效**。安装包版本、更新时间或规则版本变化会使缓存失效。当前规则在 20.8.5.8 的结构变化与 20.9.0.8 的方法变化上做过适配；将来若特征消失，相关功能会安全跳过并标成“未匹配”。

首次读取时，QQ 音乐前台会显示扫描内容与进度，完成后弹出逐项结果；失败项会列出具体原因。模块页也保留进度、汇总和逐项状态。“已检测 8/8”仅表示八项都检查过，不表示八项都成功；“已匹配”表示 Hook 成功安装，版本更新后仍建议核对实际界面。更详细的错误可在 LSPosed 模块日志中搜索 `QQMusicClean`。

## 安装与使用

1. 安装 [`app/dist/qqmusicclean-v0.5.3.apk`](app/dist/qqmusicclean-v0.5.3.apk)，在 LSPosed 中启用模块，确认推荐作用域已勾选 QQ 音乐。
2. 打开一次模块设置页，选择要保留的标签与入口。
3. 打开 QQ 音乐，查看前台弹出的逐项检测结果。若显示“已找到，重启后生效”，点击“重启 QQ 音乐”；首次使用该按钮需要在 Root 管理器中给“QQ 音乐精简”授予 Root 权限。如果未授予，可手动强停并重新打开 QQ 音乐。

更改开关后也需要强停并重新打开 QQ 音乐。隐藏入口不会删除 QQ 音乐中的数据。只在首页选择“只保留推荐”时，才尝试过滤其余频道和插入式广告频道。预加载限制是实验功能，默认关闭。

## 构建与验证

使用 [`app/build.ps1`](app/build.ps1) 构建。工具链**自动探测**，不再写死某台机器的路径：SDK 依次找 `QMC_SDK` → `ANDROID_SDK` → `ANDROID_HOME` → `ANDROID_SDK_ROOT` → 仓库同级的 `.android_build_tools\android-sdk` → `C:\Android\Sdk` → `%LOCALAPPDATA%\Android\Sdk`；JDK 找 `QMC_JDK` → `JAVA_HOME` → 同级 `.android_build_tools\jdk17` → AdoptOpenJDK。platform 与 build-tools 取**该 SDK 下实际可用的最高版本**，不再钉死 android-35。

D8 也单独解析：优先 `R8_JAR` 环境变量，其次 `<SDK>\d8\r8-*.jar`，最后才用 SDK 自带的 `d8.jar`——旧 build-tools 自带的是 R8 3.3.20，dexing 本项目会抛 `Cannot invoke String.length() because <parameter1> is null`。另外部分 build-tools 安装的 `d8.bat` 缺失它自己需要的 `d8.jar`，此时会退化成一条看不懂的 `ClassNotFoundException`，所以脚本不依赖 `d8.bat`。

构建脚本使用保留的本地测试密钥签名，并校验 APK 签名；可覆盖安装之前的测试版。首次自行构建会生成新的测试密钥，签名不同，无法直接覆盖这里发布的 APK。原版 QQ 音乐 APK 只用于本地分析，不随项目分发。

20.9.0.8 真机测试中，首次首页特征查找约 0.7 秒且在后台执行；缓存后的启动没有再次扫描。实际界面只剩“推荐”频道与用户保留的首页底部标签，右上角两个入口已隐藏。20.6.5.8 上新增的推广区过滤 Hook 已安装，但对应区块会随服务器内容变化，尚未取得完整的过滤前后对照，因此它仍属实验功能。LSPosed 日志显示冷、热启动广告 Hook 成功安装，但没有完整的广告实物前后对照，因此不宣称所有广告场景都已覆盖，也不宣称已测得省电幅度。

代码按 GPL-3.0 授权。LSPosed 接口、DexKit 及其运行依赖的来源和许可见 [`NOTICE`](NOTICE)。
