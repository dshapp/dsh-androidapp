# 图片渲染协议：对齐 DSH Web

本文回答"Web 端是怎么渲染图片的、协议长什么样"，并说明安卓端按同一套口径做了什么。
主机版本：`@deepseek-ai/dsh` 0.1.6-alpha.2；Web 参考实现：

- `@deepseek-ai/dsh-client-ui-tool/lib/client.js`（`image-card-model.js`、`read_image` 行、`tool.call.images` 槽位）
- `@deepseek-ai/dsh-client-ui-chat/lib/client.js`（`MessageItem.js` 的 `contentParts`、`AssistantMarkdown`、`ContextInjectionRow`）
- `@deepseek-ai/dsh-api-session-controller`（`session/attachment`）

## 1. 图片在 Web 转写里出现的四个位置

| 位置 | 数据来源 | Web 的行为 |
| --- | --- | --- |
| 用户/引导气泡 | `user/message`（`source.kind == "user"`）里的 `image` 块 | 逐张渲染成相册；多张时 `compact`，右对齐 |
| 助手正文 | `assistant/message` 的 `image` 块，**连续**的图片合成一组 | `renderMessageImages({align:"start"})` |
| 工具结果 | `tool/result` / `tool/ptc-dispatch` 的内容里带 `image` 块（典型是 `read_image`） | 行内图片卡：路径标签 + 相册 + 信封文本 |
| 上下文注入 | `user/message` 且 `source.kind != "user"`（如 `plugin: tools-ptc`） | 折叠的 context 行，`image` 块按"未知块"以 JSON 形式展示（**不是**图片） |

也就是说：模型通过 `read_image` 看到的那张图，在 Web 上是从**工具卡片**（`run_code` 下面的嵌套 `read_image` 行）显示出来的，
而不是从它被注入成的 context 行。

## 2. 线上的图片块（ContentBlock）

```json
{
  "type": "image",
  "attachment": {
    "attachmentId": "sha256:8a2213d9...",
    "mediaType": "image/png",
    "bytes": 56549,
    "width": 1080,
    "height": 2400,
    "name": "emu1.png",
    "originalDimensions": { "width": 2160, "height": 4800 }
  }
}
```

- `mediaType` 只能是 `image/png` / `image/jpeg` / `image/webp` / `image/gif`（Web 的 `IMAGE_MEDIA_TYPES`）。
- `bytes` / `width` / `height` 必须是**正整数的 JSON 数字**（字符串数字不算）；`originalDimensions` 出现时也必须两个正数。
- `name` 可以缺省；出现时必须是字符串。
- **字节不在这条消息里**：host 已把图片落盘，客户端拿 `attachmentId` 去 RPC 换 base64。

同一份数据在不同消息里会出现两次：`user/message`（含 tools-ptc 注入的那条）与
`tool/ptc-dispatch` 的 `content`；两者引用同一个 `attachmentId`。

## 3. `read_image` 的识别闸门（Web `imageCardModel`）

一次调用要变成"图片卡"，必须同时满足：

1. 已结算（`tool/result` 已到）且不是错误结果；
2. 调用名就是 `read_image`，且参数里有非空白的 `file_path`；
3. 标签路径 = `meta.path`，**或**（仅当这是嵌套调用、`parentCallId` 存在时）退回调用自己的 `file_path`；
4. 结果内容只含 text / image 块（`fullyRendered`）；
5. 每个 image 块都能通过上面的字段校验（`imageReferences`，任一不合规就整体回落）；
6. 至少有一个文本块匹配信封形状（`IMAGE_ENVELOPE`）：

```
<path>/tmp/emu1.png</path>
<type>image</type>
<content>
image/png image, 1080x2400 px, 56549 bytes
</content>
```

满足后卡片 = `{ label: path, images: [attachment...], text: 所有文本块 }`。

## 4. PTC 子调用事件

`run_code` 里派发的 `read_image` 不是一条 `tool/call`，而是一对事件（0.1.5 起就叫这个名字）：

```json
{ "type": "tool/ptc-dispatch-start", "seq": 2, "data": {
  "rootCallId": "call_...", "parentCallId": "call_...", "subCallId": "call_...:ptc:1",
  "name": "read_image", "arguments": { "file_path": "/tmp/emu1.png" } } }

{ "type": "tool/ptc-dispatch", "seq": 3, "data": {
  "rootCallId": "call_...", "parentCallId": "call_...", "subCallId": "call_...:ptc:1",
  "name": "read_image", "arguments": { "file_path": "/tmp/emu1.png" },
  "isError": false,
  "content": [ { "type": "text", "text": "<path>...</path>\n<type>image</type>\n<content>\n...\n</content>" },
               { "type": "image", "attachment": { ... } } ] } }
```

Web 把它们挂成父调用下面的子节点（缩进 + 左竖线），子节点各自用自己的 toolview 渲染；
嵌套的 `read_image` 不落 `presentationMeta`，所以标签退回 `file_path`。

> 旧名 `tool/code-dispatch-start` / `tool/code-dispatch`（v2 日志）语义相同，两个名字都要收。

## 5. 取字节：`session/attachment`

```jsonc
// 请求
{ "sessionId": "session-...", "attachmentId": "sha256:8a2213d9..." }
// 响应
{ "attachment": { ...ImageAttachmentRef... }, "data": "<base64>" }
```

host 只放行"这条会话引用过"的附件，否则报 `session/attachment-invalid` / `ATTACHMENT_NOT_REFERENCED`。

## 6. 安卓端落地

| 位置 | 实现 |
| --- | --- |
| 图片块字段模型 | `model/Models.kt`：`ImageAttachmentRef` + `ImageDimensions`（`originalDimensions`） |
| 协议解析（与本文 2–3 节逐条对齐） | `model/ImageBlocks.kt`：`decodeImageRef`、`imageReferences`、`lenientImageRefs`、`imageTexts`、`fullyRendered`、`readImageCard` |
| PTC 事件 | `state/SessionStore.kt`：`tool/ptc-dispatch-start` / `tool/ptc-dispatch`（保留旧 `tool/code-dispatch*`），子调用带 `error`/`meta` |
| 工具卡 | `ui/component/Cards.kt`：`ToolCard` 画出 `read_image` 卡身（`ToolImageCard`）与嵌套子调用；`ToolResultView` 先画图片块再按 `resultView.card` 分派 |
| 气泡/正文 | `ui/component/MessageRow.kt`：`AttachmentImage`、`ImageGallery`（连续图片成组，多张缩略，用户靠右、助手靠左） |
| 取字节 | `state/PhoneModel.kt`：`attachmentImage` → `session/attachment` → base64 → `Bitmap`（`imageCache`） |

## 7. 验证

- 单测：`app/src/test/java/ai/deepseek/dshphone/ImageProtocolTest.kt`（5 条：attachment 解码、严格校验、信封闸门、
  `readImageCard` 的根/嵌套路径口径、`tool/ptc-dispatch*` 事件投影成嵌套 `read_image` 子调用）。
- 命令：`JAVA_HOME=<JDK 21> ./gradlew testDebugUnitTest --offline`。
- 真实日志样本：`session-1621444d-66a6-4897-ad69-10a1d24043dc`（`run_code` → `read_image` `/tmp/emu1.png`，
  seq 1326–1333）。

