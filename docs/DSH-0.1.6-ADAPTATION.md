# 安卓端 DSH 0.1.6 适配清单

依据：Mac 端会话 `session-83d54b24-dbb1-49dc-8a39-6249647c8829`（仓库 `dsh-macapp`）最终落地的两笔提交

- `60076a6` feat(016): 适配 DSH 0.1.6 协议，新增变更文件卡片与插件管理写操作
- `a61202f` feat: 变更文件卡片与插件组合包分页的界面实现

Mac 端这次适配的实质，是让客户端从 **DSH 0.1.5-rc.2** 抬到 **0.1.6-alpha.2**（`NpmGlobalPackages.dshVersion`）。
安卓端（`dsh-androidapp`）是同一台主机、同一套 DSH 后端的**手机客户端**：经 `dsh-mobile-bridge` 的 Noise 隧道访问
`/api/<ns>/<method>` RPC 与 `$events` / `workspace/follow` / `session/control` / `session/follow` 四条流。

所以结论很直接：**Mac 端为 0.1.6 做的协议层适配，安卓端基本要原样做一遍**；桌面专属的部分（设置分页、
插件组合包管理、内测弹窗）不需要。下面每一条都在 0.1.6-alpha.2 的安装包（`/tmp/dsh016-verify/node_modules`）
与 0.1.5-rc.2 源码（`deepseek-harness/packages`）里逐条核对过。

---

## 一、0.1.6 动了哪些与手机端有关的 wire

| # | 变化 | 0.1.5 | 0.1.6 | 安卓现状 |
| - | ---- | ----- | ----- | -------- |
| 1 | `session/control` 基线 | `value.queues` + 独立 `queue` 帧 | 只剩 `jobs` / `projections`，`queue` 帧删除 | 仍在解 `queues` / `queue` → 队列恒空 |
| 2 | 待发队列来源 | control 流 | `inbox` 会话投影 `{ "next-turn":[…], "next-step":[…] }`，元素是整条 user message | `decodeSingleProjection` 没有 `inbox` 分支 |
| 3 | `permissions` 投影 | `{ currentValue, options }` | `{ currentValue }` | options 只来自投影 → 权限菜单为空 |
| 4 | 权限预设目录 | 随投影 | 进程级 RPC `permissionPresets/catalog` + `permission-presets/catalog-changed` 事件 | 完全没有 |
| 5 | 新增 durable 事件 | — | `workspace/changes`（只带 turn，非 surface 事件） | 落 `else` 分支，无人接住 |
| 6 | 新增 HTTP 路由 | — | GET `/api/changes.summary`、`/api/changes.diff` | 没有；且 bridge 默认不放行 |
| 7 | 新增 RPC | — | `permissionPresets/catalog`、`pluginManager/*`、`workspace/unarchiveSession` | 只有 `workspace/archiveSession` |
| 8 | 新增事件类型 | — | `image/offload`（纯服务端 LLM 投影，非 surface） | 无需处理 |
| 9 | `session/page` 键名 | `records` | `records` | 已一致 |
| 10 | `session/updateQueue` | `itemId` + `action` | 形状未变 | 兼容；但 `itemId` 语义变成 inbox 消息 id |

0.1.6 默认 web 配置（`dsh-web-app` bundle）已经带上 `dsh-workspace-changes`、`dsh-client-ui-deliverables`、
`dsh-client-ui-permission-presets`，所以上面第 1、5、6 条是**必然发生**的，不是可选项。

---

## 二、P0：不改会在 0.1.6 主机上直接坏掉

### P0-1 待发队列：`session/control` 的 queue → `inbox` 投影

**现象**：连到 0.1.6 主机后，安卓端的待发队列 / 插队条永远为空。

**证据链**（安卓侧）：

- `app/src/main/java/ai/deepseek/dshphone/model/Frames.kt:195-243` —— `SessionControlFrame.Baseline` 仍读
  `v["queues"]`，`decodeControlFrame` 仍解析 `"queue"` 帧；0.1.6 基线没有该字段、也不再发该帧。
- `app/src/main/java/ai/deepseek/dshphone/state/StreamCoordinator.kt:318-352` —— `queues` map 只由基线 / queue 帧填充，
  `hydrateControlState` 从它回灌。
