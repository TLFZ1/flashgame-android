# 技术笔记

记录踩过的坑，避免重复排查。

## 1. 为什么需要内置 HTTP 服务

Ruffle 解析 SWF 内部的 `loadMovie("./data/x.swf")` 时，是**相对页面 URL**解析的，
不是相对 SWF 所在的 URL。用 `file://` 或 `content://` 打开时，相对路径无法落到 SWF 同目录，
多文件游戏必然白屏。

因此 App 在 `127.0.0.1:<port>` 起一个极小 HTTP 服务，把应用私有目录当作站点根，
每个多文件游戏生成一个 `games/<name>/play.html`，SWF 与 `data/` 放在同目录下。

## 2. 设备字体（device font）

Ruffle **不读系统字体**，只在自己加载的字体库里查找。老游戏大量使用
`_明朝` / `_ゴシック` / `ＭＳ Ｐゴシック` 这类 Windows 字体名，在 Android 上必然缺失。

Ruffle 的解析分两层：

- `DefaultFont::from_name`（core/src/font.rs）只认六个名字：
  `_sans`、`_serif`、`_typewriter`、`_ゴシック`、`_等幅`、`_明朝`
- 未命中时走 `library.default_font()` 的 fallback 链，链上的名字由
  `set_default_font(DefaultFont, Vec<String>)` 设置

JS 侧的 `fontSources` 通过 `addFont` 注册字体，注册名取决于 wasm 绑定传参
（目前表现为 URL 字符串），因此 `defaultFonts` 的值未必能按"字体家族名"命中。

**实测（2026-10-24）**：三种命名方式（家族名 / URL 名 / 文件名等于家族名）都没能让
`DefineEditText` 的 `ＭＳ Ｐ明朝` 文本显示出来。桌面版 Ruffle 在装有 MS PGothic 的
Windows 上同样失败：

```
WARN ruffle_core::library: Unknown device font "ＭＳ Ｐ明朝"
ERROR ruffle_core::html::layout: Fallback font not found (JapaneseMincho) for: ＭＳ Ｐ明朝,
      text will be missing
```

**结论**：这类文本在 Ruffle 上属于上游限制，配置层解决不了。
可行路线是把 SWF 里该文本框的字体改指向**内嵌字体**（FFDec 改，逐游戏处理）。

## 3. 方向切换

用 CSS 旋转游戏元素会导致虚拟键盘方向不匹配，且页面视口不重排。
改为 `setRequestedOrientation` 让**整个 Activity** 旋转，键盘位于物理底部。
返回列表时恢复竖屏。

## 4. 全屏

`getWindow().getInsetsController()` 在 `setContentView` 之前调用会 NPE（DecorView 尚未创建）。
必须在 `setContentView` 之后调用，并 try/catch 兜底。

`setDecorFitsSystemWindows(false)` 会让内容钻到导航栏下面，导致边缘被裁；
保持 `true`，只隐藏状态栏，再用 CSS `env(safe-area-inset-*)` 兜圆角。

## 5. WebView 长按

默认长按会触发系统文本选择菜单（复制/分享/全选）。需要：

```java
web.setLongClickable(false);
web.setOnLongClickListener(v -> true);
```
配合全局 `user-select: none`。

## 7. vivo 安装确认页：绕不过去（已证伪）

实测（2026-10-24）：`pm install` 会拉起 vivo 的**「安全守护」外部来源应用**页面：

```
Flash 游戏  13.47 MB · 3.5 · 来自"未知来源"
安全守护提示您：外部来源应用（该应用来源于非 vivo 官方应用商店，未经 vivo 人工亲测）
[ ] 已了解应用的风险检测结果
[ 继续安装 ]
```

必须**勾选 + 点「继续安装」**才会真正安装，否则超时报
`INSTALL_FAILED_ABORTED: User rejected permissions`。

关闭开发者选项里的「通过 USB 验证应用」（`verifier_verify_adb_installs=0`）
**不能**去掉这个页面——它是 vivo 自己的第三方来源提示，不是 ADB 校验。

**结论：没有免确认的 adb 安装方式（除非 root）。开发时攒改动、批量安装。**

