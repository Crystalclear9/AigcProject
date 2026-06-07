# Android 真机 ADB 调试教程

本文档用于在手机上安装、更新、运行和调试当前 Android App。

当前工程路径：

```powershell
E:\aigc\AigcProject\apps\android
```

当前应用包名：

```text
com.suishouban.app
```

## 1. 前置条件

电脑需要具备：

- Android SDK，当前项目已配置在 `apps/android/local.properties`：

```properties
sdk.dir=E\:\\Android\\Sdk
```

- ADB 工具路径：

```powershell
E:\Android\Sdk\platform-tools\adb.exe
```

- Gradle Wrapper，项目自带：

```powershell
E:\aigc\AigcProject\apps\android\gradlew.bat
```

手机需要开启：

- 开发者选项
- USB 调试
- 允许通过 USB 安装应用，部分国产系统需要单独打开

## 2. 手机开启 USB 调试

不同 Android 系统菜单名称略有差异，通用流程如下：

1. 打开手机 `设置`。
2. 进入 `关于手机`。
3. 连续点击 `版本号` 或 `软件版本号` 7 次，直到提示已进入开发者模式。
4. 返回设置，进入 `系统` / `更多设置` / `开发者选项`。
5. 打开 `USB 调试`。
6. 如果存在 `USB 安装`、`通过 USB 安装应用`、`USB 调试（安全设置）`，也打开。

连接电脑后，手机会弹出 RSA 指纹授权窗口，选择 `允许`。如果没有授权，ADB 可以看到设备，但无法安装或调试。

## 3. 检查设备连接

在 PowerShell 执行：

```powershell
& 'E:\Android\Sdk\platform-tools\adb.exe' devices
```

正常输出类似：

```text
List of devices attached
10AFA30A7Z002Q5    device
```

状态含义：

- `device`：连接正常。
- `unauthorized`：手机未授权，检查手机弹窗。
- `offline`：连接异常，重新插拔 USB 或重启 ADB。
- 没有设备：检查数据线、驱动、USB 模式和开发者选项。

重启 ADB：

```powershell
& 'E:\Android\Sdk\platform-tools\adb.exe' kill-server
& 'E:\Android\Sdk\platform-tools\adb.exe' start-server
& 'E:\Android\Sdk\platform-tools\adb.exe' devices
```

## 4. 编译并安装到手机

进入 Android 工程目录：

```powershell
cd E:\aigc\AigcProject\apps\android
```

安装 Debug 版：

```powershell
.\gradlew.bat app:installDebug
```

这条命令会完成：

1. 编译当前源码。
2. 生成 Debug APK。
3. 通过 ADB 安装到手机。

生成的 APK 路径：

```powershell
E:\aigc\AigcProject\apps\android\app\build\outputs\apk\debug\app-debug.apk
```

## 5. 覆盖更新规则

能否直接覆盖更新，取决于两个条件：

1. 包名一致：当前是 `com.suishouban.app`。
2. 签名一致：Debug 包必须使用同一套 debug 签名。

如果满足条件，执行：

```powershell
.\gradlew.bat app:installDebug
```

会直接覆盖旧版，通常保留 App 数据。

如果出现签名不一致：

```text
INSTALL_FAILED_UPDATE_INCOMPATIBLE
```

说明手机上的旧包和当前 APK 签名不同。处理方式：

```powershell
& 'E:\Android\Sdk\platform-tools\adb.exe' uninstall com.suishouban.app
.\gradlew.bat app:installDebug
```

注意：`uninstall` 会删除该 App 的本地数据。

## 6. 启动 App

安装后可以手动点击手机桌面图标，也可以用 ADB 启动：

```powershell
& 'E:\Android\Sdk\platform-tools\adb.exe' shell monkey -p com.suishouban.app -c android.intent.category.LAUNCHER 1
```

## 7. 查看日志

查看当前 App 相关日志：

```powershell
& 'E:\Android\Sdk\platform-tools\adb.exe' logcat | Select-String -Pattern 'suishouban|SuiShouBan|AndroidRuntime|FATAL EXCEPTION'
```

清空旧日志后再观察：

```powershell
& 'E:\Android\Sdk\platform-tools\adb.exe' logcat -c
& 'E:\Android\Sdk\platform-tools\adb.exe' logcat | Select-String -Pattern 'suishouban|SuiShouBan|AndroidRuntime|FATAL EXCEPTION'
```

