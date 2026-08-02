<!-- ppt-master-schema: design-spec/v1 -->
# 随手办产品演示 - Design Spec

## I. Project Information

| Item | Value |
| --- | --- |
| Project Name | 随手办产品演示 |
| Canvas Format | PPT 16:9，1280 × 720 |
| Page Count | 18 |
| Target Audience | 产品评审、课程答辩或创新项目展示现场的教师、评委与潜在协作者；听众知道截图会被遗忘，但未必了解随手办的完整处理机制。 |
| Communication Intent | 先用日常截图堆积建立问题，再展示随手办如何通过截图识别、拆卡、确认、提醒和本地偏好形成完整闭环，最后说明产品的可信边界、AI 创新、用户价值、场景应用、商业潜力和未来拓展。 |
| Desired Audience Outcome | 听众能够复述随手办从截图到行动的核心流程，理解莫斐在识别、确认和提醒中的角色，并能判断方案是否完整、可信且具有继续展示、评审或合作价值。 |
| Core Message / Ask / Action | 随手办以截图这一符合个人习惯的轻量入口，自动把通知、人物和约定整理成可确认、可追溯、可提醒的行动卡。 |
| Delivery Context | 主要用于有主讲人的 8–12 分钟现场演示；文件同时支持会后独立浏览。 |
| Artifact Afterlife | 作为项目答辩、产品演示和后续迭代讨论的可编辑母稿，可替换真实界面截图、调整莫斐动作并继续复用。 |
| Reading Mode | balanced |
| Content Strategy | 保留现有 18 页叙事与视觉身份，同时将原版 PPT 中的赛事与团队身份、作品概述、目标用户、关键字段、蓝心大模型应用、OriginOS 联动、需求依据与竞争差异重新并入对应页面；仅删除重复表述，不再以“演讲备注可补充”为由省略关键可见信息。 |
| Design Style | 随手办原生平面版：项目真实色彩、平面网格、真实界面证据与夸张莫斐动作结合 |
| Formula Policy | text-only |
| AI Image Acquisition Path | host-native |
| Generation Mode | continuous |
| Spec Refinement | disabled |
| Speaker Notes | enabled — explicit final chat confirmation |
| Custom Animations | enabled — explicit per-slide and object-level motion request |
| Narration Audio | disabled — explicit final chat confirmation |
| Created Date | 2026-07-30 |

## II. Canvas Specification

| Property | Value |
| --- | --- |
| Format | PPT 16:9 |
| Dimensions | 1280 × 720 |
| viewBox | `0 0 1280 720` |
| Margins | 64 px horizontal，48 px vertical |
| Content Area | x=64–1216，y=48–672 |

## III. Visual Theme

### Theme Style

- **Mode**: narrative
- **Visual style**: custom
- **Visual Style Behavior**: 保留 BrandBlue、MistBlue、Paper、Ink 与橙色状态色，以及莫斐自身的透明材质；页面保持平面，不使用玻璃拟态、渐变发光、悬浮卡片墙或装饰性阴影。手机、截图、字段、时间轴和莫斐直接落在清晰网格上，以浅色底、品牌蓝色区、字号和细线关系建立层级；圆角只用于真实手机控件或必要标签。
- **Theme**: 截图被莫斐接住并转化为行动
- **Tone**: 轻盈、具体、可信、具有现场演示张力

### Color Scheme

| Role | HEX | Purpose |
| --- | --- | --- |
| Background | #F7F9FE | 主页面底色，与当前 Android Paper 一致 |
| Secondary background | #EAF2FF | 局部信息区、手机屏幕浅底 |
| Primary | #2F6BFF | 品牌主色、流程推进、关键按钮 |
| Accent | #1748B8 | 深蓝标题、系统结构、重点边界 |
| Secondary accent | #FF8A3D | 承诺、提醒、风险和转折 |
| Body text | #182033 | 主文字 |

### AI Image Strategy