### 旧记录（已作废）

vivo（OriginOS）在 `pm install` 时会弹「是否安装此应用」，必须手动点「允许」。

试过 `verifier_verify_adb_installs=0` + `package_verifier_enable=0`（安装校验开关），
**无效**。判定方法：先 `input keyevent 26` 熄屏再装，1 秒即返回

```
Failure [INSTALL_FAILED_ABORTED: User rejected permissions]
```

熄屏时没人能点，仍然失败 ⇒ 确认框来自系统安装器本身，不是校验扫描。
（这两个 settings 已删除，恢复默认。）

结论：**没有免确认的 adb 安装方式**（除非 root）。开发时减少安装次数、批量提交改动。
不要停用 `com.vivo.safecenter`，会影响系统其它功能且未必有用。

## 6. 尝试“改 SWF 字体名”绕过设备字体（失败）

思路：把 SWF 内嵌的 `DefineFont3 "ＭＳ Ｐゴシック"` 改名为文本框请求的 `ＭＳ Ｐ明朝`，
让 Ruffle 的设备字体查找命中内嵌字形。脚本见 `tools/patch-device-font.py`
（按 tag 结构重写，已处理 RECT 头、标签长度、CWS 重压缩）。

结果（Ruffle nightly 2026-10-23 桌面版日志）：

```
WARN Unknown device font "ＭＳ Ｐ明朝" (bold: true)     ← 改名后仍然未知
ERROR Fallback font not found (JapaneseMincho) for: ＭＳ Ｐ明朝, bold
      text will be missing
```

说明：**SWF 内嵌字体不会进入 Ruffle 的 device_fonts 注册表**，改名解决不了；
通过 `fontSources` 加载的 TTF 同样没有进入该注册表（三种命名都试过，见 §2）。

结论：这条路径无效，不要重复尝试。真要走通，只能改 Ruffle 核心，或者把文本框
从“设备字体”改成“引用内嵌字体 ID”——后者要重写 DefineEditText 标签，风险大。

## 8. 外部存储的页面副本：标记文件必须跟版本走

`installAssets()` 会把 `index.html / ruffle.js / *.wasm / 字体` 拷到 `getExternalFilesDir(null)`，
再用一个标记文件判断"拷过没有"。标记名原本是写死的常量（`.assets_v30` 这种）——
**装回旧版本时那个旧标记还在，直接 return，页面仍是上一版的**。

实测症状：装回 3.9 后顶栏还是 4.9 的样子（多出 ▣/⚙），`dumpsys package` 明明显示 versionName=3.9，
页面里却还有 `padCfgBtn`。差点被误判成"改动没生效"。

已修：标记名在运行时取 versionCode

    ".assets_v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionCode

**别再用写死的常量。**

## 9. vivo 禁止降级安装：改 versionCode + 同签名重签

`adb install -d` 和 `pm install -r -d` 都会被拒：
`INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected`（vivo 连 `-d` 都不认）。

保留数据、不卸载的绕法：
1. 改 APK 内二进制 `AndroidManifest.xml` 的 versionCode（只改 4 字节值，长度不变，不用重排）；
2. `zipalign -p -f 4` → `apksigner sign`，**必须用同一把 debug.keystore**，否则签名不符；
3. `adb install -r` 就变成"升级"，数据全保留。

注意：改完版本号是假的（例如把 3.9 改成 41），此后任何真版本都必须大于它，否则又被拦。

## 10. 圆形按钮里的字形要显式居中

`#showBtn` 是个固定 36×36 的 `div`，只设了宽高和 `border-radius`，字形会按块级默认排在左上，
视觉上明显偏出圆圈。加 `display:flex; align-items:center; justify-content:center`
（并且显示时的规则要一起从 `display:block` 改成 `display:flex`）才对齐。

## 11. 输入法弹出时的画面处理（沉浸式全屏的坑）

**问题**：游戏里的文本框唤起系统输入法后，键盘盖住下半屏，innerHeight 纹丝不动（764 不变）。

