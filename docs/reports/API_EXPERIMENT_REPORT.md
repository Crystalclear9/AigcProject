# 随手办接口实验与比赛验收报告

## 目标

本轮优化把“可跑 demo”增强为比赛可讲、接口可验、现场稳定的版本。核心链路是：

```text
图片 -> vivo OCR -> 蓝心大模型 -> 行动卡
图片 -> vivo OCR 失败 -> Android ML Kit -> 蓝心大模型/规则 -> 行动卡
文本 -> 蓝心大模型 -> 失败回退规则 -> 行动卡
```

## 接口来源与配置

- vivo AIGC 文档来源：`docs/api/vivo-aigc/`
- 蓝心大模型地址：`https://api-ai.vivo.com.cn/v1/chat/completions`
- vivo 通用 OCR 地址：`http://api-ai.vivo.com.cn/ocr/general_recognition`
- 鉴权方式：`Authorization: Bearer AppKey`
- OCR 请求要点：`requestId` 使用 UUID，`businessid` 为 `aigc+appid`，`pos=2` 返回文字和相对坐标。

后端 `.env` 示例：

```env
LANXIN_API_KEY=
LANXIN_BASE_URL=https://api-ai.vivo.com.cn/v1
LANXIN_MODEL=Doubao-Seed-2.0-mini
VIVO_OCR_APP_ID=
VIVO_OCR_APP_KEY=
VIVO_OCR_BUSINESS_PROFILE=rotatable
VIVO_OCR_TIMEOUT_SECONDS=5
MAX_UPLOAD_IMAGE_BYTES=5242880
DATABASE_PATH=./suishouban.db
CORS_ORIGINS=*
```

真实 key 只放本地 `.env`，不提交仓库。无 key 时，文本分析自动回退规则；图片分析由 Android 回退 ML Kit。

## 验收接口

```http
GET  /health
POST /api/analyze/screenshot-text
POST /api/analyze/screenshot-image
GET  /api/demo/evaluate
GET  /api/metrics/summary
```

分析接口新增非破坏字段：

- `trace_id`：单次请求追踪 ID。
- `fallback_reason`：蓝心大模型失败或缺 key 时的原因。
- `warnings`：给演示界面展示的降级说明。

## 实验场景

`GET /api/demo/evaluate` 覆盖策划案五类场景：

- 课程通知处理
- 比赛报名处理
- 社团活动安排
- 聊天承诺识别
- 会议与准备事项

字段级验收项：

- 卡片类型
- 标题
- 时间字段
- 地点或平台
- 提交材料
- 提醒策略
- 待确认字段
- 拆卡数量

## 当前验证结果

已在 2026-05-25 本地环境执行：

```powershell
cd services/api
python -m compileall app
python -m unittest discover -s tests -v
```

结果：

- 后端编译通过。
- 后端单元测试 15/15 通过。
- 测试覆盖 vivo OCR `pos=0/1/2` 结构解析、HTTP 401 错误消息、空 OCR 结果、蓝心 JSON 解析、LLM 失败回退、五类 demo 字段级验收。

Android 验证：

```powershell
cd apps/android
.\gradlew.bat testDebugUnitTest --no-daemon
```

当前机器缺 Android SDK，Gradle 报错：

```text
SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting sdk.dir in local.properties.
```

因此本轮只完成 Android 源码级同步检查；APK 构建和单测需在安装 Android SDK 35 的 Android Studio 环境继续执行。

## 演示建议

1. 启动后端并打开 `http://127.0.0.1:8000/docs`。
2. 调用 `/api/demo/evaluate`，展示字段级通过率。
3. 调用 `/api/analyze/screenshot-text`，展示 `engine`、`trace_id`、`fallback_reason`、`warnings`。
4. 打开 Android App，从“导入”选择样例或截图。
5. 在“动作预览”展示引擎标签和回退提示，再确认创建卡片和提醒。