- `app/src/main/java/ai/deepseek/dshphone/state/SessionStore.kt:1177-1218` —— `decodeSingleProjection` 无 `inbox` 分支。
  该文件 `109-122` 的 `queue` / `queued` / `steering` 因此恒为空。
- 表现面：`app/src/main/java/ai/deepseek/dshphone/ui/screen/ComposerBar.kt:223,784-846`（QueueStrip）与
  `app/src/main/java/ai/deepseek/dshphone/ui/screen/ChatScreen.kt:106`（steering 行）永远不出现。
- `agent/inbox/spliced` 事件安卓仍在消费（`SessionStore.kt:507,574-589`），但它只维护
  `inboxByTarget` / `claimedSteeringIds`，**不**填充 `queue`，救不回这个面。

**改法**（逐条对照 Mac 的 `StreamFrames.swift` / `SessionStore.swift` / `StreamCoordinator.swift`）：

1. `Frames.kt`：新增 `SessionInboxValue`，解码 `next-turn` / `next-step` 两个整条消息列表
   （每项 `id`、`role`、`content`、`source`）；placement 本地推导：
   `next-turn → "queued"`，`next-step` 且 `source.kind == "user" → "steering"`，否则 `"context"`。
   Mac 对应 `QueuedInboxMessage` + `SessionInboxValue` + `QueuedInboxItem.rows(fromInbox:)`。
2. **保留** 0.1.5 的 `queues` / `queue` 解码路径，双版本兼容（Mac 也是这么做的：投影与 RPC 两版取值合并）。
3. `SessionStore.decodeSingleProjection` 增加 `"inbox"` → `setQueue(rows)`；
   `setQueue`（`SessionStore.kt:1552-1558`）入参从 `SessionQueueFrame` 改为 `List<QueuedInboxItem>`。
4. `StreamCoordinator`：把 `inbox` 投影镜像到 per-session 的 `inboxQueues`（基线 projections 与 `projection` 帧
   走同一处处理），`hydrateControlState` 从它回灌。否则「基线之后才创建的 store」拿不到队列 —— Mac 专门为这个
   新增了 `inboxQueues` 字段。
5. 验：0.1.5 与 0.1.6 上各排队一条 + 插队一条，QueueStrip 与 steering 行都要出现，
   点「插队」后 placement 从 `queued` 变 `steering`。

### P0-2 权限：`permissions` 投影收窄 + `permissionPresets/catalog`

**现象**：连到 0.1.6 主机后，输入框的权限下拉一个选项都没有，按钮退化成「权限」。

**证据链**：

- 0.1.6 的 `permissions` 投影 schema 是 `z.object({ currentValue: z.string() })`（`dsh-api-session-controller`）。
- `app/src/main/java/ai/deepseek/dshphone/model/Models.kt:164-173`：`PermissionSelectValue.options` 默认空表，
  解码不报错但为空 → `selectableOptions` 为空。
- `app/src/main/java/ai/deepseek/dshphone/ui/screen/ComposerBar.kt:336-350`：菜单项仅来自 `store.permissions.selectableOptions`。

**改法**（对照 Mac 的 `DomainModels.PermissionSelection` + `AppModel.refreshPermissionCatalog`）：

1. `net/RpcMethods.kt` 增加 `permissionPresets/catalog`（空参，返回 `{ options: [{ value, name, description }] }`）。
   路径 `/api/permissionPresets/catalog` 是标准 RPC（POST + JSON），mobile-bridge 的 RPC 通道直接放行，**无需**白名单。
2. 新增 `PermissionSelection(currentValue, options)`，`options = catalog ?? projection.options` —— 0.1.6 用 catalog，
   0.1.5 主机没有该 RPC（`endpoint-unknown`）时退回投影内联列表。
3. 连接建立 / 重连后拉一次 catalog 并广播给所有 store；`state/PhoneModel.kt:302-315`
   `handleRemoteInvalidation` 增加 `permission-presets/catalog-changed` 分支重拉。
4. 选权限继续走现有 `/permission <presetId>` 命令（`RpcMethods.kt:278-280`）—— 0.1.6 的 `dsh-permission-presets`
   仍在，`commands/execute` 路径不变。

---

## 三、P1：0.1.6 新能力，Mac 已接、安卓落后

