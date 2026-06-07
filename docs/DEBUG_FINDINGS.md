# 真机调试问题记录

记录时间：2026-05-27

## 已定位问题

### 1. 真机请求后端失败后静默降级

现象：

- 页面顶部显示 `mlkit+rules`。
- 后端没有收到 `POST /api/analyze/screenshot-image`。
- 手机日志中出现过两类失败：
  - `CLEARTEXT communication to 10.21.207.108 not permitted by network security policy`
  - `SocketTimeoutException: failed to connect to /10.21.207.108:8000`

判断：

- 之前不是云端 OCR 成功，而是云端请求失败后回退到本机 ML Kit 和本地规则。
- 局域网 IP 在电脑本机可访问，不代表手机也能访问。校园网、热点隔离或防火墙会导致手机无法连到电脑端口。

已处理：

- Android 增加 `network_security_config.xml`，允许调试环境访问本地 HTTP 后端。
- Debug 默认 API 地址改为 `http://127.0.0.1:8000/`。
- 真机调试采用 `adb reverse tcp:8000 tcp:8000`，绕开局域网互访限制。

剩余风险：

- 断开 USB、重启手机或重启 ADB 后，需要重新执行 `adb reverse tcp:8000 tcp:8000`。
- 当前云端失败会静默回退，界面只显示最终引擎，不足以提示真实失败原因。

### 2. vivo OCR 和 LLM 配置问题

现象：

- 后端最初返回 `VIVO_OCR_APP_KEY is missing`。
- LLM 最初返回 `unsupported model`。

判断：

- `.env` 未配置 vivo OCR key 时，后端图片 OCR 不可用。
- `LANXIN_MODEL=lanxin-pro` 不在当前 vivo chat completions 接口支持列表内。

已处理：

- 支持 `LANXIN_BASE_URL` 写完整 `https://api-ai.vivo.com.cn/v1/chat/completions`。
- 模型改为文档可用模型 `Doubao-Seed-2.0-mini`。
- 已验证文本链路返回 `engine=lanxin`，图片链路返回 `engine=vivo-ocr+lanxin`。

### 3. 截图检测提醒体验不足

现象：

- 检测到截图后通知弹窗偏小。
- 可能在截图文件尚未完全写入时就弹出通知或进入处理流程。
- 点击通知会直接进入 App 内部流程，缺少中间确认和预览。

需要改进：

- 截图检测后增加短暂延迟或文件稳定性检查，避免图片尚未生成完成就处理。
- 通知样式需要更大、更明确，展示截图来源和处理状态。
- 点击通知后不应直接进入完整 App 流程，优先展示一个卡片式预览弹窗。
- 预览弹窗应直接展示行动卡片，可编辑标题、时间、地点、提醒和确认字段。

### 4. 生成行动卡延迟过高

现象：

- 云端链路包含图片上传、vivo OCR、LLM 抽取三步，体感等待时间明显。
- 若云端请求失败，还会再跑本地 ML Kit 和 rules，进一步拉长等待。

需要改进：

- UI 显示分阶段状态：上传中、OCR 中、行动卡生成中。
- 云端失败不要静默降级，应明确显示失败原因和当前 fallback。
- 优化超时策略，避免每次失败都等待完整连接超时。
- 可考虑先本地快速生成草稿，再用云端结果异步替换或增强。

### 5. 相册导入和截图入口行为不一致

现象：

- 用户观察到直接导入相册时仍显示 `mlkit+rules`。
- 截图入口和相册导入看起来可能走了不同逻辑。

判断方向：

- 需要检查相册导入是否调用 `analyzeImage(uri)`，以及是否启用 `preferCloudModel`。
- 如果云端图片上传失败，App 会继续本机 OCR，所以界面最终仍可能显示 `mlkit+rules`。
- 应在 UI 上区分“选择图片来源”和“实际分析引擎”，避免误判。

需要改进：

- 统一截图入口、系统分享、相册导入的分析路径。
- 在预览页展示云端请求状态和失败原因。
- 增加调试信息入口：API 地址、云端开关、最近一次请求 URL、最近一次失败原因。
