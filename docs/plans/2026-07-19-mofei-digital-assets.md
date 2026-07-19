# 墨斐数字资产 Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 生成一套可用于 Android 电子宠物的墨斐状态主视觉与动作关键帧，且所有素材保持 v6 角色设定的一致性。

**Architecture:** 将 `output/imagegen/mofei-mascot-concept-v6.png` 作为唯一角色母版。每个状态均通过 Paigod `gpt-image-2-edit` 对母版进行图生图编辑，锁定轮廓、面罩、截图框和材质，只允许眼部、辉光、轨道、姿态和状态色变化。动作以同一状态的首帧、过渡帧和尾帧组成短循环关键帧序列，视频文件不在图像代理中生成。

**Tech Stack:** Paigod `gpt-image-2-edit`、PNG、Android `drawable-nodpi`、关键帧序列。

---

## 资产契约

- 画布：`1024x1024` PNG，角色居中，四周至少保留 12% 透明或近白安全边距。
- 角色固定：中度横向椭球薄玻璃舱、深海蓝圆角方形面罩、双竖向发光眼、四段向内截图识别框、下半部斜向轨道光带；无嘴、四肢、底座、文字、Logo、水印或场景。
- 变更边界：只改变眼部表达、状态色、光环、轨道亮度和细微姿态。不得改变角色比例、相机角度、面罩位置或截图框数量。
- 状态主图命名：`mofei_<state>_base.png`。
- 动作帧命名：`mofei_<state>_f01.png`、`mofei_<state>_f02.png`、`mofei_<state>_f03.png`。

| 状态 | 主色 | 动作 | 帧数 |
| --- | --- | --- | --- |
| idle | 冰蓝 | 轻微漂浮和眨眼 | 3 |
| focus | 青蓝 | 扫描线下移 | 3 |
| confirm | 紫色 | 侧目等待确认 | 3 |
| reminder | 琥珀色 | 轻敲/提示脉冲 | 3 |
| due_soon | 橙色 | 加速环带和警示脉冲 | 3 |
| urgent | 珊瑚红 | 紧急双脉冲 | 3 |
| complete | 薄荷绿 | 勾选和星点绽放 | 3 |
| rest | 灰蓝 | 闭眼、光线变暗 | 3 |

### Task 1: 验证代理与母版输入

**Files:**
- Read: `output/imagegen/mofei-mascot-concept-v6.png`
- Create: `output/imagegen/mofei-runtime/manifest.json`

**Step 1:** 对 Paigod 编辑请求执行 `--dry-run`，确认使用 `gpt-image-2-edit`、母版路径、目标尺寸和输出路径。

**Step 2:** 执行单张 `idle` 基准图生成，人工检查角色比例、面罩、截图框和无文字约束。

**Step 3:** 写入清单，记录母版 SHA-256、提示词版本、状态与输出文件。

### Task 2: 生成八张状态主视觉

**Files:**
- Create: `output/imagegen/mofei-runtime/mofei_<state>_base.png`

**Step 1:** 每次只使用 v6 母版输入，按状态提示词生成八张主视觉。

**Step 2:** 检查每张图的尺寸、格式、角色居中程度、文字/水印缺失和状态色。

**Step 3:** 若某张偏离角色母版，重复同一图生图请求并使用最符合资产契约的版本。

### Task 3: 生成 24 张动作关键帧

**Files:**
- Create: `output/imagegen/mofei-runtime/mofei_<state>_f01.png`
- Create: `output/imagegen/mofei-runtime/mofei_<state>_f02.png`
- Create: `output/imagegen/mofei-runtime/mofei_<state>_f03.png`

**Step 1:** 每个状态使用其已验收主视觉作为动作序列母版，生成静止首帧、动作中段和可回环尾帧。

**Step 2:** 比较三帧的角色几何结构，拒绝角色比例、面罩、截图框或相机角度变化明显的序列。

**Step 3:** 将各帧文件、提示词和验收结果写入清单。

### Task 4: 打包前验收

**Files:**
- Modify: `output/imagegen/mofei-runtime/manifest.json`

**Step 1:** 验证共 32 张 PNG 均可读取，且像素尺寸为 `1024x1024`。

**Step 2:** 生成联系表，人工检查八个状态和八条三帧动作序列的一致性。

**Step 3:** 记录未解决项：透明背景、WebP 转码和 Android 资源接入属于后续应用实现，不在本次资产生成中执行。