### P1-1 变更文件卡片（`workspace/changes` + `/api/changes.*`）

Mac 侧：`workspace/changes` 事件只带 `turn`，再按该事件的 `seq` 去 GET `/api/changes.summary`（文件列表 + 增删行数）
与 `/api/changes.diff`（逐文件 hunk），挂到 turn tail 上（`ChangedFilesCard` + `TurnChangesStore`）。

安卓侧要做的：

1. `SessionStore.applyEvent`（`state/SessionStore.kt:503-529`）显式接住 `workspace/changes`，记录 `turn → event.seq`
   （Mac 的 `changesSeqByTurn` + `changesAddress(turn:)`）。只带 turn、无 surfaceOp，所以**不会**变成未知卡片，
   但也因此现在完全没有数据。
2. 新增 `TurnChanges` 模型（Summary / ChangedFile / Diff 三种 kind：text / binary / oversized）—— 可直接照抄
   `dsh-macapp/Sources/DSHMacKit/Models/TurnChanges.swift` 的字段。
3. `net/ApiClient.kt` 目前只有 POST RPC + `uploadFile`（`ApiClient.kt:67-129`），需要补一个 GET 助手并解析
   plain JSON；404 视为「没有记录」而不是错误（Mac 的 `changesGet` 语义）。
4. UI 落到 `ui/component/MessageRow.kt:319` 的 `TurnTailRow`（或 turn tail 邻近的产出文件行），
   按 turn 缓存（Mac 的 `TurnChangesStore` 按 turnover 缓存 summary 与 diff）。
5. **跨仓库前提**：`/api/changes.summary` 与 `/api/changes.diff` 是插件注册的普通路由，**不在**
   `dsh-mobile-bridge` 的默认白名单里（`dsh-mobile-bridge/src/http.ts:42-47` 只有 `/api/file`、
   `/api/session/uploadFileBinary`、`/api/remote.mux`）。手机链路是默认拒绝，因此必须：
   给 bridge 的 `apiAllowlist` 配置补上这两条（`apiAllowlist: ['/api/changes.summary','/api/changes.diff']`），
   或改 `DEFAULT_API_ALLOWLIST` 发新版本；否则卡片永远 404。
6. `/api/changes.open`（在主机桌面打开文件）手机端用不上，不用加。

### P1-2 归档会话恢复（`workspace/unarchiveSession`）

Mac 侧：新增 `workspace/unarchiveSession` RPC；手机端归档列表（iOS 的 `PhoneArchivedView`）补了滑动恢复，
`PhoneModel.unarchive` 恢复后重拉列表。桌面端另有独立窗口 `ArchivedSessionsView`：按标题/工作区名搜索、
每行一个取消归档、三种空态。

安卓侧现状：RPC 与行内「恢复」已接好（`net/RpcMethods.kt` 的 `workspaceUnarchiveSession`、
`state/PhoneModel.kt` 的 `unarchive`），但入口只是视图选项菜单里的底部弹层 `ArchivedSheet`：
无搜索、拿标题要退回 sessionId 兜底，而且归档时 `PhoneModel.archive` 直接 `listStore.remove`，
主机 `session/list` 带回来的归档会话会在刷新后重新冒回主列表。

改法：底部入口 + 独立页。

- `ui/screen/SessionListScreen.kt`：底部固定一行「已归档会话」入口（`onOpenArchived`）；
  视图菜单里的归档项与 `ArchivedSheet` 删除。主列表改用 `listSessionsVisible`，在渲染时
  过滤归档 id（`model/ArchivedSessions.kt`）。
- `ui/screen/ArchivedSessionsScreen.kt`（新）：独立导航页，标题栏搜索、行内取消归档、
  三种空态（归档为空 / 归档非空但摘要已不存在 / 查询无匹配），失败走 snackbar。
- `ui/App.kt`：新增 `composable("archived")` 路由。
- `net/RpcMethods.kt`：`workspaceArchiveSession` 改为返回 `{ archivedSessionIds }`；
  `state/PhoneModel.kt` 的 `archive` 应用返回集合、保留名册摘要，`unarchive` 记录
  `unarchivingIds` 做行内进度与防重复点击。

该 RPC 也是标准 RPC，bridge 直接放行。

