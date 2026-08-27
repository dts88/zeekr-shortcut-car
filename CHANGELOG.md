# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [0.1.0-alpha] - 2026-08-27

首个版本。以 EVCam `0876b97`（GPL-3.0）为代码基座，新增极氪合成流支持。

### 新增

- **极氪7X 车型档案**：车机只提供一路四联合成流，摄像头数量按 1 处理。
- **合成流几何拆分** `CompositeStreamGeometry`：把 `1280x5140` / `5120x1280` 等
  合成帧按真实几何拆成四个方形画面，正确处理 5 条 4px 分隔带；
  排布不匹配时安全回退为四等分。纯 Java 实现，含 20+ 条 JVM 单元测试。
- **GL 四宫格视图** `CompositeTextureView`：继承 `AutoFitTextureView`，
  重写 `getSurfaceTexture()` 返回自建 OES 外部纹理，因此**无需改动上游相机管线**
  即可把长条合成流渲染成 2x2 四宫格。支持 FIT/FILL 缩放、单画面聚焦、画面排列自定义。
- **按能力查找相机** `ZeekrCameraLocator`：遍历所有 Camera2 设备，
  选出真正声明了合成流尺寸的那一路，不再写死摄像头下标。
- **合成流车型档案** `ZeekrCompositeProfile`：已知尺寸优先、码率推荐；
  绝不臆造 HAL 未声明的分辨率。
- **「关于与致谢」页面**：应用内完整说明两个功能来源、各自许可证与使用边界。
  入口在侧边菜单。
- **极氪专用布局** `activity_main_zeekr_7x.xml`：四宫格 + 四象限角标 +
  四宫格/单画面切换按钮 + 合成流识别结果诊断条。
- **GitHub Actions CI**：推送即跑单元测试并构建 APK；打 `v*` tag 自动发布 Release。

### 变更

- 应用名改为 `Zeekr Shortcut`，包名改为 `io.github.dts88.zeekrshortcut`，
  版本号从 `0.1.0-alpha` 重新起算（EVCam 基座版本为 1.6.6）。
- 签名密钥可通过环境变量覆盖（`ZEEKR_KEYSTORE` 等），默认仍用仓库内的公开测试密钥。
- 启用 `unitTests.isReturnDefaultValues`，使纯逻辑单元测试可在 JVM 上直接运行。
- 上游 EVCam 的 `README.md` / `CLAUDE.md` / `AGENTS.md` / `免责声明.md` 移入 `docs/`，
  避免与本项目文档混淆。

### 移除

- `assets/logo.png`、`assets/donate.jpg`：未被代码引用，移出 APK 以减小体积
  （logo 移至 `docs/evcam-upstream-logo.png` 供文档使用）。

### 说明

- 本版本**未在实车上验证**。自动化测试只覆盖纯逻辑部分；
  GL 渲染、相机取流与录制链路需要实车验证。首次使用请在静止车辆上测试。
