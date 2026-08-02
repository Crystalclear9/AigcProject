# 随手办团队协作功能设计

## 定位

在现有单人"截图 → 行动卡"闭环之上,新增**可真实使用的演示级多人协作**:创建团队、邀请码入队、发布共同目标、AI 拆解为带分工的任务卡、成员状态互见。目标是让一个真实学生小组能跑通全流程,并为竞赛演示提供商业化叙事(个人版免费 → 团队版 AI 项目管家),不追求生产级安全与并发。

## 已确认决策

1. **轻身份**:首次启动生成设备级 `user_id`(UUID),用户仅填昵称;请求头 `X-User-Id` 标识身份。无密码、无短信、无找回。
2. **团队模型**:6 位邀请码建队/入队;两级角色 `owner`(队长,创建者自动获得)/`member`。队长可改队名、移除成员、解散团队、指派负责人;成员可创建/认领/更新任务。
3. **协作单元**:团队目标(team goal)→ AI 拆解为带负责人、里程碑、起止时间的任务卡;截图生成的卡片也可手动转入团队。
4. **项目管理元素**:里程碑、只读时间线视图、成员进度统计面板。不做审批流、依赖可视化、拖拽排期、燃尽图。
5. **同步**:团队页可见时 20~30 秒轮询(演示模式 5 秒)+ 下拉刷新;冲突按字段级 `updated_at` last-write-wins。不做 WebSocket、不做系统推送。
6. **部署**:局域网 —— 演示笔记本跑 FastAPI,手机连同一热点;复用设置页已有的 `apiBaseUrl` 可编辑配置。
7. **AI 可靠性**:LLM(`structured_completion`)优先,失败时降级到确定性目标拆解模板(比赛项目/课程大作业/小组报告等骨架),沿用 `card_refinement_graph.create_model_plan` 的 try/except 降级范式。
8. **视觉约束**:界面克制、有美感、不过度繁复;全部复用 `DS` 设计令牌与 `SoftCard` 等既有组件;**少用气泡** —— 墨斐以静默角标/状态点参与,气泡仅在 AI 拆解完成时出现一次。
9. **文档产出**:仅工程文档(本设计文档 + `COMPETITION_DEMO.md` 新增一幕),不级联更新 PRD。

## 后端设计(services/api)

### 数据模型

在 `app/db/connection.py` 的 `_ensure_schema_locked()` 中按既有幂等模式新增:

```sql
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    nickname TEXT NOT NULL,
    avatar_color TEXT NOT NULL DEFAULT 'blue',
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS teams (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    invite_code TEXT NOT NULL UNIQUE,   -- 6 位大写字母数字,去除易混淆字符
    owner_id TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS team_members (
    team_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'member',  -- owner | member
    joined_at TEXT NOT NULL,
    PRIMARY KEY (team_id, user_id)
);
CREATE TABLE IF NOT EXISTS team_goals (
    id TEXT PRIMARY KEY,
    team_id TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    due_date TEXT,
    status TEXT NOT NULL DEFAULT 'active',  -- active | done | archived
    decompose_source TEXT NOT NULL DEFAULT 'template',  -- llm | template
    created_by TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS milestones (
    id TEXT PRIMARY KEY,
    goal_id TEXT NOT NULL,
    title TEXT NOT NULL,
    due_date TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0
);
```

cards 表追加两列(进 ALTER 迁移字典):`milestone_id TEXT`、`updated_at TEXT`(创建时等于 `created_at`,每次 update/complete 刷新;老数据回填为 `created_at`)。团队任务卡即 `workspace_type='team'`、`workspace_id=<team_id>` 的普通卡,复用全部既有字段。

### 端点(新增 `app/api/endpoints/teams.py` + `app/schemas/team.py` + `app/repositories/teams.py`)

身份:除 `POST /users` 外,团队端点通过 `X-User-Id` 请求头识别调用者(`fastapi.Header`);未注册或非成员返回 403/404。这是演示级信任模型,不做签名防伪。

- `POST /users` 注册轻身份;`PATCH /users/{id}` 改昵称
- `POST /teams` 建队(生成邀请码,创建者写入 members 为 owner)
- `POST /teams/join` 凭邀请码入队
- `GET /teams` 我加入的团队列表;`GET /teams/{id}` 详情(含成员与角色)
- `PATCH /teams/{id}` 改名、`DELETE /teams/{id}` 解散、`DELETE /teams/{id}/members/{uid}` 移除成员 —— 仅 owner
- `POST /teams/{id}/goals` 创建目标并触发 AI 拆解,返回**拆解预览**(暂不落卡);`POST /teams/{id}/goals/{gid}/confirm` 队长确认(可修改分工)后批量落卡
- `GET /teams/{id}/summary?since=<iso>` 轮询聚合接口:目标 + 里程碑完成度 + 成员统计 + `since` 之后有变更的卡片增量
- 卡片更新沿用 `PATCH /cards/{id}`,repository 比较请求携带的 `updated_at` 基线,整体采用 last-write-wins:写入方无条件覆盖并刷新 `updated_at`,轮询方以服务端为准

### AI 目标拆解(新增 `app/services/team_goal_service.py` + `team_goal_templates.py`)

输入:目标标题/描述/截止日期 + 成员名单。输出结构:里程碑列表 + 任务列表(标题、负责人、里程碑归属、起止日期、交付物)。