- **Image Rendering**: custom
- **Visual**: 保持项目现有莫斐的透明蓝色椭圆机体、深蓝面屏、白色表情、环绕轨道及四个“【】式悬浮锚点；场景图使用干净的产品插画/轻写实混合，不在图内生成关键文字。
- **Mood**: 动作夸张、节奏活泼，像动画片中的强烈 squash-and-stretch，但产品信息仍清晰可信。
- **Image Rendering Behavior**: 莫斐不增加手、脚、机械臂、腿或鞋。夸张动作仅通过机体倾斜、压缩拉伸、轨道速度、锚点位移旋转和面屏表情实现；所有 AI 场景使用项目页面底色或可无缝融入的浅色背景，手机壳、关键文本和按钮由可编辑 PPT 对象另行构成。

## IV. Typography System

### Font Plan

| Role | Character (Reference) | Primary | English if non-English | Fallback tail |
| --- | --- | --- | --- | --- |
| Title | 现代无衬线、粗体、紧凑 | Microsoft YaHei | Segoe UI | Arial, sans-serif |
| Body | 现代无衬线、常规字重 | Microsoft YaHei | Segoe UI | Arial, sans-serif |
| Annotation | 现代无衬线、精确小字 | Microsoft YaHei | Segoe UI | Arial, sans-serif |

- **Title stack**: Microsoft YaHei, Segoe UI, Arial, sans-serif
- **Body stack**: Microsoft YaHei, Segoe UI, Arial, sans-serif
- **Annotation stack**: Microsoft YaHei, Segoe UI, Arial, sans-serif

### Font Size Hierarchy

| Purpose | Anchor Size (px) |
| --- | ---: |
| Body | 24 |
| Title | 42 |
| Subtitle | 32 |
| Annotation | 18 |

## V. Layout Principles

### Page Structure

- **Header area**: 左对齐标题；封面与结尾允许居中或超大字号。标题不加装饰性下划线。
- **Content area**: 以一个主视觉关系为中心；手机、界面、莫斐和证据标注直接挂在网格上，不使用重复卡片容器。
- **Footer area**: 仅保留小页码或必要的“示意数据”说明。

### Spacing Specification

| Element | Current Project |
| --- | --- |
| Safe margin | 64 px |
| Content block gap | 32 px |
| Icon-text gap | 12 px |

## VI. Icon Usage Specification

- **Primary bundled library**: tabler-outline
- **Stroke Width**: 2

| Purpose | Icon Path | Page |
| --- | --- | --- |
| 确认、日历、提醒、锁、断网、云端 | tabler-outline 对应图标 | P06、P13、P15、P16 |
| 卡片类型辅助标识 | tabler-outline 对应图标 | P07 |

## VIII. Image Resource List

