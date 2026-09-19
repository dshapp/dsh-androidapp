# 文件详情协议：Web 怎么拿，手机端怎么落地

## 结论：就是一条 Remote RPC

DSH Web 的"文件详情"不是 HTTP 静态资源，而是一条 Remote 方法：

```
POST /api/workspaceFiles/read
{
  "workspaceFileScopeId": "<sessionId>",
  "path": "<绝对路径或工作区相对路径>",
  "range": { "offset": <1 起的起始行> }
}
→ { absolutePath, version, bytes, offset, text, lines, eof }
```

调用链（网页端）：

1. 聊天里点一个产物提及或文件链接 → `ui-chat/apply.ts#openFile`
   用 `fileAddressFor(sessionId, cwd, path)` 拼出资源地址
   `dsh-resource://file/session/<sessionId>/<path>`（绝对路径走 `.../session/<id>//abs/path`），
   交给右侧栏 `sidebarRight.openResource(url)`。
2. 右侧栏的 `ui-sidebar-documentpreview` 认领这个地址，
   `rpc.ts#hostFileOf` 把它解析回 `{ sessionId, path }`，
   `remote.workspaceFiles.read(sessionId, path, { offset }, signal)` 按页读。
3. 每一页都带 `version`：翻到第二页以后版本变了就从第 1 行重读（`face.ts#loadPage`）。
   整文件给文档渲染器时走 `workspaceFiles.readAll`。

主机侧（`packages/api/workspace-files/src/index.ts`）：`WorkspaceFiles extends TypertRemoteService`，
`@Remote read()` 的入参类型是 `WorkspaceFileScope`，其 wire 名是 `workspaceFileScopeId`，
lookup 把它解析成会话的 `workspaceRoot`（会话头里的 `cwd`）。文件读写权限由 `fs` + `sandboxPolicy`
决定，**不**受工作区包含限制；一页默认 5000 行、2 MiB，`readAll` 上限 32 MiB。

## 安卓端原来错在哪

安卓端聊天页把 Markdown 链接交给 `LinkAnnotation.Url`（系统浏览器），而模型写的文件地址是
相对路径或 `file://`——浏览器打不开，且应用里的文件提及回调只接"本轮产出的文件"
（`TurnDeliverables.resolveMention`），普通文件链接根本走不到那里，所以点击"没有反应"。

## 安卓端现在的映射

| 环节 | 做法 |
| --- | --- |
| 识别 | `model/FileLink.kt`：`dsh-resource://file/session/<id>/<path>`、`absolute/<path>`、`file://…`、绝对路径、相对路径；行内代码额外认"目录 + 扩展名"的明确路径（`FileLink.parseInlineCode`） |
| 读取 | `RpcMethods.kt#workspaceFileRead` → `workspaceFiles/read`，`PhoneModel#openFileInSystemViewer` 连续翻页到 `eof` |
| 落地 | `FileOpener.kt`：缓存目录写一个只读文件 → `FileProvider` 出 `content://…` |
| 打开 | `ACTION_VIEW` + `text/plain` + `FLAG_GRANT_READ_URI_PERMISSION`，交给系统文本查看器 |
| 退路 | 没有可接的查看器（干净模拟器）或读取失败 → 弹窗说明原因，并可改用内置的 `FilePreviewSheet`（同一条分页协议） |
| 外部地址 | `http(s)`/`mailto` 等仍由 `LinkAnnotation.Url` 交给系统 |

手机链路（`dsh-mobile-bridge`）对 `/api/<ns>/<method>` 的 POST JSON RPC 放行，无需为
`workspaceFiles/read` 加白名单；只有 `changes.summary`/`changes.diff` 这类插件 GET 路由要单独列。