**为什么**：
- 界面是沉浸式全屏（setDecorFitsSystemWindows(false) + 隐藏状态栏/导航栏），
  windowSoftInputMode=adjustResize 在这种模式下不生效，窗口不跟随键盘。
- 给 WebView 设 setPadding(bottom=ime) 也不改变布局视口，innerHeight 照样不变（实测）。

**有效做法**（已验证：innerHeight 764 → 437，失焦恢复 764）：
1. web.setOnApplyWindowInsetsListener 里取 insets.getInsets(WindowInsets.Type.ime()).bottom；
   沉浸式下这个 inset 是会分发的（日志实测拿到 1142px）；
2. 用它改 WebView 的 LayoutParams.height = 根高度 − ime，0 时恢复 MATCH_PARENT；
3. manifest 里同时写 windowSoftInputMode="adjustResize|stateHidden"（与 Swiff 一致）。

**同赛道参照**：Swiff v1.4.1 主 Activity 就是 0x12（adjustResize|stateHidden）；
PPSSPP / Dolphin / RetroArch / ruffle-android 都没设（游戏时不需要系统键盘）。

**另一条坑**：vivo 上 adb install 会卡在"外部来源应用"确认页（勾选 + 继续安装）；
流式安装还会验签失败（INSTALL_PARSE_FAILED_NO_CERTIFICATES），必须 --no-streaming。

**最终决定（2026-10-24）**：**不做自动让位**。理由：Flash 游戏里的文字输入只是
“输个名字 / 输个秘籍”，都是短输入，看不见过程也能接受；而自动让位在横屏下会把
画面压成一条（横屏高度只有 360dp，键盘吃掉 ~150dp），收益远小于代价。
现在的行为 = 系统键盘直接盖住下半屏（和 PPSSPP/Dolphin/RetroArch 一致）。
上面那段实现方法保留备查 —— 万一以后有“必须边看边打字”的需求，按它做即可。

## 12. exe 打包的 Flash 游戏：三种形态与提取策略

**背景**：浏览器早就不支持 Flash，作者发行时要么给 SWF、要么打 exe（两行命令就能打）。