| Filename | Dimensions | Ratio | Purpose | Type | Layout pattern | Crop Policy | Acquire Via | Status | Reference | text_policy | page_role |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mofei_home_hero.png | 1402x1122 | 1.25 | 封面与结尾的标准莫斐身份锚点 | Existing mascot | 页面右侧或中央独立主体，与标题形成对角关系 | no-crop | user | Existing | 当前项目正式首页莫斐 | none | hero_page |
| mofei_in_app_idle_f01.png | 512x512 | 1.00 | 问题场景中的观察/待命状态 | Existing mascot frame | 小比例放在截图或手机边缘 | no-crop | user | Existing | 应用内 idle 第一帧 | none | local |
| mofei_in_app_focus_f01.png | 512x512 | 1.00 | 识别、扫描与模块调度 | Existing mascot frame | 靠近识别流程或系统结构中心 | no-crop | user | Existing | 应用内 focus 第一帧 | none | local |
| mofei_in_app_confirm_f01.png | 512x512 | 1.00 | 等待用户确认 | Existing mascot frame | 与确认按钮保持视线关系 | no-crop | user | Existing | 应用内 confirm 第一帧 | none | local |
| mofei_reminder_f01.png | 512x512 | 1.00 | 普通提醒 | Existing mascot frame | 沿时间线或提醒通知进入 | no-crop | user | Existing | reminder 第一帧 | none | local |
| mofei_due_soon_f01.png | 512x512 | 1.00 | 临近截止提醒 | Existing mascot frame | 靠近 24 小时状态 | no-crop | user | Existing | due soon 第一帧 | none | local |
| mofei_urgent_f01.png | 512x512 | 1.00 | 紧急与异常强调 | Existing mascot frame | 与橙色警示区域联动 | no-crop | user | Existing | urgent 第一帧 | none | local |
| mofei_complete_f01.png | 512x512 | 1.00 | 完成与结尾庆祝 | Existing mascot frame | 与对勾、任务完成状态组合 | no-crop | user | Existing | complete 第一帧 | none | local |
| mofei_rest_f01.png | 512x512 | 1.00 | 断网或服务不可用时的安静待命 | Existing mascot frame | 放在本地草稿边界内 | no-crop | user | Existing | rest 第一帧 | none | local |
| mofei_action_ring_full.png | 1024x1024 | 1.00 | 展示莫斐动作入口与轨道身份 | Existing product asset | 独立圆形动作环，不裁切 | no-crop | user | Existing | 当前项目完整动作环 | none | local |
| suishouban-mofei-app-current.png | 1080x2344 | 0.46 | 校准当前产品色彩与页面语义 | Existing app screenshot | 仅在项目真实性页作为完整窄屏证据 | no-crop | user | Existing | 当前 Android 应用截图 | embedded | local |
| ai_scene_gallery_dorm.png | 1536x1024 | 1.50 | 学生在宿舍翻找截图的生活场景 | Generated scene | 右侧人物与桌面，左侧留出可编辑手机相册 | adaptive | ai | Generated | 宿舍、手机相册、截图堆积、无可读文字 | none | hero_page |
| ai_screen_ai_apps.png | 1024x1536 | 0.67 | 大量抽象 AI 应用图标的手机屏幕内容 | Generated screen | 仅生成屏幕内部背景，外壳另绘 | no-crop | ai | Generated | 抽象图标，无品牌与文字 | none | local |
| ai_screen_groupchat.png | 852x1846 | 0.46 | 班级群聊背景 | Generated screen | 仅生成聊天气泡与头像占位，关键文字另绘 | no-crop | ai | Generated | 群聊界面，无可读文字、无手机壳 | none | local |
| ai_poster_debate.png | 1024x1536 | 0.67 | 辩论赛海报的无字视觉底图 | Generated poster | 竖版海报完整显示，文字另绘 | no-crop | ai | Generated | 校园辩论赛、麦克风与舞台，无文字 | none | local |
| ai_scene_future_ecosystem.png | 1536x1024 | 1.50 | 未来拓展：学习、会议、旅行、办事场景 | Generated scene | 横向四场景连续画面，中间留出莫斐与路线 | adaptive | ai | Generated | 多场景生态，无文字 | none | hero_page |
| mofei_pose_sheet_a.png | 1254x1254 | 1.00 | 夸张动作素材 A：被截图淹没、接住截图、探头、扫描 | Illustration Sheet | 2x2 等距动作表，不直接放置 | no-crop | ai | Generated | 严格莫斐锚点，无手脚，页面底色背景 | none | local |
| mofei_pose_sheet_b.png | 1254x1254 | 1.00 | 夸张动作素材 B：拆分、疑问、警报、确认 | Illustration Sheet | 2x2 等距动作表，不直接放置 | no-crop | ai | Generated | 严格莫斐锚点，无手脚，页面底色背景 | none | local |
| mofei_pose_sheet_c.png | 1254x1254 | 1.00 | 夸张动作素材 C：学习、守护、离线、庆祝 | Illustration Sheet | 2x2 等距动作表，不直接放置 | no-crop | ai | Generated | 严格莫斐锚点，无手脚，页面底色背景 | none | local |
| mofei_overwhelmed.png | 535x492 | 1.09 | P02 被截图淹没 | Mascot slice | 页面角落独立角色 | no-crop | slice | Generated | sheet A cell 1 | none | local |
| mofei_catch.png | 553x373 | 1.48 | P04 接住截图 | Mascot slice | 手机侧边大动作 | no-crop | slice | Generated | sheet A cell 2 | none | local |
| mofei_peek.png | 324x499 | 0.65 | P03 从应用图标后探出 | Mascot slice | 手机边缘局部露出 | no-crop | slice | Generated | sheet A cell 3 | none | local |
| mofei_scan.png | 550x408 | 1.35 | P06/P11 扫描与模块调度 | Mascot slice | 流程中心 | no-crop | slice | Generated | sheet A cell 4 | none | local |
| mofei_split.png | 480x495 | 0.97 | P10 拆分海报行动 | Mascot slice | 三条路径中心 | no-crop | slice | Generated | sheet B cell 1 | none | local |
| mofei_question.png | 473x492 | 0.96 | P08 低置信疑问 | Mascot slice | 中置信区域 | no-crop | slice | Generated | sheet B cell 2 | none | local |
| mofei_alarm.png | 481x465 | 1.03 | P16 异常与离线提示 | Mascot slice | 断网图标附近 | no-crop | slice | Generated | sheet B cell 3 | none | local |
| mofei_confirm_burst.png | 465x466 | 1.00 | P06/P15 强调用户确认 | Mascot slice | 确认按钮旁 | no-crop | slice | Generated | sheet B cell 4 | none | local |
| mofei_learn.png | 451x406 | 1.11 | P12 从修改中学习 | Mascot slice | 时间线终点 | no-crop | slice | Generated | sheet C cell 1 | none | local |
| mofei_guard.png | 491x428 | 1.15 | P13 本地隐私守护 | Mascot slice | 手机边界内部 | no-crop | slice | Generated | sheet C cell 2 | none | local |
| mofei_offline.png | 395x369 | 1.07 | P16 离线仍保留草稿 | Mascot slice | 本地层中心 | no-crop | slice | Generated | sheet C cell 3 | none | local |
| mofei_celebrate.png | 459x492 | 0.93 | P18 结尾庆祝 | Mascot slice | 标志与闭环旁 | no-crop | slice | Generated | sheet C cell 4 | none | hero_page |

