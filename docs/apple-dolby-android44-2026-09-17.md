# Android 4.4 苹果 / 杜比测试源优化（2026-09-17）

设备：Samsung SM-G3608，Android 4.4.4，ADB `8ba8ceb8`。使用设备现有的「测试频道」分组，保留频道表和配置；只覆盖安装 `xiao.bu.tv`，没有清数据或安装测试组件。

## 发现与修改

1. 第三方 HLS 原来设置 `min-frames=20`。IJK 在音视频队列达到包数门槛后停止读包，不能仅靠较大的缓冲水位获得足够预读。苹果 1080p60 实测视频缓存中位数只有 850 ms，几乎每个分片边界都会缓冲。
   - 普通 HTTP/HTTPS 媒体预读门槛改为 360 个包，压缩包队列上限 8 MiB，`infbuf=0`。
   - CCTV、投屏、远端频道代理保留原来的专用策略；没有修改清晰度或码率选择。
2. 原来的卡顿恢复重新创建播放器，却没有恢复点播进度。播放中下载超时后，可能表现为重新从头播放。
   - 同地址重连前读取点播进度，通过新播放器的 `seek-at-start` 指定起点；切台不会串用旧进度。直播不设置点播 seek。
   - 实测捕获苹果混合源在 103682 ms 处下载超时，恢复日志和 API 均显示保留该位置。这不是固定每 60 秒执行的定时器；最初苹果 TS 基线连续越过 60 秒也未重启。
3. 代理在已经输出 200/206 响应后遇到读超时，原来还会追加一个 HTTP 502 错误响应到视频字节流。
   - 响应开始后仅终止损坏的连接，交给播放器恢复，不再写入第二个响应。响应头发送前失败仍返回 502。
   - 苹果混合源两轮分别在 103.7 / 105.8 秒出现分片中途 TLS 超时，而同一 Range 在电脑可约 0.7 秒完整下载。增加最多两次字节范围续传：仅对具有有效 Content-Range 和强 ETag 的有限 206 响应启用；使用 If-Range，复核返回状态、起止偏移、总长和 ETag，拒绝忽略 Range 或资源已变化的响应。失败时不拼接任何错误响应内容。
   - 点播缓冲恢复等待改为 30 秒，避免分片重试仍在进行就重建解码器。直播保持原 10 秒。该机制与用户可关闭的「超时切备用线路」分开。
4. 复用 TLS 连接时，多轮苹果 Range 下载在约 104–126 秒后出现读响应超时，重试/续播也会反复等待。在 Android 4.x 的 Range 请求中加入 `Connection: close` 后，对照轮已越过此前所有故障位置；现代 Android 不改变连接复用策略。
5. 这台设备没有 HEVC 硬解。先前尝试 GLES/YUV 降低 10bit 转换开销，但后续切台出现连续 `eglCreateWindowSurface failed` 和黑屏，因此已撤回 `overlay-format=fcc-_es2`，恢复原有渲染路径。下面早期 HEVC 帧率结果只记录实验，不代表最终版本。
   - 按用户要求停止 10bit 渲染/帧率优化，以音频播放为先。Android 4.x 非实时投屏播放的 `framedrop` 使用原先显式软解的值 5，覆盖自动模式中实际回退软解的情况；IJK 保持原有音频主时钟，允许丢弃更多落后的画面。实时投屏仍为 0。
   - 根据实际视频像素格式（或明确的 Main 10 / High 10 profile）识别 10bit，再查询对应硬件 decoder 的 profile。缺少硬解时 Toast 提示一次，不切台、不暂停、不改变解码器。能力未知不误报，软件 decoder 不算硬解；正在使用 MediaCodec 的流不提示。

## 早期实测结果（包含已撤回的 GLES 实验）

数据通过设备 `/api/media` 每 5 秒采样，结合 logcat 核对。帧率是短窗口估计值，60 fps 流的读数可能略高于 60；以下不是长期压力测试。

| 测试源 | 结果 |
| --- | --- |
| 苹果 H.264 / MPEG-TS | 同样约 140 秒观察窗口，播放进度从修改前 110.2 秒改善到 130.9 秒；缓存中位数 0.85 秒 → 8.07 秒。未重置；仍出现一次约 7.3 秒的网络缓冲，不能宣称完全无卡顿。 |
| 苹果 H.264 / fMP4 | 连续播放到 125.3 秒，约 60 fps，无进度归零。 |
| 苹果 HEVC / H.264 混合源 | 自动选择现有 1080p60 H.264 分支。复用连接时在约 103.7 / 105.8 / 125.5 秒遇到 SocketTimeout。最终关闭旧设备 Range 请求的连接复用后，播放到 189.58 秒；216 秒观察窗口内没有重建播放器/进度归零，但约 189.6 秒再次缓冲，仍存在网络侧稳定性限制。 |
| 杜比 720p H.264：HLS packed audio | 完整播完约 75 秒，约 25 fps，正常结束，无切线。 |
| 杜比 720p H.264：HLS fMP4 | 完整播完约 75 秒，约 25 fps，正常结束，无切线。 |
| 杜比 720p H.264：MP4 | 完整播完约 75 秒，约 25 fps，正常结束，无切线。 |
| 杜比 1080p HEVC：HLS fMP4 | 10–45 秒区间帧率中位数由 3.0 提升至 6.55 fps。 |
| 杜比 1080p HEVC：MP4 | 10–45 秒区间帧率中位数由 3.0 提升至 14.05 fps。 |