---

## 四、P2：不需要做（确认过的）

| Mac 端适配项 | 安卓是否要做 | 原因 |
| ------------ | ------------ | ---- |
| 插件组合包管理写操作（`pluginManager/*`） | **否**（除非要在手机加插件页） | 安卓 `SettingsScreen` 只有「服务器 / 外观」，没有任何插件管理面；`pluginManager/*` 也无消费方 |
| `image/offload` 投影 | **否** | 纯服务端 LLM 投影，且非 surface 事件（`surfaceOp` 不存在），`applyUnknownSurface` 不会画卡片 |
| 去掉内测声明弹窗 | **否** | 安卓没有该弹窗；其 onboarding 只是「去 Mac 扫码配对」 |
| onboarding 按钮改「配置其他模型提供商」 | **否** | 同上，安卓 onboarding 无该按钮 |
| `dshVersion` 固定 0.1.6-alpha.2 | **否**（主机侧） | 安卓不装 dsh；由 Mac 端 pin。可选：把 `dsh-mobile-bridge` 的 `peerDependencies` 从 `>=0.1.5-rc.2` 提到 `>=0.1.6-alpha.2`（现版本服务名 `connection`/`credentials`/`typertGateway` 在 0.1.6 未变，实测可跑） |
| `session/page` 键 `records` | **已一致** | `Models.kt:228` 就用 `records` |
| `session/updateQueue` 形状 | **兼容** | 参数仍是 `sessionId/itemId/action`；但 `itemId` 要改成 inbox 里的消息 id |
| `session/list` 的 `_request.projections` 瘦身 | **无需改** | bridge 仍按 `values` 过滤，0.1.6 形状未变 |

---

## 五、任务拆分（建议执行顺序）

| 优先级 | 任务 | 主要文件 | 依赖 |
| ------ | ---- | -------- | ---- |
| P0 | inbox 投影解码 + placement 推导 | `model/Frames.kt` | — |
| P0 | store 消费 inbox、setQueue 改签名 | `state/SessionStore.kt` | 上一条 |
| P0 | inbox 镜像 + 迟到 store 回灌 | `state/StreamCoordinator.kt` | 前两条 |
| P0 | 双版本兼容（保留 queue 帧路径） | 同上 | — |
| P0 | `permissionPresets/catalog` + Selection 合并 | `net/RpcMethods.kt`、`model/Models.kt`、`state/SessionStore.kt` | — |
| P0 | catalog 拉取 / 广播 / 失效事件 | `state/PhoneModel.kt` | 上一条 |
| P1 | `workspace/changes` 接住 + turn→seq | `state/SessionStore.kt` | — |
| P1 | changes GET 助手 + 模型 | `net/ApiClient.kt`、`model/` | 上一条 |
| P1 | 变更卡片 UI 挂 turn tail | `ui/component/MessageRow.kt` | 上一条 |
| P1 | bridge allowlist 放行 changes 路由 | `dsh-mobile-bridge/src/http.ts` 或 `cordis.patch.yml` 的 `apiAllowlist` | — |
| P1 | `workspace/unarchiveSession` + 归档页恢复 | `net/RpcMethods.kt`、`state/PhoneModel.kt`、`model/ArchivedSessions.kt`、`ui/screen/SessionListScreen.kt`、`ui/screen/ArchivedSessionsScreen.kt`、`ui/App.kt` | — |

## 六、验收清单

1. 0.1.6 主机：排队 / 插队 / 编辑 / 删除队列条目全部可用，steering 行正常出现。
2. 0.1.6 主机：权限下拉列出全部预设，切换生效并回显当前值。
3. 0.1.5 主机：以上两项**不回归**（投影内联 options 与 control queue 路径仍生效）。
4. 0.1.6 主机：一轮改动文件后，turn tail 出现变更卡片，展开可见增删行数，点开可见 diff；
   无改动的一轮不出现卡片。
5. 0.1.6 主机：会话列表底部的「已归档会话」入口进入独立页，可按标题/工作区名搜索；行内取消归档后该行下线、会话回到会话列表，且归档会话不再因刷新冒回主列表。
6. 反向确认：0.1.6 的 `workspace/changes` / `image/offload` 不会在转写里画成未知 JSON 卡片。