## IX. Content Outline

### Part 1: 问题与产品登场

#### Slide 01 - 封面

- **Audience move**: 尚未进入产品情境 → 立即理解“截图之后还有下一步”
- **Layout**: 左侧超大标题与一句副标题；右侧标准莫斐与三种截图/行动对象形成向前的轨迹。背景保持平面浅底。
- **Title**: 随手办
- **Core message**: 让截图里的事，继续往下走。
- **Content**: 产品名；副标题；小字“截图识别 · 自动拆卡 · 用户确认 · 本地优先”；赛事身份“2026 中国大学生计算机大赛 · AIGC 创新赛”；团队身份“华中科技大学｜团队：给我干哪来了”。
- **Images**: mofei_home_hero.png；截图与任务/日历/提醒均为可编辑图形。
- **Motion suggestion**: 三张截图依次进入，莫斐轻微压缩后弹起，最后任务、日历和提醒沿轨道展开。

#### Slide 02 - 相册里存了很多截图

- **Audience move**: 把截图视为普通存档 → 看见重要行动被淹没的具体成本
- **Layout**: 左侧独立手机相册层，右侧宿舍人物场景；三个时间信息直接用高亮框连接，不放解释箭头。
- **Title**: 重要信息，常常停在相册里
- **Core message**: 截图很快，但后续寻找、读取和记忆仍由用户承担。
- **Content**: “周五 23:59”“周六 14:00”“周末上午”；一句说明“课程通知、活动海报、聊天约定，截图以后常常就留在相册里。”；目标人群“18–26 岁大学生、研究生、初入职场青年”；高频身份“学生干部、社团骨干、实习生、科研助理”；三类直接成本“信息分散难检索 / 截止时间、地点、提交物、承诺容易遗漏 / 手动摘录到日历、待办和提醒，重复操作多”。
- **Images**: ai_scene_gallery_dorm.png；mofei_overwhelmed.png。
- **Motion suggestion**: 截图瀑布快速落下，莫斐被压扁后从边缘弹出，三处时间最后被逐一高亮。

