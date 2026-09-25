# 公开版：接收端边界

公开版本从公开分支生成独立提交，不合并私有分支历史。发送端实现及其专用测试、调试入口、技术记录继续留在私有项目。

## 保留

- 频道播放、旧 Android 兼容、音轨与字幕、截图、媒体下载。
- 多标签 WebView、收藏栏、鼠标与触控交互、广告拦截、用户脚本。
- 手机网页管理、飞鼠、文件下载与 APK 接收/安装。
- LAN 发现响应、接收端 Wi-Fi Direct 准备及会话路由切换。
- `NTV-TAKEOVER/1` TCP 会话、心跳、断线恢复与遥控回传。
- 接收端主动读取控制端目录、解析流地址；RTSP TCP/UDP 解码播放。
- 独立 UDP 光标通道：校验来源地址、会话和序列，按当前流标识绘制鼠标，丢弃过期坐标。
- `/api/multimedia/control` 接收协议：hello、start、heartbeat、stop；心跳超时恢复先前频道。

## 不包含

画面采集、MediaProjection、音视频编码、RTSP 发送服务、采集保活服务、发起接管和文件转码发送。管理网站不提供发送入口，也不包含发送端脚本。

`third_party/android-transcoder` 仅保留上游 `TextureRender` 及许可证，用于旧 Android 的单帧播放截图；不包含转码器或编码器。

## 验证

静态边界检查：

```sh
python tests/public-receiver-boundary.test.py
node tests/control-pages.test.js
node tests/flymouse-takeover.test.js
```

接收端设备测试（独立包名，不覆盖已有应用）：

```sh
gradlew :app:assembleArm32Debug :app:assembleArm32DebugAndroidTest -PapplicationIdOverride=xiao.bu.tv.publicreceiver -PtestRunner=xiao.bu.tv.CastCursorChannelInstrumentation
adb install -r app/build/outputs/apk/arm32/debug/app-arm32-debug.apk
adb install -r app/build/outputs/apk/androidTest/arm32/debug/app-arm32-debug-androidTest.apk
adb shell am instrument -w xiao.bu.tv.publicreceiver.test/xiao.bu.tv.CastCursorChannelInstrumentation
```

测试覆盖 TCP 握手、心跳、UDP 光标、遥控回传、会话关闭、旧包拒绝、光标实际像素绘制与超时隐藏。不等同于所有实体电视上的端到端编解码测试。

2026-09-25 验证：ARM32/ARM64 debug 与 release 构建通过；Android 9 独立测试包的接收协议/光标和浏览器媒体状态测试通过；44 项管理页场景、飞鼠/手势/标签页/主题/网页音频测试及 HLS、播放进度、10-bit、切轨、重连检查通过。