如果 App 崩溃，重点看：

```text
FATAL EXCEPTION
Caused by
com.suishouban.app
```

## 8. 运行后端并让真机访问

当前 App 默认后端地址是：

```text
http://10.0.2.2:8000/
```

这个地址只适用于 Android 模拟器。真机运行时，需要把 App 设置页里的 API 地址改为电脑的局域网地址。

启动后端：

```powershell
cd E:\aigc\AigcProject\services\api
python -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

查看电脑局域网 IP：

```powershell
ipconfig
```

找到当前 Wi-Fi 或以太网网卡的 IPv4 地址，例如：

```text
192.168.1.23
```

手机 App 设置页中的 API 地址应填写：

```text
http://192.168.1.23:8000/
```

手机和电脑必须在同一个局域网。Windows 防火墙如果拦截 Python 或 8000 端口，手机会无法访问后端。

## 9. 验证后端是否可访问

电脑本机验证：

```powershell
Invoke-RestMethod http://127.0.0.1:8000/api/metrics/summary
```

局域网地址验证：

```powershell
Invoke-RestMethod http://192.168.1.23:8000/api/metrics/summary
```

把 `192.168.1.23` 替换成实际电脑 IP。

如果电脑能访问 `127.0.0.1`，但手机访问局域网 IP 失败，优先检查：

- 手机和电脑是否在同一个网络。
- 后端是否使用 `--host 0.0.0.0` 启动。
- Windows 防火墙是否放行 Python。
- API 地址是否以 `/` 结尾，例如 `http://192.168.1.23:8000/`。

## 10. 常用命令

查看设备：

```powershell
& 'E:\Android\Sdk\platform-tools\adb.exe' devices
```

安装或覆盖更新：

```powershell
cd E:\aigc\AigcProject\apps\android
.\gradlew.bat app:installDebug
```

卸载 App：

```powershell
& 'E:\Android\Sdk\platform-tools\adb.exe' uninstall com.suishouban.app
```

启动 App：

```powershell
& 'E:\Android\Sdk\platform-tools\adb.exe' shell monkey -p com.suishouban.app -c android.intent.category.LAUNCHER 1
```

查看已安装版本：

```powershell
& 'E:\Android\Sdk\platform-tools\adb.exe' shell dumpsys package com.suishouban.app | Select-String -Pattern 'versionCode|versionName|firstInstallTime|lastUpdateTime'
```

查看崩溃日志：

```powershell
& 'E:\Android\Sdk\platform-tools\adb.exe' logcat | Select-String -Pattern 'AndroidRuntime|FATAL EXCEPTION|com.suishouban.app'
```

## 11. 常见问题

### 设备显示 unauthorized

手机没有授权当前电脑。

处理：

1. 拔掉 USB。
2. 手机开发者选项中执行 `撤销 USB 调试授权`。
3. 重新插入 USB。
4. 手机弹窗选择 `允许`。
5. 重新执行 `adb devices`。

### 安装失败：INSTALL_FAILED_UPDATE_INCOMPATIBLE

手机上已有同包名但不同签名的 App。

处理：

```powershell
& 'E:\Android\Sdk\platform-tools\adb.exe' uninstall com.suishouban.app
.\gradlew.bat app:installDebug
```

### 手机访问不到后端

优先检查四项：

1. 后端是否使用 `--host 0.0.0.0`。
2. 手机和电脑是否在同一个局域网。
3. App 设置页 API 地址是否是电脑局域网 IP。
4. Windows 防火墙是否允许 Python 入站连接。

### 命令找不到 adb

直接使用完整路径：

```powershell
& 'E:\Android\Sdk\platform-tools\adb.exe' devices
```

或者把下面路径加入系统环境变量 `Path`：

```text
E:\Android\Sdk\platform-tools
```

### 安装后不是最新代码

按顺序检查：

1. 是否在 `E:\aigc\AigcProject\apps\android` 执行命令。
2. 是否执行的是 `.\gradlew.bat app:installDebug`。
3. 手机上包名是否为 `com.suishouban.app`。
4. 是否连接了多个设备；如果连接多个设备，需要指定设备号：

```powershell
& 'E:\Android\Sdk\platform-tools\adb.exe' -s 10AFA30A7Z002Q5 install -r app\build\outputs\apk\debug\app-debug.apk
```