#### Slide 03 - 桌面上也装过不少 AI

- **Audience move**: 认为“下载 AI 应用”就能解决问题 → 看到额外入口导致低复用
- **Layout**: 大手机屏幕占页面中心，屏幕背景是抽象应用图标；少量使用记录与莫斐探头在外层单独编辑。
- **Title**: 工具很多，但使用停在第一次
- **Core message**: 每次都要想起、打开、输入和选择功能，使用自然会中断。
- **Content**: “首次打开：1 次”“最近使用：28 天前”；短句“后来连图标放在哪一页都忘了。”；使用中断链“想起工具 → 打开应用 → 输入内容 → 选择功能”；替代方案差异“OCR 辅助工具偏识别、任务编排弱 / AI 待办管理清晰但依赖手动输入 / 屏幕记忆上下文强但学习成本高”；随手办定位“截图理解 → 行动卡生成 → 提醒落地”。
- **Images**: ai_screen_ai_apps.png；mofei_peek.png。
- **Motion suggestion**: 图标成组出现后逐渐降饱和，莫斐从最底一排探出并做夸张左右张望。

#### Slide 04 - 随手办从截图之后开始

- **Audience move**: 以为产品需要新入口 → 理解原操作不变、系统在截图之后接续
- **Layout**: 手机外壳与群聊屏幕分层；截图闪光后底部出现识别提示，莫斐在手机外侧接住截图。
- **Title**: 照常截图，后面的事继续发生
- **Core message**: 用户仍按原习惯截图，随手办只在发现行动信息时提示。
- **Content**: “识别到 1 项课程任务”“周五 23:59 截止”“查看 / 忽略”；产品名小幅出现；作品概述“面向大学生与高频信息处理人群，以截图为统一入口”；结构化字段“时间 / 地点 / 截止时间 / 提交物 / 任务类别”；落地能力“联动日历、提醒与任务清单”。
- **Images**: ai_screen_groupchat.png；mofei_catch.png。
- **Motion suggestion**: 截图闪光→提示自底部上移→莫斐高速倾斜接住截图→任务草稿从轨道另一侧出现。

### Part 2: 从截图到行动

#### Slide 05 - 三张截图，三种行动

- **Audience move**: 把所有截图视为同一种信息 → 理解内容会进入不同处理路径
- **Layout**: 三个截图源直接对应三种行动字段，不使用三张大卡片容器；颜色只标识类型。
- **Title**: 同样是截图，后面是不同的事
- **Core message**: 课程通知、活动海报和聊天约定需要不同的后续处理。
- **Content**: “课程通知 → 任务”“活动海报 → 事件”“聊天约定 → 承诺”；例子：课程报告、辩论赛宣讲、和室友去图书馆；字段差异“任务：截止时间 + 提交物 / 事件：时间 + 地点 / 承诺：人物 + 约定内容”。
- **Motion suggestion**: 三个截图依次翻转为字段组，莫斐轨道分别指向红、蓝、橙三条路径。

#### Slide 06 - 用户真正需要做的只有确认

- **Audience move**: 担心要等待完整 AI 流程 → 理解本地草稿先出现、复杂补全在后台
- **Layout**: 横向六步流程；“确认”节点放大；屏幕、按钮和莫斐均为独立对象。
- **Title**: 先有草稿，再由用户确认
- **Core message**: 本地立即给出可看的结果，后台补全不阻塞用户。
- **Content**: “截图 → OCR 识别 → 本地草稿 → 语义补全 → 动作预览 → 用户确认 → 日历 / 提醒 / 清单”；“立即出现”“后台进行”“确认并保存”；动作预览展示“日历事件、提醒策略、潜在冲突”，且提醒可手动调整。
- **Images**: mofei_scan.png；mofei_confirm_burst.png。
- **Motion suggestion**: 流程逐段擦入；莫斐扫描时轨道高速旋转，到确认节点突然停住并放大；按钮最后出现。