HEVC 两组改善幅度不同，仍未达到稳定 25 fps。截图确认画面正常；不能把缓存充足或时钟推进当成视频已流畅，也不能仅根据配置为「硬解」就认定 HEVC 实际使用硬解。音量维持用户原来的 0，本轮不声称完成了听感验证。

## 校验与复现资料

- `tests/vod-reconnect.test.py`：提取生产重连代码，验证新播放器起始位置、直播不 seek、末尾边界、后续切台不继承进度、延迟创建和创建失败。
- `tests/hls-stream-failure.test.py`：运行生产代理请求/流转发代码，模拟中途超时、响应头写失败、响应前上游失败；验证续传字节准确、两次重试上限，以及错误 Range / ETag / 200 响应的拒绝。
- `tests/playback-progress.test.py`：既有冷启动进度识别回归。
- ARM32、ARM64 debug 构建通过。
- 原始数据在 `.codex-tmp/apple-baseline-samples.jsonl`、`apple-fixed-samples.jsonl`、`apple-*-fixed.jsonl`、`dolby-*-fixed.jsonl`，对应 `*-fixed.log`。
- 测试控制脚本 `.codex-tmp/apple-dolby-suite.py`、`.codex-tmp/apple-dolby-final-suite.py`。频道索引取自本机当时的目录，不应直接用于其他频道表。

## 黑屏回退与 10bit 提示复测（14:26–14:30）

- ARM32 / ARM64 debug 重新构建通过；仅覆盖安装正式包名 `xiao.bu.tv` 到 Android 4.4，未清数据、未安装测试组件。
- HLS HEVC → MP4 HEVC → HLS HEVC 均恢复画面；新进程 25378 日志没有 `eglCreateWindowSurface failed`。截图 `dolby-final-hls.png`、`dolby-final-mp4.png`、`dolby-final-hls-return.png` 存于 `.codex-tmp`。
- 实际视频 metadata 为 `yuv420p10le`，profile 字符串为空，仍正确识别 10bit。每个播放 request 仅记录一次 `Ten-bit hardware unavailable` 并弹出 Toast：当前设备不支持 H.265 10bit 硬解，软解播放可能卡顿。
- HLS 约 81 秒观察窗口推进至 53.2 秒，MP4 约 81 秒推进至 55.5 秒；均未重置进度/切线，但后半段仍有停顿。HLS 的 AudioFlinger 约 10 / 40 秒采样 `UndFrmCnt=0`，约 60 秒升至 385664；MP4 也有欠载。因此本轮只确认恢复显示、警告和现有音频优先策略生效，**不声称 1080p 10bit 全程音频流畅，也没有完成实际听感验证**。按用户要求不再追求 10bit 帧率或继续修改渲染路径。
- `tests/ten-bit-support.test.py` 覆盖实际 10bit 像素格式、8bit 不误报、profile 回退、HEVC Main/Main10/HDR 区分、软件解码器排除、能力缺失/异常、旧系统 API 守卫。连同 VOD 恢复、HTTP 流失败和冷启动进度回归全部通过。
- 原始采样/音频状态/日志：`.codex-tmp/dolby-audio-priority-*.jsonl`、`dolby-audio-priority-*.txt`、`dolby-audio-priority-final.log`。

## 最终边界

- 最终对照捕获到了真实续传成功：`Resumed media range offset=167725093 remaining=1417024 attempt=1`。之后仍有 SocketTimeout，因此不能承诺该海外 CDN 在本机网络上全程无缓冲。
- 旧连接复用与 Range 下载失败有关是对照实验支持的判断；没有网络抓包证明所有超时都由 TLS 复用导致。也未复现严格每 60 秒固定归零。
- 在最终版中，苹果混合源已经连续跨过一分钟并达到三分多钟。异常重连保留点播起点；网络持续不通时仍可能停在恢复位置，不能把“进度没有归零”表述成“网络中断已全部恢复”。
- 修复版已覆盖安装到这台 Android 4.4，最后切回原先的苹果 H.264/MPEG-TS 频道。频道目录和清晰度配置未修改。
