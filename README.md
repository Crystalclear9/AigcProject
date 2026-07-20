# 随手办

随手办是一个“截图到行动”的移动产品：用户截图后，App 先在手机端完成 OCR、噪声过滤和行动证据判断。只有识别到明确的待办、截止、会议、报名或承诺信号时，才给出低打扰提示；用户确认后才创建行动卡、注册提醒或同步日历。

产品默认可以在手机侧独立运行。云端 AI 是可选增强，通过后端 Workflow 网关接入；Android 不直连 vivo/蓝心 provider，也不把 API key 写入 APK。

## 目录

- [代码结构](#代码结构)
- [完整使用流程](#完整使用流程)
- [后端 Workflow 网关](#后端-workflow-网关)
- [Android 构建与运行](#android-构建与运行)
- [APK 调试流程](#apk-调试流程)
- [真实设备与云真机验收](#真实设备与云真机验收)
- [核心代码位置](#核心代码位置)
- [常见问题](#常见问题)

## 用户路径

```text
截图 / 相册 / 分享 / 粘贴文字
  -> 端侧 ML Kit OCR 与噪声清洗
  -> 行动证据判定
  -> 无明确行动：静默忽略
  -> 有明确行动：低打扰“可能有待办”通知
  -> 用户点击“查看”：顶部小窗展示候选，用户再决定是否生成草稿
  -> 本地规则先出草稿，云端 Workflow 可异步增强
  -> 用户选择、编辑、确认
  -> 保存 Room 行动卡，注册 WorkManager 截止提醒，可选写入系统日历
```

确认前不会写入最终卡片、不会注册提醒、不会写日历。云端增强只补字段、追加建议或更新证据，不覆盖用户锁定字段。

## 产品原则

- **端侧优先**：OCR、截图 gate、本地规则、多任务拆卡、Room 卡片和提醒均可在手机侧完成。
- **少打扰**：通知采用静默紧凑样式；同一截图被忽略后短时间内不重复提示。
- **证据驱动**：候选卡展示标题、时间、地点/平台、材料/提交方式、证据摘要和置信度。
- **用户确认优先**：保存、提醒、日历写入都必须由用户确认触发。
- **云端可插拔**：vivo/蓝心 provider 只由后端代理调用，Android 只保存 Workflow HTTPS 网关 URL。
- **可降级**：无网、模型失败、OCR 失败时保留本地规则和手动补全入口。

## 代码结构

```text
apps/android/                         Android Compose 客户端
  app/src/main/java/com/suishouban/app/
    data/                              Room、Repository、远程 DTO/API
    domain/                            本地规则抽取、OCR 清洗、截图 gate
    ocr/                               ML Kit OCR
    reminder/                          截图监听、通知、WorkManager 提醒
    ui/                                Compose 页面与组件

services/api/                          FastAPI + LangGraph Workflow 网关
docs/                                  架构、接口资料、测试指南和报告
scripts/                               构建、部署、真实设备验收脚本
.github/workflows/                     CI
```

## 完整使用流程

### 1. 准备环境

Windows PowerShell 环境下建议准备：

- Python 3.11 或兼容版本。
- Android Studio。
- Android SDK Platform 35、Build-Tools 35、Platform-Tools。
- JDK 17 或 Android Studio 自带 JDK。
- 可选：一台开启 USB 调试的 Android 真机，或 vivo 云真机设备。

确认当前仓库在项目根目录：

```powershell
cd AigcProject
git status --short --branch
```

确认 Android SDK 可用。若 SDK 不在默认位置，构建脚本可以显式传入 `-SdkPath`：

```powershell
.\scripts\build_android_debug.ps1 -SdkPath "<Android SDK 路径>"
```

### 2. 本地端侧闭环运行

只验证 Android 端侧能力时，不需要后端、不需要模型 key。

默认设置：

```text
服务地址 = 空
AI 增强 = 关闭
```

此时 App 不访问 `127.0.0.1`、`10.0.2.2`、局域网 IP 或 `api-ai.vivo.com.cn` 原始 provider endpoint。截图识别、行动判断、候选卡、保存和提醒均走手机端闭环。

### 3. 启用云端 AI 增强

需要先启动或部署后端 Workflow 网关，再在 App 设置页填写手机可访问的 HTTPS 地址：

```text
https://your-workflow-gateway.example.com/
```

本地开发机上的 `http://127.0.0.1:8000/` 只代表电脑本机。物理 Android 真机访问 `127.0.0.1` 时指向手机自身，不会自动访问电脑。

## 后端 Workflow 网关

后端位于 `services/api`，负责：

- 文本/截图 Workflow 编排。
- vivo/蓝心 chat provider 服务端代理。
- vivo OCR 与图片生成代理。
- provider telemetry、health/readiness、指标和脱敏 probe。
- 旧版分析接口兼容和行动卡管理。

### 1. 安装依赖

```powershell
.\scripts\setup_backend.ps1
```

等价手动流程：

```powershell
cd .\services\api
python -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
```

`services/api/.env` 是后端运行配置文件。修改 `.env` 后必须重启 uvicorn；运行中的进程不会自动重新读取环境变量。

### 2. 配置 provider

`.env` 中按需配置以下字段。没有模型 key 时，Android 端侧闭环仍可运行；只是不启用云端增强。

```env
LANXIN_API_KEY=
LANXIN_BASE_URL=https://api-ai.vivo.com.cn/v1
LANXIN_MODEL=Doubao-Seed-2.0-mini

FAST_MODEL_API_KEY=
FAST_MODEL_BASE_URL=https://api-ai.vivo.com.cn/v1
FAST_MODEL_NAME=Doubao-Seed-2.0-mini

EXPERT_MODEL_API_KEY=
EXPERT_MODEL_BASE_URL=https://api-ai.vivo.com.cn/v1
EXPERT_MODEL_NAME=Doubao-Seed-2.0-pro

VIVO_OCR_APP_ID=
VIVO_OCR_APP_KEY=
VIVO_OCR_URL=http://api-ai.vivo.com.cn/ocr/general_recognition
VIVO_OCR_BUSINESS_PROFILE=rotatable

VIVO_IMAGE_GENERATION_API_KEY=
VIVO_IMAGE_GENERATION_URL=https://api-ai.vivo.com.cn/api/v1/image_generation
VIVO_IMAGE_GENERATION_MODEL=Doubao-Seedream-4.5
ENABLE_PROVIDER_PROBE=false
```

安全边界：

- 不要把真实 key 写入 Android、Gradle、README、脚本、APK 或日志。
- Chat 与图片生成 provider 必须使用 HTTPS、预期 vivo 域名和预期路径。
- OCR 按 vivo 官方文档允许精确的 `http://api-ai.vivo.com.cn/ocr/general_recognition`，但后端仍拒绝任意 HTTP、私网和非预期路径配置。
- `/health`、`/ready`、`/api/providers/status` 只返回脱敏状态，不回显密钥。
- `/api/providers/probe` 默认关闭，只应在受控验收环境中通过 `ENABLE_PROVIDER_PROBE=true` 启用。

### 3. 启动后端

```powershell
.\scripts\start_backend.ps1
```

等价手动流程：

```powershell
cd .\services\api
.\.venv\Scripts\activate
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

健康检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
Invoke-RestMethod http://127.0.0.1:8000/ready
```

接口文档：

```text
http://127.0.0.1:8000/docs
```

### 4. 后端测试

```powershell
.\scripts\test_backend.ps1
```

等价手动流程：

```powershell
cd .\services\api
.\.venv\Scripts\python.exe -m pytest -q
```

## Android 构建与运行

### 1. Android Studio 运行

1. 用 Android Studio 打开 `apps/android`。
2. 确认 SDK Platform 35 已安装。
3. 选择 `app` 模块。
4. 连接真机或启动模拟器。
5. 点击 Run。

### 2. 命令行构建 APK

推荐使用仓库脚本：

```powershell
.\scripts\build_android_debug.ps1
```

如果只想打包、不跑单元测试：

```powershell
.\scripts\build_android_debug.ps1 -SkipTests
```

如果 Gradle 依赖缓存不完整，需要联网解析依赖：

```powershell
.\scripts\build_android_debug.ps1 -Online
```

生成 APK：

```text
apps/android/app/build/outputs/apk/debug/app-debug.apk
```

脚本成功时会输出 APK 路径、大小和 SHA-256。

### 3. 直接使用 Gradle

```powershell
cd .\apps\android
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
```

安装到当前连接设备：

```powershell
.\gradlew.bat app:installDebug --no-daemon
```

## APK 调试流程

当前应用包名：

```text
com.suishouban.app
```

### 1. 检查 ADB

如果 `adb` 已加入 `Path`：

```powershell
adb devices
```

如果未加入 `Path`，先把 Android SDK 的 `platform-tools` 加入环境变量，再重新打开终端。

```powershell
adb devices
```

设备状态判断：

- `device`：连接正常。
- `unauthorized`：手机未授权当前电脑，检查手机 RSA 授权弹窗。
- `offline`：连接异常，重新插拔 USB 或重启 ADB。
- 无设备：检查数据线、驱动、USB 模式和开发者选项。

重启 ADB：

```powershell
adb kill-server
adb start-server
adb devices
```

### 2. 安装或覆盖更新 APK

```powershell
adb install -r .\apps\android\app\build\outputs\apk\debug\app-debug.apk
```

如果出现签名不一致：

```text
INSTALL_FAILED_UPDATE_INCOMPATIBLE
```

先卸载旧包再安装：

```powershell
adb uninstall com.suishouban.app
adb install -r .\apps\android\app\build\outputs\apk\debug\app-debug.apk
```

`uninstall` 会删除本地 App 数据；普通 `install -r` 通常保留数据。

### 3. 启动 App

```powershell
adb shell monkey -p com.suishouban.app -c android.intent.category.LAUNCHER 1
```

查看安装信息：

```powershell
adb shell dumpsys package com.suishouban.app | Select-String -Pattern 'versionCode|versionName|firstInstallTime|lastUpdateTime|targetSdk'
```

### 4. 查看日志

清空旧日志：

```powershell
adb logcat -c
```

观察 App、崩溃和主线程异常：

```powershell
adb logcat | Select-String -Pattern 'suishouban|SuiShouBan|AndroidRuntime|FATAL EXCEPTION|NetworkOnMainThreadException'
```

一次性导出日志：

```powershell
adb logcat -d -v time > .\logs\android-logcat.txt
```

### 5. 本地后端联调

真机要访问开发机后端，有三种方式：

1. **公网 HTTPS 网关**：推荐方式，最接近真实产品运行。
2. **局域网 IP**：手机和电脑在同一网络，App 设置页填写 `http://电脑IPv4:8000/`。
3. **adb reverse**：只适合 USB 本地调试，不能代表产品运行前提。

局域网调试：

```powershell
ipconfig
```

找到电脑当前网卡 IPv4，例如：

```text
192.168.1.23
```

App 设置页填写：

```text
http://192.168.1.23:8000/
```

必须满足：

- 后端使用 `--host 0.0.0.0` 启动。
- 手机和电脑在同一局域网。
- Windows 防火墙允许 Python 或 8000 端口入站。
- 地址以 `/` 结尾。

`adb reverse` 调试：

```powershell
adb reverse tcp:8000 tcp:8000
adb reverse --list
```

然后 App 设置页可以填写：

```text
http://127.0.0.1:8000/
```

这只在 `reverse --list` 确认映射存在时成立。没有 reverse 时，真机上的 `127.0.0.1` 是手机自身。

## 真实设备与云真机验收

### 1. 默认云真机部署

仓库脚本默认设备：

```text
val-vclinner-rt-contest.vivo.com.cn:37065
```

安装 APK 并启动 App：

```powershell
.\scripts\deploy_remote_android.ps1
```

指定设备：

```powershell
.\scripts\deploy_remote_android.ps1 -Device "host:port"
```

指定已有 APK：

```powershell
.\scripts\deploy_remote_android.ps1 -ApkPath ".\apps\android\app\build\outputs\apk\debug\app-debug.apk"
```

指定公网 Workflow 网关：

```powershell
.\scripts\deploy_remote_android.ps1 -WorkflowUrl "https://your-workflow-gateway.example.com/"
```

脚本会：

1. 等待 ADB 设备达到 `device` 状态。
2. 检查 HTTPS Workflow 网关 `/health`。
3. 推送并安装 APK。
4. 处理部分 vivo 安装器确认页面。
5. 启动 App。
6. 检查启动阶段 fatal log。

### 2. 复杂截图验收

只验端侧闭环：

```powershell
.\scripts\validate_remote_complex_screenshots.ps1
```

验端侧闭环 + vivo/蓝心增强：

```powershell
.\scripts\validate_remote_complex_screenshots.ps1 -WorkflowUrl "https://your-workflow-gateway.example.com/"
```

验收覆盖：

- 广告、系统页、随手办自身页面不提示。
- 行动截图出现“可能有待办”。
- 忽略后不保存、不注册提醒。
- 查看候选并生成草稿后展示候选卡。
- 确认后保存 Room 并注册 WorkManager 截止提醒。
- 多任务截图拆出多张卡，支持全部创建和选择性创建。
- ReAct 只完善选中卡；空选择提示先选择。
- logcat 无崩溃、DTO、Room/SQLite、WorkManager、主线程网络、本机地址连接错误。

未传 `-WorkflowUrl` 时只算端侧 ML Kit + 本地规则闭环；传入公网 HTTPS Workflow 网关后，才验证 vivo API/蓝心增强和 provider telemetry。

## API 概览

兼容旧接口：

```http
POST /api/analyze/screenshot-text
POST /api/analyze/screenshot-image
```

工作流接口：

```http
POST /api/workflows/screenshot-text
POST /api/workflows/screenshot-image
GET  /api/workflows/{run_id}
GET  /api/workflows/{run_id}/events
POST /api/workflows/{run_id}/ocr-candidates
PATCH /api/workflows/{run_id}/draft
POST /api/workflows/{run_id}/react
POST /api/workflows/{run_id}/confirm
POST /api/workflows/{run_id}/resume
```

Provider 与指标：

```http
GET  /api/providers/status
POST /api/providers/probe
GET  /api/metrics/summary
GET  /api/metrics/performance
```

## 核心代码位置

Android：

- `apps/android/app/src/main/java/com/suishouban/app/AppViewModel.kt`：截图/相册/分享文本进入分析流程，协调端侧规则和云端 Workflow。
- `apps/android/app/src/main/java/com/suishouban/app/domain/`：本地行动抽取、截图 gate、OCR 清洗。
- `apps/android/app/src/main/java/com/suishouban/app/ocr/TextRecognitionService.kt`：ML Kit OCR。
- `apps/android/app/src/main/java/com/suishouban/app/data/remote/`：后端 API DTO、Retrofit 接口和 API factory。
- `apps/android/app/src/main/java/com/suishouban/app/data/repository/WorkflowUrlPolicy.kt`：Workflow URL 安全策略。
- `apps/android/app/src/main/java/com/suishouban/app/reminder/`：截图监听、通知和 WorkManager 提醒。
- `apps/android/app/src/main/java/com/suishouban/app/ui/`：Compose 页面与候选卡 UI。

后端：

- `services/api/app/main.py`：FastAPI 应用入口。
- `services/api/app/core/config.py`：环境变量配置与 provider 安全边界。
- `services/api/app/api/endpoints/workflows.py`：Workflow HTTP 接口。
- `services/api/app/api/endpoints/providers.py`：provider 状态和 probe 接口。
- `services/api/app/services/llm_client.py`：文本模型调用客户端。
- `services/api/app/services/provider_runtime.py`：provider 可用性、脱敏状态和运行时策略。
- `services/api/app/services/vivo_ocr.py`：vivo OCR 服务端代理。
- `services/api/app/services/image_generation.py`：图片生成 provider 代理。
- `services/api/app/services/workflow_graph.py`：截图到行动的 LangGraph 编排。
- `services/api/app/services/workflow_service.py`：Workflow 状态、事件和确认逻辑。
- `services/api/app/repositories/workflows.py`：Workflow 持久化、缓存和检查点。

## 常见问题

### APK 构建失败，提示找不到 Android SDK

确认 SDK 35 已安装，或显式传入路径：

```powershell
.\scripts\build_android_debug.ps1 -SdkPath "<Android SDK 路径>"
```

### Gradle 离线构建失败

默认脚本使用离线参数。首次构建或依赖缓存缺失时执行：

```powershell
.\scripts\build_android_debug.ps1 -Online
```

### 设备显示 unauthorized

处理步骤：

1. 拔掉 USB。
2. 在手机开发者选项里撤销 USB 调试授权。
3. 重新插入 USB。
4. 手机弹窗选择允许。
5. 重新执行 `adb devices`。

### 手机访问不到后端

按顺序检查：

1. 后端是否使用 `--host 0.0.0.0`。
2. 手机和电脑是否在同一局域网。
3. App 设置页 URL 是否填写电脑局域网 IP，而不是 `127.0.0.1`。
4. Windows 防火墙是否允许 Python 或 8000 端口入站。
5. 如使用 `adb reverse`，确认 `adb reverse --list` 中存在 `tcp:8000 tcp:8000`。

### OCR 显示 `mlkit+rules`

`mlkit+rules` 不等于 vivo OCR 一定没配置。该项目会并行或降级使用端侧 ML Kit、本地规则和云端 Workflow。排查顺序：

1. App 设置页是否启用 AI 增强并填写正确 Workflow URL。
2. 手机是否能访问该 URL。
3. 后端 `/health`、`/ready` 是否正常。
4. `services/api/.env` 是否配置 provider key，修改后是否重启后端。
5. `adb reverse --list` 是否存在映射；没有映射时真机 `127.0.0.1` 不会访问电脑。
6. 后端日志和 `workflow.db` 是否出现 OCR、workflow 或 provider 错误。

### 安装后不是最新代码

按顺序检查：

1. 是否先执行了 `.\scripts\build_android_debug.ps1`。
2. 安装的 APK 是否为 `apps/android/app/build/outputs/apk/debug/app-debug.apk`。
3. 手机上包名是否为 `com.suishouban.app`。
4. 是否连接了多个设备；多个设备时使用 `adb -s <device>` 指定。

### 需要复赛提交材料

当前已整理的复赛材料在：

```text
..\随手办_应用赛道复赛提交材料.zip
```

该压缩包只保留正式提交材料：PPT、海报、演示视频、APK、核心代码包、提交说明与运行手册。

## 更多文档

- [Android 真机 ADB 调试教程](docs/ADB_DEBUGGING.md)
- [测试与真实设备验收指南](docs/guides/LOCAL_AND_REMOTE_TESTING.md)
- [产品演示与验收脚本](docs/guides/COMPETITION_DEMO.md)
- [后端 Workflow API](services/api/README.md)