#### Slide 07 - 五类行动，对应五种处理

- **Audience move**: 认为只是统一截图仓库 → 理解分类决定后续行为
- **Layout**: 五个类型沿一条处理轴分布，用文字、图标和色条表达，不使用封闭卡片墙。
- **Title**: 先分清它接下来要怎么处理
- **Core message**: 任务、事件、承诺、对比和收藏对应五种不同动作。
- **Content**: 任务“含明确截止时间和待办事项，按时完成”；事件“含确定时间和地点，进入日历”；承诺“聊天中的承诺、约定与帮忙事项，提醒兑现”；对比“保留多个候选，等待比较”；收藏“有价值但暂无明确行动，暂不执行”。
- **Motion suggestion**: 五个类型从同一点分流展开，莫斐沿轨道快速扫过并在每个节点改变表情。

#### Slide 08 - 拿不准时，先让用户看一眼

- **Audience move**: 担心 AI 擅自决定模糊时间 → 理解置信度越低处理越保守
- **Layout**: 一条连续置信度刻度贯穿页面，高/中/低结果直接落在刻度上。
- **Title**: 越不确定，越不会替用户决定
- **Core message**: 高置信直接生成，中置信等待确认，低置信暂存收藏。
- **Content**: 0.91“周五 23:59”；0.63“周末前”；0.34“下次有空再看”；蓝心大模型对模糊时间进行消歧，无法确定时标记“待确认”，同时给出卡片类型、优先级、标签与提醒建议。
- **Images**: mofei_question.png。
- **Motion suggestion**: 刻度从高到低展开；莫斐在中段急停、歪斜并显示夸张问号表情；黄色字段脉冲两次。

#### Slide 09 - 一张海报里，可能不止一件事

- **Audience move**: 以为一张图只对应一个时间 → 看到多步骤遗漏风险
- **Layout**: 左侧无字海报底图叠加可编辑活动文字；右侧三个手绘式圈选框直接标“报名、材料、比赛”。
- **Title**: 一张海报，藏着三件事
- **Core message**: 只记比赛时间，报名和材料提交仍会被漏掉。
- **Content**: “本周五前报名并缴费”“赛前三天提交自我介绍”“下周六上午正式比赛”。
- **Images**: ai_poster_debate.png；mofei_in_app_focus_f01.png。
- **Motion suggestion**: 海报先出现，三处圈选依次画出；莫斐轨道每次扫到一处就发生一次夸张转向。

#### Slide 10 - 它会把一张图拆成几件事

- **Audience move**: 看见多信息问题 → 理解 ActionGraph 的拆分与依赖价值
- **Layout**: 延续海报视觉，右侧三条行动依次展开并由细连接线表达先后关系。
- **Title**: 报名、交材料、比赛，分别成行动
- **Core message**: 随手办把海报拆成独立行动，再保留它们的顺序和上下文。
- **Content**: “报名并缴费 / 周五截止”“提交自我介绍 / 比赛前三天”“参加辩论赛 / 周六 09:00 / 三教报告厅”；小标签“ActionGraph”；每个行动保留“标题 / 时间 / 地点 / 提交物或费用 / 原图来源 / 前后依赖”。
- **Images**: ai_poster_debate.png；mofei_split.png。
- **Motion suggestion**: 海报向左收缩，莫斐瞬间拉长并旋转，三条行动像被切开一样依次飞出；连接线最后绘制。

### Part 3: AI 机制与个性化

#### Slide 11 - 后台不是一个模型读完整张图

