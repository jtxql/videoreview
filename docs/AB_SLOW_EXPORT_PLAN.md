# A/B慢动作导出实施建议

状态：2026-11-02，用户已确认独立Media3导出方案及依赖下载，要求先提交现有工作，再实施导出；本检查点尚未实现导出。用户已确认：按当前播放倍速生成慢动作视频，而非仅原速截取。

## 已确认事实

- 当前工程只有:app和:player-mpv；PlayerController暴露播放控制，A/B在LoopPoints中，尚无视频导出接口。已有“导出播放器日志”不是视频导出。
- libmpv依赖记录中虽列出FFmpeg，不代表已有可直接调用的FFmpeg导出SDK/可执行文件；本次未确认该预编译组合的编码能力。
- 当前常规Gradle缓存未发现androidx.media3组目录；引入建议组件需要下载新的Maven依赖，体积及传递依赖尚未实测。
- [官方Transformer指南](https://developer.android.com/media/media3/transformer)说明基于Android MediaCodec及OpenGL处理；[裁剪/格式配置](https://developer.android.com/media/media3/transformer/transformations)支持按时间裁剪并配置H.264/AAC。
- [EditedMediaItem.Builder](https://developer.android.com/reference/androidx/media3/transformer/EditedMediaItem.Builder)提供setSpeed；[官方发布页](https://developer.android.com/jetpack/androidx/releases/media3)目前标注稳定版1.11.1。具体AAR构建约束与本项目兼容性尚未下载核验。

## 推荐方式（待确认）

增加独立:video-export库模块，候选使用Media3 Transformer 1.11.1及必要同版本依赖；mpv继续负责播放，007 USB层不变。不将播放器替换为ExoPlayer，不新增手写JNI/NDK或自编译FFmpeg，不自动升级当前AGP/Gradle/Kotlin。若依赖不兼容，报告具体约束再决定。

为什么不先做简单无损截取：用户需要将慢动作写入成片时间轴；仅复制压缩数据的快剪不能直接保证变速、音频时间和任意A/B边界。优先验证官方导出组件，避免首次就维护自有编解码流水线。

## 首版交互与行为

1. 新增独立“导出A/B慢动作”屏幕按钮，A/B有效时可用；不要求AB循环开着，不添加TourBox导出按键映射。
2. 点击时冻结当前视频、播放器会话、A/B源时间戳及核心实际倍速。确认框显示区间、倍速、预期成片时长和声音选项。后续改速/改标点不能偷偷改变已经开始的任务。
3. 成片理论时长=(B−A)/speed：10秒区间在0.25倍速为约40秒，0.1倍速为约100秒；实际容器时长可能受帧和音频包边界影响。
4. 建议导出MP4（H.264视频，有声时AAC），保留源分辨率为优先目标；不静默降画质、丢音轨或宣称所有源格式可导出。默认保留音频并同步变速，确认框提供静音选项；首版音调与同步实际表现需验证，不承诺恒音调。
5. 导出时暂停播放、暂时停止播放器输入分发，USB原始接收仍按原流程运行；屏幕显示进度/取消。同一时刻只允许一个导出任务，不在UI线程处理媒体或复制大文件。
6. 前台导出到应用临时文件，成功后通过系统保存选择器另存新文件，不覆盖原视频；保存取消时明确提示尚未另存，临时片段可保留供重试。首版不做常驻后台服务，编码中进入后台则安全取消，清理半成品。
7. 系统保存选择器可能使Activity后台并按既有规则停止USB；返回后的自动连接行为保留。保存的是冻结的独立临时片段，不从已经释放的播放会话重新取A/B。

## 预计影响文件

- 新增android/video-export/的构建文件、导出请求/结果模型、任务状态机、Media3适配器与纯逻辑测试。
- android/settings.gradle.kts、app/build.gradle.kts：注册模块及宿主依赖，统一固定Media3版本。
- PlayerApi/MpvPlayerController：提供受会话约束的导出参数快照/实际速度读取；不改变USB协议、逐帧或AB控制规则。
- PlayerPanel及必要宿主生命周期接线：确认、进度、取消、另存、暂停输入分发与清理。导出模式下不自动重新加载播放器去争用编解码器。
- README、ROADMAP、CHANGELOG：实际能力、依赖和验证记录。

## 验证与限制

- 无设备测试：无效A/B/速度、时长计算、重复启动、旧会话、取消/完成竞态、后台、失败清理及保存结果；继续回归原测试/Debug/Lint。
- 真机先测短SDR H.264文件和五档速度；检查开始/结束画面、实际成片时长、音画同步、方向元数据、保存与取消。支持API本身不等于已达到逐帧裁剪精度。
- 0.1倍速会放大成片时长和存储需求；不凭空插帧，不保证耗时或文件大小。缓存不足、读权限失效和编码器不足需明确失败，不自动无限重试。
- [官方格式限制](https://developer.android.com/media/media3/transformer/supported-formats)指出编码依赖设备MediaCodec；4K、高帧率、HDR/杜比视界与播放器共占编解码资源需专项验收。mpv能播放不等于Transformer能解码/导出。
- 保存输出不能用进度100%代替文件复制/关闭成功；失败不能留下看似成功的最终成片。未经过真机验证前，不声称慢动作成片、音频或精度已通过。

本轮未修改Android代码、下载依赖、编译、安装APK、访问手机/USB、暂存或提交Git。仅新增本方案并将路线图/变更记录标为待确认，保留全部已有修改和抓包。
