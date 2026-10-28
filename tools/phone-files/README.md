# 手机文件管理小工具（phone-files）

> **这不是 App 的一部分，不会被打包进 APK。** 这是一个独立的电脑端小工具，
> 想用就下载/复制，不想用删掉即可，不影响手机上的 Flash 播放器。

在电脑浏览器里**像文件管理器一样管理手机文件**：浏览、上传、下载、改名、删除、
上传整个文件夹（保留目录结构）。

典型用途：

- 把游戏（含 `data/` 子目录）整目录传到手机
- 备份/恢复 `Android/data/com.dsh.flashserver/files/games/` 里的存档和游戏
- 手机 MTP 不好使时（USB 接触不良、或 `Android/data` 被系统隐藏）照样能传文件

## 为什么不用 MTP

|  | MTP（资源管理器） | 本工具 |
|---|---|---|
| 传输方式 | USB | **Wi-Fi（无线 adb）** |
| 能看到 `Android/data/` | ❌ Android 11+ 被系统屏蔽 | ✅ |
| USB 线不稳时 | ❌ 卡死 | ✅ 不受影响 |
| 需要装 App | 不用 | 不用（但电脑需要 Python） |

## 准备

1. 电脑装 **Python 3**（安装时勾选 *Add Python to PATH*）
2. 手机打开 **开发者选项 → 无线调试**
   - 首次建议先用 USB 连一次电脑，执行 `adb tcpip 5555`，之后就不需要线了
3. 手机和电脑在同一个 WiFi
4. adb 不用手动配：脚本会依次找 `同目录/platform-tools/`、常见安装路径、`PATH`，
   也可以用环境变量 `ADB_PATH` 指定

## 用法

1. 在脚本同目录建 `phone.txt`，第一行写手机地址：
   ```
   192.168.1.23:5555
   ```
   （也可以设环境变量 `PHONE_DEV`）
2. 双击 `phone-files.bat`（或运行 `python phone-files.py`）
3. 浏览器打开 <http://127.0.0.1:8100>
4. 关掉窗口 = 停止服务

## 特性

- **断线自动重连**：操作前检查、后台每 20 秒保活、页面右上角有状态灯
- 上传整个文件夹（保留目录结构）
- 只监听 `127.0.0.1`，局域网内其他设备访问不到

## 给别人用的便携包

把下面这些打成 zip：

```
phone-files.bat
phone-files.py
phone.txt          (你的手机地址，或留空让对方填)
README.md
platform-tools/    (adb，Google 官方，Apache 2.0)
```

对方解压后：**装 Python → 改 `phone.txt` → 双击 bat**。
## 不会填手机地址？

打包给别人时，建议连脚本一起放下面两个助手：

| 文件 | 作用 |
|---|---|
| `adb-tcpip.bat` | 有 USB 线时用：插线点一次，把手机切成 Wi-Fi 调试（之后 `IP:5555` 固定） |
| `pair.bat` | 没有 USB 线时用：用手机「无线调试 → 使用配对码配对设备」显示的 IP:端口 和 6 位码配对 |

`phone-files.bat` 第一次运行会自动问手机地址，并写进 `phone.txt`，不需要手动编辑文本文件。
另外还附带 `使用说明.txt`（中文图文步骤）。

## 用命令行跑 adb

包里自带 `adb.cmd`（指向内置的 `platform-tools\adb.exe`）：

```cmd
adb.cmd devices
adb.cmd install xxx.apk
adb.cmd shell pm list packages
```

想直接敲 `adb`？把本文件夹加进 PATH 即可（不加也行，用 `adb.cmd` 一样）。
