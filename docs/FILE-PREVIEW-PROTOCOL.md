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

点**非图片**文件链接走应用内预览：没有"先弹一次提示、再去别的 App 打开"这一步。弹层
用的是和系统提示卡片等详情卡同一种壳（`ui/component/Cards.kt#CardDetailDialog`），
正文是等宽纯文本，右上角复制、左上角关闭。

点**图片**文件链接走系统图片查看器：图片绝不能拿文本渲染，也不内置查看器。

| 环节 | 做法 |
| --- | --- |
| 识别 | `model/FileLink.kt`：`dsh-resource://file/session/<id>/<path>`、`absolute/<path>`、`file://…`、绝对路径、相对路径；行内代码额外认"目录 + 扩展名"的明确路径（`FileLink.parseInlineCode`） |
| 分流 | `model/ImageFileType.kt` 按扩展名判定（与 Web `classifyFileType` 的图片集合一致：png/jpg/jpeg/gif/svg/webp/avif/bmp/ico/tif/tiff/heic/heif） |
| 图片 | `ChatScreen.kt#openFileLink` → `SystemImageViewer.stageFile` 先在 `cacheDir/shared-images` 占一个文件 → `PhoneModel#workspaceFileBytes` 分片读回并追加写盘 → `SystemImageViewer.open` 用 `FileProvider` 换成 `content://` → `ACTION_VIEW` + 图片 MIME 交系统图片查看器 |
| 文本 | `ChatScreen.kt#openFileLink` 把 `FileLinkTarget` 交给 `preview` 状态，弹层立刻出现 |
| 加载 | 弹层出现后才开始读；首屏在途时在**弹层内部**显示转圈 + `Strings.fileLoading`，读完把正文换成纯文本 |
| 文本读取 | `RpcMethods.kt#workspaceFileRead` → `workspaceFiles/read`，`ui/screen/Sheets.kt#FilePreviewSheet` 按页翻到 `eof`（每页带 `version`，翻页时版本变了就从第 1 行重读） |
| 图片读取 | `RpcMethods.kt#workspaceFileReadBytes` → `workspaceFiles/readBytes`，每次要一片（2 MiB，等于主机 `maxBytes` 默认值），靠 `eof` 翻到读完；搬运逻辑在 `state/PhoneModel.kt#copyWorkspaceFileChunked`。**不用 `readAll`**：它上限 32 MiB，base64 之后更大，解成字节时两份同时在内存里，低端机容易 OOM |
| 失败 | 文本：读不回来 → 弹层里给出 `无法打开此文件` + 原因 + 重试；正文已经出来后再失败只在末尾追加原因，正文留着。图片：读不回来、超过 `SystemImageViewer.MAX_BYTES`(64 MiB)、或系统没有图片应用 → 删掉半截缓存文件，只弹一条 Snackbar（`无法打开这张图片`），**不回落到文本预览** |
| 外部地址 | `http(s)`/`mailto` 等仍由 `LinkAnnotation.Url` 交给系统默认浏览器 |

系统查看器只用在图片上：`AndroidManifest.xml` 里的 `FileProvider`（`${applicationId}.fileprovider`）
与 `res/xml/file_paths.xml` 只为 `cacheDir/shared-images` 服务，非图片文件不经过它。
缓存目录只留最近 8 个文件，落盘名带一段完整路径的哈希（`stagedImageFileName`），
所以不同目录下的同名图片不会互相覆盖。
`FileOpener.kt`、`FileLinkOpenDialog.kt`、`PhoneModel#openFileInSystemViewer`
（连同整份文件的连续翻页与文本上限）仍然不存在。

手机链路（`dsh-mobile-bridge`）对 `/api/<ns>/<method>` 的 POST JSON RPC 放行，无需为
`workspaceFiles/read` 加白名单；只有 `changes.summary`/`changes.diff` 这类插件 GET 路由要单独列。