| 形态 | 结构 | 支持情况 |
|---|---|---|
| **A. 单文件 projector** | 播放器.exe + 尾部追加 **1 个** SWF | 已支持：扫全文件取最大的 SWF |
| **B. exe + 同目录资源** | game.exe + data/*.swf + sound/ 在旁边 | 3.9.5 起支持：扫描目录时把主 SWF 提到同目录 |
| **C. 全部塞进 exe** | SWF Studio / Zinc 等打包器，自定义格式多半还压缩 | 无解，只能提示 |

**关键事实（实测）**：
- Adobe projector 的 exe 里除主游戏外，还带播放器自带的小 SWF（设置面板 showSettings、
  存储提示页 storageError 等，2KB~86KB）→ 不能“全提取”，否则游戏库里会多出一堆假游戏，
  取最大在 A 形态下是对的；
- exe 里没有文件名/路径信息（PE 资源表只有图标/菜单/版本，SWF 是裸数据块）→ 对 C 形态，
  即使全提出来也还原不了 loadMovie("data/x.swf") 的路径。

**形态 B 的实现**（3.9.5）：rebuildGamesJson() 扫到目录时，若顶层没有 .swf 但有 .exe，
就用 extractSwfFromFile() 把主 SWF 提到同目录，随后 pickMainSwf() 自然能选到它。
实测：某个 exe+data 夹具（只有 exe + data/）→ 自动提取 → md5 与原件一致 → 游戏正常加载 data/、无 404。

**造测试夹具**（本机有真播放器 <tools>/flashplayer32/flashplayer_32_sa.exe）：
把播放器字节 + SWF 字节直接拼接写成一个 .exe 即可，结构就是标准 projector。





## 13. 设备字体文字丢失（tofu）的真正根因与修复（2026-10-25）

§2/§6 的结论是**错的**，这里更正。

**现象**：老游戏里用 `ＭＳ Ｐ明朝` / `ＭＳ Ｐゴシック` 这类**设备字体**的文本显示成 □□□，
控制台报 `Fallback font not found (Sans|JapaneseMincho) for: ＭＳ Ｐゴシック`。

**真正原因有两个，都在我们自己的配置里**：

1. **`defaultFonts` 的 key 必须是 Ruffle 的 6 个标识符**
   `web/packages/core/src/internal/player/inner.tsx:734` 把 key 原样传给 wasm，
   Rust 侧 `web/src/builder.rs:set_default_font` 只认
   `sans / serif / typewriter / japaneseGothic / japaneseGothicMono / japaneseMincho`，
   其它一律 `_ => return` **静默丢弃**。
   我们原来写的是 Flash 字体名（`_sans`、`_明朝`、`ＭＳ Ｐゴシック`…）→ 一条都没生效。

2. **`defaultFonts` 的 value 必须是字体的 FULL_NAME（name 表 nameID=4）**
   `web/src/builder.rs:register_ttf_face_by_name` 优先用字体自带的全名注册，URL 只在字体没有全名时才用：

   ```rust
   let name = full_name.unwrap_or(url);
   player.register_device_font(FontDefinition::FontFile { name, ... });
   ```

   我们的 Noto 字体全名是 **`Noto Sans CJK JP Regular`**（带 " Regular"），
   原来写的是路径 `../../Noto Sans CJK JP` → 名字对不上 → 找不到回退字体。

**修复**（`app/assets/index.html`、`app/assets/play_template.html`）：

```js
fontSources: ['Noto Sans CJK JP'],
defaultFonts: {
  sans: ['Noto Sans CJK JP Regular', 'Noto Sans CJK JP'],
  serif: [...], typewriter: [...],
  japaneseGothic: [...], japaneseGothicMono: [...], japaneseMincho: [...]
}
```

值给两个：全名优先，路径兜底（万一换了字体文件）。

**验证方法（PC 上，不用手机）**：`<repo>/ruffle-test/` 下有 `shot.js` / `probe.js`
（CDP 截图 / 抓 console），`python -m http.server 8099` 起服务，headless Chrome 打开页面即可对比。
判据：控制台无 `Fallback font not found`，且日文说明能显示（旧配置是 □）。

**上游建议**：Ruffle 对未知 `defaultFonts` key 完全静默，建议提 issue/PR 加一条 warn —— 这不需要 fork。
## 14. 文字仍被裁：行高不匹配 → 衍生字体（2026-10-25，1.0.2）

§13 修好了“完全没有字”，但有的游戏**末段文字被裁掉**（例：某游戏 A 正文 5 段只显示 3 段）。

**根因是行高，不是字宽**：
- 横向折行两边一致（都是 17 字/行），所以不是字宽问题；
- Ruffle 用**字体自带**的 ascent/descent 算行高：Noto CJK = 1.448 em，
  而 Flash 时代的 MS Gothic / MS PGothic = 1.0 em；同一文本框 Ruffle 少放约 3 行 → 末段被裁。

**验证**（PC 上 `ruffle-test/`，同一游戏同一页面）：
| 字体 | 显示段数 |
|---|---|
| Noto Sans CJK JP（原） | 3 |
| 真 MS Gothic（Windows 自带） | 4 |
| 衍生字体 0.88/0.12 | 4 |
| **衍生字体 0.84/0.10** | **5（与 Flash Player 一致）** |

**做法**：`tools/make-font.py` 从 Noto Sans CJK JP 生成 `app/assets/FlashGameCJK.ttf`：
把 hhea / OS2 的 ascent/descent 改成 0.84 / 0.10 em（合计 0.94 em），并改名
`Flash Game CJK / Flash Game CJK Regular`（OFL 要求改名；版权声明随 LICENSES 保留）。
字体全名用于 `defaultFonts` 的 value。

**权衡**：行距比原版紧一点；对所有设备文本生效。比“末段被裁”可接受得多。

**根本方案**（未做）：Flash 用 SWF 自己的 leading，Ruffle 用字体度量；要完全对齐需改 Ruffle 的
`core/src/html/layout.rs` 行高计算，需要 Rust + wasm 工具链重建 —— 目前用衍生字体绕开。