- **Audience move**: 把系统理解为单次黑盒问答 → 理解多模块并行与字段仲裁
- **Layout**: 左侧原始截图，中部六个无容器模块名围绕莫斐，右侧字段仲裁与结果。
- **Title**: 不同问题，交给不同模块
- **Core message**: 分类、时间、人物地点、金额链接、重复检测和风险检查并行给出证据。
- **Content**: 端到端闭环“原始截图 → OCR 文本提取 → 蓝心大模型语义理解 → 结构化行动卡 → 动作执行”；六模块并行；示例“赛前三天 → 8 月 13 日”“三教报告厅”“报名费 30 元”；“字段仲裁 → 任务 / ActionGraph”；蓝心大模型承担“语义理解、意图调度与多应用编排”。
- **Images**: mofei_scan.png。
- **Motion suggestion**: 六条输入同时发出，莫斐轨道高速扫描，证据线汇入字段仲裁；输出结果最后定格。

#### Slide 12 - 用户改过一次，下一次就不必重来

- **Audience move**: 把修改视为系统失败 → 理解修改会形成可控的本地偏好
- **Layout**: 三次时间线，修改字段用蓝色手写效果；第三次莫斐给出预填结果。
- **Title**: 修改一次，下一次少改一点
- **Core message**: 系统从同类场景的用户修改中学习预填习惯。
- **Content**: 第一次“周六 18:00 → 周日 23:59”；第二次“周日 18:00 → 周日 23:59”；第三次“周日 23:59 → 直接确认”；课程、社团、比赛、考试等标签模板复用同类修改，减少重复设置。
- **Images**: mofei_learn.png。
- **Motion suggestion**: 三次记录逐列出现；每次修改数字先划掉再写入；莫斐在第三次快速旋转并弹出对勾表情。

#### Slide 13 - 这些习惯只保存在本地

- **Audience move**: 担心个性化等于上传画像 → 理解偏好边界与云端职责
- **Layout**: 手机边界占页面中心，莫斐与三条偏好位于边界内；云端图标在外且仅虚线连接。
- **Title**: 莫斐记住的偏好，只留在手机里
- **Core message**: 常用时间、提醒方式和地点偏好保存在本地，云端不获取完整画像。
- **Content**: “张老师的作业 → 周日 23:59”“课程任务 → 提前 1 天提醒”“常用地点 → 三教 201”“仅保存在本机”。
- **Images**: mofei_guard.png。
- **Motion suggestion**: 三条偏好从外部飞入手机边界，莫斐四个锚点迅速扩张形成保护姿态；云端虚线随后变淡。

#### Slide 14 - 用得越久，需要修改的地方越少

- **Audience move**: 关注生成数量 → 转向关注结果是否更接近用户真实需求
- **Layout**: 左侧可编辑折线图，右侧第一周与第四周的字段对比直接排版。
- **Title**: 真正重要的是，还要改多少
- **Core message**: 字段编辑率下降，才表示系统越来越贴近用户。
- **Content**: 示意曲线“第 1 周 42% → 第 4 周 12%”；第一周修改时间、地点、提醒方式；第四周直接确认；底部“示意数据，实际结果以测试为准”。
- **Visualization**: editable native line chart using scenario values.
- **Native-ready**: yes
- **Data class**: scenario
- **Motion suggestion**: 曲线从左向右绘制，第四周数值放大；莫斐从忙乱表情逐步转为轻松完成表情。

### Part 4: 可信边界与拓展

#### Slide 15 - 所有关键操作都留在确认之后

- **Audience move**: 担心不可追溯和自动写入 → 理解来源、修改、锁定和确认机制
- **Layout**: 左侧可编辑任务字段，右侧原图局部证据；底部三个真实按钮。
- **Title**: 能看来源，能改字段，确认后才执行
- **Core message**: 写入日历、提醒和保存都发生在用户确认之后。
- **Content**: “任务名称 ← 原图第 2 行”“截止时间 ← 原图右下角”“地点 ← 海报底部”；按钮“修改 / 确认并保存 / 忽略”；动作预览显示“日历事件 / 提醒策略 / 潜在冲突”；“后续补全不会覆盖已确认字段”。
- **Images**: mofei_confirm_burst.png。
- **Motion suggestion**: 三条来源线逐一出现，用户修改字段后锁图标落下；莫斐在确认按钮旁急停并等待点击。