1. **LLM 路径**:`structured_completion("fast_model", system_prompt=团队协调者提示词, payload, schema=拆解 JSON Schema)`,温度 0。
2. **模板兜底**:任何异常(未配置、超时、schema 不符)降级到 `team_goal_templates.py`:按关键词匹配"比赛/竞赛"、"大作业/课设"、"报告/论文"、"活动/晚会"四类骨架,按截止日期倒排里程碑、按成员轮转分配任务;完全无匹配时用通用三段骨架(拆解 → 执行 → 收尾)。响应标记 `decompose_source`,前端可如实展示"规则拆解"。

### 测试

沿用 `tmp_path` 换 `settings.database_path` 的既有夹具模式,新增 `test_teams.py`:建队/入队/权限(非 owner 踢人 403)/邀请码唯一性/目标拆解模板兜底(无凭据环境自动走模板)/summary 增量/last-write-wins 覆盖顺序。

## Android 设计(apps/android)

### 身份与设置

`AppSettings` 新增 `localUserId`(首次启动生成 UUID 持久化)与 `userNickname`。团队功能首次进入时若昵称为空,页内内联填写(单输入框 + 确认,不弹全屏引导)。注册调用 `POST /users`,失败静默重试,不阻塞本地使用。

### 数据层

- Room v7 → v8 迁移:`team_workspaces` 增列 `invite_code`、`owner_id`、`my_role`、`updated_at`;`team_members` 增列 `avatar_color`;cards 增列 `milestone_id`、`updated_at`。目标/里程碑/统计**不落本地表**:summary 由内存 StateFlow 持有,服务端为唯一真相源(实现比设计初稿更克制,离线时仅个人卡片可用)。
- `WorkflowDao` 补读取查询:`observeWorkspaces()`、`observeMembers(workspaceId)`、成员数聚合等 Flow。
- `SuiShouBanApi` 新增团队端点;新建 `TeamRepository`(团队/成员镜像进 Room,summary 的 `changed_cards` 复用既有卡片同步路径 upsert 进本地卡片流)。
- 轮询:团队详情页可见时立即拉取一次,之后固定 10 秒轮询(演示与真实使用取同一节奏,不设开关),`server_time` 作增量游标,离开页面即停;失败静默保留上次数据并标记 `isStale`。

### 页面结构(底部导航新增第 6 个"团队"标签)

`MainActivity` 增加 `Screen.Team`(图标 `Groups`,双字标签与现有一致)。若 6 标签在窄屏视觉过挤,降级方案是并入首页入口,但默认先做标签(演示价值优先)。

1. **团队列表页**:`ScreenTitle` + 团队 `SoftCard` 列表(队名、成员数、活跃目标进度细条);底部两个并排按钮"创建团队 / 邀请码加入",均为页内轻量对话框(单输入框),不做多步向导。
2. **团队主页**(点入团队):
   - 顶部:队名 + 成员头像行(首字圆形色块,复用 `AccentIconChip` 风格)+ 邀请码(点击复制)
   - 目标卡:目标标题 + 一根总进度条(完成卡数/总卡数)+ 截止倒计时
   - 里程碑:垂直短列表,每项一个状态点(完成实心/进行空心)+ 标题 + 日期,不加连线装饰
   - 成员统计:横向一行,每人"昵称 + 完成/总数"微型条形,不用环形图(更克制)
   - 任务列表:按里程碑分组的团队任务卡(复用 `ActionCardItem` 精简版)
3. **时间线页**(团队主页顶部切换 tab,而非独立路由):横轴周刻度、纵轴成员,任务为圆角色条(按卡类型色,今日竖线一根)。只读、横向滚动、Canvas 绘制,无手势编辑。
4. **目标创建页**(仅 owner 可见入口):目标一句话 + 可选截止日期 → 提交 → 拆解加载态(墨斐 thinking 素材静置 + 细进度条,**无气泡**)→ 预览列表(每条任务可改负责人下拉/删除)→ "确认发布"批量落卡。

### 与既有卡片流的融合

团队任务卡写入 Room 后自然出现在"卡片"页,卡片行左上加一枚 12dp 圆角团队徽标(队名首字,`MistBlue` 底)。状态更新走现有交互,`ActionCardRepository.update` 已有的远端推送通道直接生效。

### 墨斐参与(克制)

- 拆解加载态:复用现有素材以静态/低动效方式居中展示 + 一行"墨斐正在拆解目标…",无气泡;reduce-motion 时定格单帧。
- 拆解完成:页内一行安静的结果文字"已生成 N 项任务、M 个里程碑"(模板兜底时追加"· 规则拆解"),**不使用气泡**。
- 团队有新变更:不打扰(依赖 10 秒轮询自然刷新页面),不加角标、不弹提示。

## 明确不做

密码/短信/微信登录、审批流、依赖关系可视化、拖拽排期、燃尽图、WebSocket、系统推送、云端部署、PRD 级联更新、新墨斐动画资产、团队内聊天。

## 实施阶段与验证

1. **身份与团队骨架**:后端 users/teams/members + Android 团队标签页与建队/入队 → 两台设备(或设备+curl)入同一队。
2. **目标与 AI 拆解**:goals/milestones + 拆解服务(含模板兜底)+ 目标创建/预览/确认页 → 无凭据环境一句话目标生成分工卡。
3. **同步与状态回流**:summary 轮询 + cards `updated_at` + last-write-wins + 卡片页团队徽标 → B 改状态,A 数秒可见。
4. **可视化与演示**:团队主页三块(进度/里程碑/统计)+ 时间线 + 墨斐角标 + `COMPETITION_DEMO.md` 团队一幕(含热点组网步骤与模板兜底预案)。

每阶段验证:`python -m compileall app`、`pytest`、demo 场景评估保持 5/5;Android `testDebugUnitTest`(Gradle 缓存锁问题按环境阻塞处理,不视为编译失败)。
