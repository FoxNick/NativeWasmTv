# CCTV1 首次启动排查

用户现象：清数据首次启动时 CCTV1 两条线路失败，自动跳 CCTV2；随后手动返回 CCTV1 能播放。

## 实机复现

使用本地发布产物 `output/nTv.apk`，版本 1.7.0 / 7，SHA-256 为
`1d1ad76b7fa33b25072fffd57f5e992ee4cb811e11c71514ea64212e58bfcc1b`。
测试通过 ADB 清除原包名 `xiao.bu.tv` 数据后启动，没有安装测试组件。

| 设备 | 发布包清数据测试 | 主界面初始化至首帧 |
| --- | --- | --- |
| 小米 6 / Android 7.1.1 | 2 次，CCTV1 第一线路成功，未跳台 | 9.9 秒、9.9 秒 |
| Android 4.4.4 | 2 次，CCTV1 第一线路成功，未跳台 | 9.7 秒、8.0 秒 |

没有复现用户的完整故障链，不能认定网络、鉴权或解码器中的某一项是原始原因。

## 确认的代码问题

`onPrepared` 将 `lastPlaybackPosition` 初始化为 -1；`refreshVideoInfo` 原先把首次位置读数
（包括 0）当作播放进展，立即设置 `playbackProgressObserved=true`。
这会让尚未真正播放的启动过程满足播放中断恢复的条件。

`recoverStalledPlayback` 会重试当前线路最多 5 次，耗尽后尝试备用线路，再耗尽后跳到下一频道。
这是独立于“关闭 / 5 秒 / 10 秒”超时换线设置的恢复路径。

修复：第一次有效读数仅建立基准；后续读数变化才标记播放进展。保留直播窗口时间回退也算进展的逻辑。
`tests/playback-progress.test.py` 执行从生产代码提取的时钟判断，验证首个零/非零读数、真实进展、
无效读数、直播窗口回退及恢复计数重置。相同场景在旧分支失败，修复后通过。

修复后两台设备各重新清数据启动一次，CCTV1 第一线路成功，约 5.8 秒和 8.7 秒出首帧。
此结果验证修复未破坏首次播放，不代表用户原始故障已经完整复现。
随后两台设备手动 CCTV1 → CCTV2 → CCTV1，均记录到 CCTV2 和返回 CCTV1 的硬解首帧，未出现恢复跳台。

实机原始日志保存在 `.codex-tmp/cctv-cold-release.log`、`.codex-tmp/cctv-cold-44-release.log`
和 `.codex-tmp/cctv-first-start/`。
