# 安卓端 DSH 0.2.0-rc.2 适配清单

依据：Mac 端会话 `session-6b7acb1c-5cb9-43eb-8c90-b8bac35dd7fd`（仓库 `dsh-macapp`）的提交
`c710a29 feat: 适配 dsh 0.2.0-rc.2`。0.2.0 相对 0.1.7-rc.2 的 wire 面没有破坏性变化，只新增了
`userQuestions/answer`、`userQuestions/attachWait` 两个端点和 `userQuestions` 会话投影，所以安卓要做的是
**跟进 web 的新行为**。Mac 那次桌面专属的部分这里不做：dsh 版本固定、更新检查走 `next`、识别 Desktop
载体安装的 dsh 命令行（手机不装 dsh）。

## 一、改动

| # | web 0.2.0 行为 | 安卓改法 |
| - | -------------- | -------- |
| 1 | 会话搜索只匹配会话自己的标题（`titleOf`），不再回落到目录名或 sessionId；没有标题的行显示「未命名 / Untitled」 | 新增 `sessionStoredTitle`（实时标题 → 投影标题，去首尾空白）。列表搜索、归档页筛选、重命名预填都改用它。`sessionDisplayTitle` 的兜底从「cwd 目录名 → sessionId」改为 `Strings.sessionUntitled`；`sessionSearchName` 删除 |
| 2 | 模型选择（#4787）：供应商顺序 DeepSeek 账号 → DeepSeek 官方 → 其余按目录顺序；超过 4 个模型才出搜索框；`rankByName` 排序（前缀命中 → 对齐得分 → 目录顺序）；空分组隐藏 | 新增 `model/ModelSearch.kt`（`orderModelProviders` / `visibleModelGroups` / `rankByName`，打分 DP 与 web/Mac `fuzzyScore` 相同）；`ModelPickerSheet` 加搜索框、清除按钮和「没有匹配的模型」 |
| 3 | 限时提问（#5178）：请求带 `wait:{callId, timed:true}`。客户端先用 `userQuestions/attachWait` 认领，按主机给的 `remainingMs` 倒计时，到点用 `rejected` + `code:"ASK_TIMED_OUT"` 回执，agent 拿到 pending 结果后继续工作 | `QuestionRequested` 增加 `callId / continued / deadline / held` 字段。`StreamCoordinator` 认领时订阅 `question-wait:<eventId>`，倒计时由 coordinator 负责，弹层不在屏上也会按时超时。`RemoteEventOutcome.Rejected` 增加 `code`。本端应答后调用 `retireLocally`，自己撤掉认领和倒计时（应答方收不到自己那份撤回帧） |
| 4 | 用户开始作答或点「慢慢回答」后不再计时，一直等到提交或取消 | 弹层内任何改动都会调用 `holdQuestion`。状态行有三种：「N 秒后继续工作 · 慢慢回答」「会一直等你回答」「已继续工作，仍可回答」。草稿按 `question.identity` 记住，倒计时开始或按住时不会被清掉 |
| 5 | 超时后问题变成 continued，仍可回答：迟到的回答走 `userQuestions/answer`；关闭弹层只是收起，可以重新打开 | `SessionStore` 解码 `userQuestions` 投影，`continuedQuestions` 会排除已在 inbox 排队的回答和本端已经回答过的。弹层显示 `presentedQuestion`：有实时问题先显示它，否则显示第一个没被收起的 continued 问题。收起后输入框上方会出现一条「问题 · 回答」入口。回答失败（如 `REPLY_QUEUED`）时显示错误 |
| 6 | 迟到的回答在对话里显示为 `question-reply` 节点 | `source.kind == "user-question-reply"` 的消息总会生成 `TurnTrigger`，解析 `answer_to_pending_question` JSON，直接列出「问题 / 回答：…」；跳过的题显示「已跳过」 |
| 7 | 定时触发卡（#5137）：标题「定时任务」改为「自动化任务」，图标从闹钟改为时钟 | 文案改为 `Automation task / 自动化任务`，图标改为 `Icons.Default.Schedule` |
| 8 | 时长文案改为单元制（`formatRunDuration`） | `formatDuration` 输出「5s / 2m 5s / 1h 0m 3s」和「5秒 / 2分5秒 / 1小时0分3秒」，不补零。旧模板 `durationSeconds / durationMinutes` 删除 |

