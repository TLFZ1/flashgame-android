# Flash 游戏 (FlashGame)

Android 上的离线 Flash 播放器。基于 [Ruffle](https://ruffle.rs)（Rust → WebAssembly），
内置一个本地 HTTP 服务，用来解决多文件 SWF（通过 `loadMovie` 加载外部 `data/*.swf`）无法在
SAF/文件选择器下加载的问题。

**本仓库不包含任何游戏内容。**

## 特性

- 离线运行，不需要网络、不需要安装 Flash、不需要单独的 Ruffle
- 单文件 SWF 直接播放；带 `data/` 的多文件游戏自动识别并生成播放页
- 表格化管理：导入（文件 / 文件夹）、重命名、删除
- 播放控制：横竖屏切换、缩放、拖动模式、隐藏界面、虚拟键盘（12 键，可改键）
- 导入 `.exe`（Flash Projector）时自动提取内嵌的 SWF

## 目录结构

```
app/
  AndroidManifest.xml
  src/com/dsh/flashserver/     Java 源码（MainActivity / Handler）
  res/                         图标资源
  assets/                      网页（index.html、play_template.html）
tools/
  fetch-deps.sh                下载 Ruffle web 包与字体
  build.sh                     无 Gradle 打包脚本
docs/
  NOTES.md                     技术笔记（踩过的坑）
```

Ruffle 的 `ruffle.js`、`*.wasm` 和字体体积较大，**不纳入版本库**，用 `tools/fetch-deps.sh` 获取。

## 构建

需要：

- JDK 17
- Android build-tools 34（aapt2 / d8 / zipalign / apksigner）
- Android platform 34（android.jar）

```bash
./tools/fetch-deps.sh        # 下载 Ruffle + 字体到 app/assets
./tools/build.sh             # 输出 build/FlashServer.apk
```

没有使用 Gradle：直接 `aapt2 link` → `d8` → `zipalign` → `apksigner`。

## 版本管理

远端：`git@github.com:TLFZ1/flashgame-android.git`（私有）

本机走 SSH over 443，因为 TUN 模式把 22 端口劫持到了 fake-ip：

```
~/.ssh/config
Host github.com
  HostName ssh.github.com
  Port 443
  User git
```

```bash
git push -u origin main
```

## 许可

| 组件 | 许可 |
|---|---|
| 本项目代码 | MIT（见 [LICENSE](LICENSE)） |
| [Ruffle](https://github.com/ruffle-rs/ruffle) | MIT OR Apache-2.0 |
| [Noto Sans JP](https://fonts.google.com/noto/specimen/Noto+Sans+JP) | SIL OFL 1.1 |

Ruffle 的许可文件随 `tools/fetch-deps.sh` 一起落盘到 `app/assets/LICENSES/`，
打包时会进入 APK。再分发时请保留它们。

**本项目不包含、也不分发任何游戏内容。** 游戏版权归各自作者所有。

Ruffle 为独立开源项目，与本项目无隶属关系。

## 相关工具（不随 App 分发）

[`tools/phone-files/`](tools/phone-files/) —— 电脑端小工具：用浏览器管理手机文件（走**无线 adb**）。

- 能访问 `Android/data/`（MTP 在 Android 11+ 做不到），适合整目录传游戏、备份存档
- 支持上传整个文件夹（保留目录结构）、断线自动重连
- **不在 `app/assets` 里，不会被装进 APK**；需要电脑有 Python 3，详见该目录的 README

## 开发方式

**本项目由 vibe coding 生成**（AI 辅助编码，需求与验收由作者负责）。这是业余项目，无维护承诺；如有纰漏，请见谅。
