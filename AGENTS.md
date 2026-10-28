# 项目约定（Flash Game Android）

面向后续会话/AI 的落地约定，比全局规则更具体。冲突时以本文件为准。

## 目录布局

```
<repo>/
├── repo/         git 主仓（main 分支 = 当前发布版）
├── worktree/     git worktree（pad39 分支，日常构建在这里做）
├── dist/         对外 APK：顶层只放当前版本，历史包进 dist/archive/
└── toolchain/    JDK 17 + Android build-tools 34 + platform 34 + debug.keystore + 测试夹具
                  （原位于另一个目录，2026-10-24 迁移过来）
```

**通用工具不要塞进来**（`<tools>` 的 adb、`<scripts>` 的脚本属于全机共用）。

## 构建

无 Gradle，直接 aapt2 → d8 → zipalign → apksigner：

```bash
cd <repo>/worktree
export JAVA_HOME="<repo>/toolchain/jdk-17.0.12+7"
export BUILD_TOOLS="<repo>/toolchain/android-14"
export ANDROID_JAR="<repo>/toolchain/platform/android-34/android.jar"
export KEYSTORE="<repo>/toolchain/app/debug.keystore"
bash tools/build.sh            # → build/FlashServer.apk
```

**只装到自己手機试**（versionName 不动，只提高 versionCode）：

```bash
VERSION_CODE=57 bash tools/build.sh
```

版本号覆盖不写回 manifest（aapt2 的 `--replace-version`），git 里不留噪音。

## 装机

USB 传大文件会挂，一律走 Wi-Fi（见全局 AGENTS）。

```bash
adb connect <phone-ip>:5555
adb -s <phone-ip>:5555 install --no-streaming -r build/FlashServer.apk
```

- `--no-streaming` 必加，否则 `INSTALL_PARSE_FAILED_NO_CERTIFICATES`
- vivo 会弹「外部来源应用」确认页：勾选 + 继续安装（1260x2800 下坐标约 376,2421 → 657,2574）
- 手机上 `svc power stayon true` 只对充电有效；Wi-Fi 调试时屏幕会睡，操作前先 `input keyevent KEYCODE_WAKEUP`

## 发版

1. 改 `app/AndroidManifest.xml` 的 versionName（versionCode 继续 +1）
2. 构建并装真机验证
3. `git tag -a vX.Y.Z -m "..."` → 推 tag
4. APK 复制到 `<repo>/dist/FlashGameAndroid-X.Y.Z.apk`
5. **必须校验**：`aapt dump badging <apk> | grep versionCode`，确认版本号与文件名一致
   （2026-10-24 踩过：cp 时工作目录在 repo，把 repo 里 v4.9 的旧产物拷成了 1.0.0）
6. 旧的移进 `dist/archive/`
7. 更新 `CHANGELOG.md`

## 代码结构

| 位置 | 内容 |
|---|---|
| `app/src/com/dsh/flashserver/MainActivity.java` | 主界面、导入、提取、本地 HTTP 服务 |
| `app/src/com/dsh/flashserver/Handler.java` | HTTP 静态文件服务（只绑 127.0.0.1） |
| `app/assets/index.html` | 游戏列表页（含虚拟键盘、设置面板） |
| `app/assets/ruffle.js` + `*.wasm` | Ruffle 引擎（**不入 git**，`tools/fetch-deps.sh` 拉取） |
| `docs/NOTES.md` | 踩坑记录（§8~§12 是最近的） |
| `docs/handover/` | 每次会话的交接文档，取日期最新的 |

## 手机文件管理（不用 USB）

独立小工具，**不在 `app/assets` 里，绝不会进 APK**：`tools/phone-files/`（含 README）。

双击 `tools/phone-files/phone-files.bat`（或根目录的 shim `<repo>/phone-files.bat`）
→ 本地起网页 http://127.0.0.1:8100 （只绑 127.0.0.1）→ 浏览/上传/下载/改名/删除手机文件。
底层走无线 adb，**能访问 `Android/data`**（MTP 不能）；支持「上传整个文件夹」保留目录结构；
断线自动重连（每 20s 保活 + 页面状态灯）。

## 已知限制

- **exe 形态 C**（所有游戏文件被打包器塞进 exe）无解 —— exe 里没有文件名信息，提出来也拼不回 `loadMovie("data/x.swf")` 的路径。只支持形态 A（单文件）和 B（exe + 同目录 data/）。
- 若主 SWF 比播放器自带资源还小（<86KB），"取最大"可能选错；改进方向：优先取结束位置最接近文件末尾的候选。
- 包名仍是 `com.dsh.flashserver`，debug 签名；上架前需要改。

## 验证手段

- 提取是否成功：`adb logcat -s flashserver:I | grep -E 'extractSwf|formB'`
- 造 exe 测试夹具：本机有真播放器 `<tools>/flashplayer32/flashplayer_32_sa.exe`，
  把它和 SWF 的字节直接拼接即得到标准 projector