确认不用改的：
- **agentPresets 解除 gating**：安卓本来就没有这层限制。
- **Work details 默认值**：安卓没有这个设置，固定把已完成的轮次折叠成摘要，相当于 standard。
- **LLM replay 改用 requested model**：安卓不显示 response model。
- **RunningStatus 计时行**：Mac 加上后又按用户要求去掉了，这里也不加。
- **`productAnalytics`**：可选埋点，不接。

## 二、自测中修掉的一个既有崩溃

后台保活服务 `AgentExecutionService`：如果一轮在服务的 `onCreate` 之前就跑完了，`stopService` 会被提前调用，
系统随即抛 `ForegroundServiceDidNotStartInTimeException`，整个进程崩溃。模拟模型秒回时稳定复现。
修法：服务还没进入前台时，`stop` 只记下「不再需要」；等 `onCreate` 里 `startForeground` 完成后，再由服务自己 `stopSelf`。

## 三、验证

- `:app:compileDebugKotlin`、`:app:testDebugUnitTest` 通过，共 165 个用例。新增的 `Dsh020ParityTest` 有 7 个，覆盖时长单元制、标题口径、
  `rankByName`、供应商排序与空组隐藏、question-reply 解析、continued 问题的收起和已回答过滤、答案 payload 形状。
- 端到端：在 `Pixel7_API34` 上装了独立包名的实例（`-PprobeSuffix=.p020…`），连到一台隔离的 0.2.0-rc.2 主机，
  用模拟模型调用限时 `ask_user_question`，期限 25 秒。链路是「手机 → 本地 proxy → mobile bridge → dsh 0.2.0-rc.2」。以下都实测通过：
  - 倒计时显示「Continuing in 22s · Take time」，到点后弹层变成「Work continued — you can still answer」，模型拿到 pending 结果。
  - 点「Take time」后显示「Waiting until you answer」，超过期限 30 秒仍在等。之后作答，工具直接拿到 `{"answers":[…Postgres]}`，没有走迟到回答。
  - 收起 continued 问题后，输入框上方出现入口；迟到回答经 `userQuestions/answer` 送出，对话里显示「Reply to earlier pending questions / 用哪种数据库？ / Answer: Postgres」，
    模型收到了这条回答。
  - 7 个模型时出现搜索框：输入 `flash` 后 DeepSeek-V41-Flash、Flash Lite、Mock Flash 按前缀优先排序；输入 `zzq` 显示「No matching models.」。
  - 时长显示为「25s / 55s」。

## 四、需要另外处理：mobile bridge 打不开 `$events`

`dsh-mobile-bridge` v0.3.5 的 `src/http.ts` 里，`serveRemoteStreams` 仍按旧签名调用
`wireStream.open(endpoint, payload, abort.signal)`。主机的 `TypertGatewayWireStream.open` 现在是
`(endpoint, payload, uplink, peer, signal)`，0.1.7-rc.2 时就已经是这个签名。旧调用让 `signal` 变成 `undefined`，于是
`$events` 一打开就失败，报错 `signals[0] is not of type AbortSignal.`。

结果是经 bridge 连接的手机（安卓、iOS、小程序）**收不到任何 waterfall 和 emit**：审批、提问不会弹出，
`api-session/*` 实时状态也收不到。`session/follow` 等普通流不受影响。这次端到端自测用的是 `/tmp` 里打过补丁的 bridge 副本，
改动是 `wireStream.open(endpoint, payload, <挂起到 abort 的空 uplink>, undefined, abort.signal)`。bridge 仓库需要单独修这一处。
