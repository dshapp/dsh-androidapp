# 安卓端 DSH 0.1.7 适配清单

依据：Mac 端会话 `session-606a79f9-ed0b-4850-aa9a-74cda520130d`（仓库 `dsh-macapp`）的提交
`b49ae4f feat: 适配 dsh 0.1.7-alpha.2`。安卓端是同一套后端的手机客户端（经 `dsh-mobile-bridge` 的 Noise 隧道），
所以 Mac 那次的协议层适配这里基本要原样做一遍。桌面专属的部分这次不需要：预设文档读写/复制/删除、
设置命名空间改名、插件清单的 trust。

每一条 wire 都在 `deepseek-harness` 的 `dsh-v0.1.7-alpha.2` 源码里核对过，也在本机 3080（0.1.7-alpha.2）上用 curl 实测过。

## 一、0.1.7 的 wire 变化与安卓的改法

| # | 变化 | 0.1.6 | 0.1.7 | 安卓改法 |
| - | ---- | ----- | ----- | -------- |
| 1 | 后台任务 | `session/control` 基线里的 `jobs`，外加一条 `jobs` 帧 | 独立流 `job/list {request:{sessionId}}`，帧 `{type:'rows', jobs}`；control 基线只剩 `projections` | follow 时另订一条 `jobs:<id>` 流；`decodeJobListFrame`；删掉 control 里的 jobs |
| 2 | 停止任务 | 没有 | `job/kill {request:{sessionId,jobId}}` | `jobKill`，任务列表里正在跑的任务加一个停止按钮 |
| 3 | 工具结果 | `content:[{type:'tool-result',toolCallId,content,isError}]` | `role:'tool'` 消息：`toolCallId`、`isError` 挂在消息上，`content` 就是结果本身 | `applyToolResult` 改读消息字段；删掉 `ContentBlock.ToolResult` |
| 4 | 压缩检查点 | `source.plugin == "compact"` | `source.kind == "compact-checkpoint"` | 改判定条件；未知来源的标签去掉 `plugin:` 前缀 |
| 5 | 子智能体目录 | `subagents/list` | 删除（404）；改读 `session/projections {request:{sessionId}}` 返回的 `values.subagentCatalog:[{id,createdAt,mode,label?}]` | `subagentCatalog()`：运行状态取子会话自己的摘要，父会话是否在线取 `SessionSummary.agentAvailable` |
| 6 | 子会话模式 | one-shot / continuable | 新增 `unknown` | `SubagentMode.UNKNOWN`，这类子会话只读 |
| 7 | `subagents/prompt` | 不需要额外字段 | `request` 必须带 `requestId`（uuid）和 `delivery:'queue'\|'steer'` | 补上这两个字段；不带的话主机会报 `gateway/input-invalid` |
| 8 | Agent 预设 | `trust`、`authorable`、`hasDocument` | 都删了；内置预设不发 `name`；`code` 改名为 `ptc` | `isBuiltIn = name == null && id ∈ {standard, ptc, minimal, cordis}`；「自定义」角标改按 isBuiltIn 判断 |
| 9 | 归档仍在跑的会话 | 直接归档 | 报错 `workspace/session-active` | 弹原生确认框，确认后带 `stopActivity:true` 重试 |
| 10 | 置顶 | 客户端本地保存 | 主机保存：`workspace/pinSession` / `unpinSession` → `{pinnedSessionIds}`；follow 基线多了 `pinnedSessionIds`，还有新的 `pinned` 帧 | 置顶集放进 `WorkspaceStore`，删掉 SharedPreferences 里的本地置顶 |
| 11 | 二进制结果 | JSON 里放 base64 的 `data` | `multipart/form-data`：`metadata` 一段放信封，`value.data:null`，字节放在 `attachments[].part` 指定的那一段 | `ApiClient.call` 识别 multipart，把字节按 `path` 转成 base64 放回 `result.value`，模型层不用改 |
| 12 | `workspaceFiles/readBytes` 参数 | `range` 在顶层 | `options:{range, baseFile?}` | 改传 `options.range` |
| 13 | `session/fork` | `atSeq?` | `atSeq?`，含义是精确的 seq；不传就取最近一个完成轮次的前缀 | 列表里的 fork 本来就不传；消息上的 fork 传锚点 seq，不用改 |

## 二、确认不用改的部分

- `session/page`、`session/list`（行里多了 `agentAvailable`，已经接上）、`commands/list`、`fileReferences/list`、`skills/list`、
  `permissionPresets/catalog`、`session/modelCatalog`、`goals/*`、`session/updateQueue`、`agentPresets/select`、
  `workspace/unarchiveSession`、`subagents/interruptByParent`。
- `workspaceFiles/read` 的 offset 仍然从 1 开始计。
- 远端失效事件的名字没变：`agent-preset/selected`、`settings/document-updated`、`llm/adapters-updated`、`permission-presets/catalog-changed`。
- 桥接：`/api/<ns>/<method>` 的 POST 统一放行，multipart 响应也是原样透传，不需要新增 allowlist。

## 三、验证情况

- `./gradlew :app:compileDebugKotlin`、`:app:testDebugUnitTest` 都通过。旧测试已跟着改：
  - `ToolResultLinkageTest` 的夹具换成 0.1.7 的 `role:'tool'` 形状；
  - `BlankSessionReuseTest` 去掉了 `trust`；
  - `ContentBlockRoundTripTest` 删掉了 tool-result 相关用例。
- 新增 `BinaryResponseTest`：夹具是 0.1.7-alpha.2 主机原样返回的 readBytes multipart 响应，用来验证字节能按 path 放回 `data`。
- 已装到模拟器 `Pixel7_API34`，能正常启动，会话列表显示缓存。但本机桥接当前处于 `disabled`（操作者关掉了手机访问），
  模拟器也不在已配对设备里，所以隧道连不上，端到端实测还没做。