#### Slide 16 - 没网、模型异常，也能先完成基本处理

- **Audience move**: 担心云端故障导致流程中断 → 理解本地基础能力和草稿保留
- **Layout**: 上方本地层、下方云端层；右侧三个异常图标共同指向“保留本地草稿”。
- **Title**: 云端失联，事情也不会消失
- **Core message**: OCR、本地拆卡、存储和提醒留在手机端，云端只补充复杂信息。
- **Content**: 手机端“OCR / 本地拆卡 / 数据存储 / 定时提醒”；云端“复杂时间解析 / 内容补全 / 多步骤关系”；异常“网络断开 / 云端超时 / 模型失败”；结果“保留本地草稿”；恢复后只补充未确认字段，不覆盖用户已确认内容。
- **Images**: mofei_offline.png；mofei_alarm.png；mofei_rest_f01.png。
- **Motion suggestion**: 云端区域短暂闪烁后变淡，本地层保持稳定；莫斐先警报震动，再收拢锚点安静守住草稿。

#### Slide 17 - 从校园截图，走向更多行动入口

- **Audience move**: 把产品限定在学生截图整理 → 看见可拓展的场景、能力和商业空间
- **Layout**: 横向四场景连续画面，中央是一条从截图识别扩展到跨应用行动编排的路径。
- **Title**: 下一步，让更多信息直接进入行动
- **Core message**: 在保持确认与本地优先的前提下，能力可拓展到会议、差旅、办事与团队协同。
- **Content**: 场景“校园通知 / 工作会议 / 差旅行程 / 公共办事”；轻量入口“截图菜单 / 悬浮球 / 快捷入口 / 语音、文字、粘贴”；OriginOS 组件化“封装为系统组件或服务”；多应用联动“日历 / 时钟 / 待办 / 便签 / 通知中心 / 相册 / 文件管理”；系统级工作流“识别截图 → 提取任务 → 生成日历事件 → 创建提醒与待办 → 同步系统卡片”；增长空间“校园 → 实习 → 办公与轻协作”；商业可能“个人效率增值 / 校园与组织服务 / 场景化合作”。
- **Images**: ai_scene_future_ecosystem.png；mofei_action_ring_full.png。
- **Motion suggestion**: 四个场景依次点亮，莫斐沿轨道高速穿越，能力标签从个人端向组织端逐级展开。

#### Slide 18 - 让截图直接进入下一步

- **Audience move**: 记住许多功能点 → 留下一个清晰、可复述的产品闭环
- **Layout**: 中央手机位置固定，截图向右依次转化为行动、日历和提醒；莫斐以最大动作完成闭环。
- **Title**: 让截图直接进入下一步
- **Core message**: 用户原来的操作没有增加，截图里的事情却能够继续往下走。
- **Content**: “从‘先截图、后遗忘’转向‘一截图、即执行’”；五项证据“截图统一入口 / 可编辑结果与动作预览 / 大学生高频场景 / 蓝心大模型适配 / 日历、提醒、待办与便签生态联动”；产品标志与“随手办”；赛事与团队身份“2026 中国大学生计算机大赛 · AIGC 创新赛｜华中科技大学 · 给我干哪来了”。
- **Images**: mofei_celebrate.png；mofei_complete_f01.png。
- **Motion suggestion**: 截图、行动、日历、提醒依次沿轨道转换；莫斐压缩到最小后弹跳放大，四个锚点向外爆发，最后定格产品名。

## X. Speaker Notes Requirements

- **Generation**: enabled
- **Filename**: match each SVG filename under `notes/`
- **Content**: 以用户原大纲的现场讲述为基础，结合当前项目源码校准；每页 25–45 秒，技术页说明因果关系，示意数据明确说明。
- **Total duration**: 8–12 minutes
- **Notes style**: conversational
- **Presentation purpose**: explain, demonstrate, persuade, and hand off
