# 墨斐数字资产 Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 生成一套可用于 Android 电子宠物的墨斐状态主视觉与动作关键帧，且所有素材保持 v6 角色设定的一致性。

**Architecture:** 以已验收的横向 v2 待命图作为唯一像素母版。胶囊、面罩、眼睛槽位和外置截图框不再由生成模型重绘；导出脚本只在固定坐标叠加状态眼形、扫描线、固定半径光环和粒子。动作以同一母版的三种固定强度叠加组成短循环关键帧，视频文件由关键帧合成 GIF。

**Tech Stack:** Paigod `gpt-image-2`（母版）、Pillow、PNG、GIF、Android `drawable-nodpi`、关键帧序列。

---

## 资产契约

- 画布：`1024x1024` PNG，角色居中，四周至少保留 12% 透明或近白安全边距。
- 角色固定：中度横向椭球薄玻璃舱、深海蓝圆角方形面罩、双竖向发光眼、四段向内截图识别框、下半部斜向轨道光带；无嘴、四肢、底座、文字、Logo、水印或场景。
- 角框硬约束：四段截图识别框必须是玻璃舱**外部**四角各一枚独立、较大、明显的青色 `[` / `]` 式裁切角标；每枚由两条垂直线段构成并向中心开口。不得将角框画成面罩内部的小圆角、装饰线或一条连续边框。
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

### Task 1: 锁定像素母版

**Files:**
- Read: `output/imagegen/mofei-runtime/mofei_idle_base.png`
- Create: `output/imagegen/mofei-runtime/manifest.json`

**Step 1:** 将待命图复制为不可变母版，记录 SHA-256 与 `512x512` 尺寸。

**Step 2:** 定义并测试固定眼睛、外置角框、轨道与光环坐标。所有帧必须复用这些常量。

**Step 3:** 写入清单，记录母版 SHA-256、提示词版本、状态与输出文件。

### Task 2: 确定性导出八张状态主视觉

**Files:**
- Create: `output/imagegen/mofei-runtime/mofei_<state>_base.png`

**Step 1:** 每次从同一像素母版开始，在固定坐标叠加状态色与眼部表达。

**Step 2:** 验证八张图胶囊、面罩和外置 `[ ]` 框区域的哈希完全相同。

**Step 3:** 若某张偏离角色母版，重复同一图生图请求并使用最符合资产契约的版本。

### Task 3: 确定性导出 24 张动作关键帧与 GIF

**Files:**
- Create: `output/imagegen/mofei-runtime/mofei_<state>_f01.png`
- Create: `output/imagegen/mofei-runtime/mofei_<state>_f02.png`
- Create: `output/imagegen/mofei-runtime/mofei_<state>_f03.png`

**Step 1:** 每个状态使用同一母版和同一组固定坐标，生成静止、动作中段、回环三帧。

**Step 2:** 对每个状态将三帧合成为无损循环 GIF，并验证三帧的固定几何掩码一致。

**Step 3:** 将各帧文件、提示词和验收结果写入清单。

### Task 4: 打包前验收

**Files:**
- Modify: `output/imagegen/mofei-runtime/manifest.json`

**Step 1:** 验证共 32 张 PNG 均可读取，且像素尺寸为 `1024x1024`。

**Step 2:** 生成联系表，人工检查八个状态和八条三帧动作序列的一致性。

**Step 3:** 记录未解决项：透明背景、WebP 转码和 Android 资源接入属于后续应用实现，不在本次资产生成中执行。
