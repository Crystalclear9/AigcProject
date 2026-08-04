<!-- ppt-master-schema: spec-lock/v1 -->
# Execution Lock

## canvas
- viewBox: 0 0 1280 720
- format: PPT 16:9

## communication
- audience: 产品评审、课程答辩或创新项目展示现场的教师、评委与潜在协作者
- objective: 通过问题、产品闭环、AI 机制、可信边界和未来拓展，让听众理解用户价值、AI 创新、场景应用与商业潜力
- core_message: 随手办以截图为轻量入口，把通知和约定转化为可确认、可追溯、可提醒的行动
- consumption_mode: balanced

## mode
- mode: narrative

## visual_style
- visual_style: custom
- visual_style_behavior: 保留项目原生色和莫斐透明材质，使用平面网格、浅色底、品牌蓝色区与细线关系；禁止玻璃拟态、悬浮卡片墙、渐变发光和装饰性阴影

## colors
- background: #F7F9FE
- secondary_bg: #EAF2FF
- primary: #2F6BFF
- accent: #1748B8
- secondary_accent: #FF8A3D
- body_text: #182033
- muted: #6E7891
- line: #E2E7F3
- task: #E95656
- event: #2878FF
- promise: #FF8A3D
- comparison: #7A8190
- collection: #D48806
- image_rendering: custom
- image_rendering_behavior: Identity-preserving transparent blue oval Mofei with dark blue face screen, white expression, orbit ring, and four bracket-shaped anchors; no hands or feet; exaggerated motion through squash, stretch, tilt, orbit speed, anchor displacement, and facial expression

## typography
- font_family: Microsoft YaHei, Segoe UI, Arial, sans-serif
- title_family: Microsoft YaHei, Segoe UI, Arial, sans-serif
- body_family: Microsoft YaHei, Segoe UI, Arial, sans-serif
- annotation_family: Microsoft YaHei, Segoe UI, Arial, sans-serif
- body: 24
- title: 42
- subtitle: 32
- annotation: 18

## icons
- library: tabler-outline
- inventory: check, calendar, bell, lock, cloud-off, wifi-off, alert-triangle, clock, route, scan
- stroke_width: 2

## images
- p01_mofei: images/mofei_home_hero.png | source=user | pattern=页面右侧或中央独立主体，与标题形成对角关系 | crop=no-crop
- idle_mofei: images/mofei_in_app_idle_f01.png | source=user | pattern=小比例放在截图或手机边缘 | crop=no-crop
- focus_mofei: images/mofei_in_app_focus_f01.png | source=user | pattern=靠近识别流程或系统结构中心 | crop=no-crop
- confirm_mofei: images/mofei_in_app_confirm_f01.png | source=user | pattern=与确认按钮保持视线关系 | crop=no-crop
- reminder_mofei: images/mofei_reminder_f01.png | source=user | pattern=沿时间线或提醒通知进入 | crop=no-crop
- due_mofei: images/mofei_due_soon_f01.png | source=user | pattern=靠近 24 小时状态 | crop=no-crop
- urgent_mofei: images/mofei_urgent_f01.png | source=user | pattern=与橙色警示区域联动 | crop=no-crop
- complete_mofei: images/mofei_complete_f01.png | source=user | pattern=与对勾、任务完成状态组合 | crop=no-crop
- rest_mofei: images/mofei_rest_f01.png | source=user | pattern=放在本地草稿边界内 | crop=no-crop
- action_ring: images/mofei_action_ring_full.png | source=user | pattern=独立圆形动作环，不裁切 | crop=no-crop
- app_evidence: images/suishouban-mofei-app-current.png | source=user | pattern=仅在项目真实性页作为完整窄屏证据 | crop=no-crop
- p02_dorm: images/ai_scene_gallery_dorm.png | source=ai | pattern=右侧人物与桌面，左侧留出可编辑手机相册 | crop=adaptive
- p03_apps: images/ai_screen_ai_apps.png | source=ai | pattern=仅生成屏幕内部背景，外壳另绘 | crop=no-crop
- p04_chat: images/ai_screen_groupchat.png | source=ai | pattern=仅生成聊天气泡与头像占位，关键文字另绘 | crop=no-crop
- p09_poster: images/ai_poster_debate.png | source=ai | pattern=竖版海报完整显示，文字另绘 | crop=no-crop
- p17_future: images/ai_scene_future_ecosystem.png | source=ai | pattern=横向四场景连续画面，中间留出莫斐与路线 | crop=adaptive
- overwhelmed: images/mofei_overwhelmed.png | source=slice | pattern=页面角落独立角色 | crop=no-crop
- catch: images/mofei_catch.png | source=slice | pattern=手机侧边大动作 | crop=no-crop
- peek: images/mofei_peek.png | source=slice | pattern=手机边缘局部露出 | crop=no-crop
- scan: images/mofei_scan.png | source=slice | pattern=流程中心 | crop=no-crop
- split: images/mofei_split.png | source=slice | pattern=三条路径中心 | crop=no-crop
- question: images/mofei_question.png | source=slice | pattern=中置信区域 | crop=no-crop
- alarm: images/mofei_alarm.png | source=slice | pattern=断网图标附近 | crop=no-crop
- confirm_burst: images/mofei_confirm_burst.png | source=slice | pattern=确认按钮旁 | crop=no-crop
- learn: images/mofei_learn.png | source=slice | pattern=时间线终点 | crop=no-crop
- guard: images/mofei_guard.png | source=slice | pattern=手机边界内部 | crop=no-crop
- offline: images/mofei_offline.png | source=slice | pattern=本地层中心 | crop=no-crop
- celebrate: images/mofei_celebrate.png | source=slice | pattern=标志与闭环旁 | crop=no-crop

## page_rhythm
- P01: anchor
- P02: dense
- P03: dense
- P04: anchor
- P05: dense
- P06: dense
- P07: dense
- P08: breathing
- P09: anchor
- P10: dense
- P11: dense
- P12: dense
- P13: breathing
- P14: dense
- P15: dense
- P16: dense
- P17: dense
- P18: dense

## pptx_structure
- mode: flat

## forbidden
- `mask`, `<style>`, `class`, external CSS, `<foreignObject>`, `textPath`, `@font-face`, `<animate*>`, `<set>`, `<script>` / event attributes, `<iframe>`
- HTML named entities in text; write typography as raw Unicode and escape XML reserved characters
- glassmorphism, floating card wall, decorative gradient glow, decorative shadows
- Mofei hands, feet, arms, legs, shoes, humanoid limbs, or any loss of the four bracket anchors
- phone shell baked into generated screen assets; key text baked into generated UI imagery
