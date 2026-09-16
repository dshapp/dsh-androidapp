# DSH Phone Android 移植计划

参照 iOS 端（`dsh-macapp/Sources/DSHMacKit/Phone` + 共用的协议/模型/状态层），
用 Kotlin + Jetpack Compose + Material 3 原生实现，功能/交互 1:1，UI 走 Android 规范。

## 分层对照

| iOS | Android |
| --- | --- |
| `Networking/APIClient` | `net/ApiClient.kt`（OkHttp + kotlinx.serialization） |
| `Networking/MuxStream` | `net/MuxStream.kt`（OkHttp WebSocket + 退避重连） |
| `Networking/RPCMethods` | `net/RpcMethods.kt` |
| `Models/*` | `model/*` |
| `State/SessionStore` | `state/SessionStore.kt` |
| `State/Stores` | `state/Stores.kt` |
| `State/StreamCoordinator` | `state/StreamCoordinator.kt` |
| `Phone/PhoneModel` | `state/PhoneModel.kt`（ViewModel） |
| `Phone/ServerProfileStore`（钥匙串） | `state/ServerProfileStore.kt`（EncryptedSharedPreferences） |
| `Phone/*View` | `ui/screen/*` |
| MarkdownUI | commonmark 解析 + Compose 原生渲染 |
| DataScanner 扫码 | CameraX + ML Kit 条码 |
| UNUserNotification | NotificationManager |

## UI 平台化替换

- NavigationStack → Navigation Compose
- List/Section/sidebar → LazyColumn + ListItem + 分组标题
- swipeActions → SwipeToDismissBox（左右滑）
- contextMenu → 长按 DropdownMenu
- sheet → ModalBottomSheet / 全屏 Dialog
- ContentUnavailableView → 自定义空状态（图标 + 标题 + 描述 + 操作）
- SF Symbols → Material Icons
- Dynamic Type 三档（content/chrome/metrics）→ MaterialTheme.typography bodyLarge/bodyMedium/labelMedium

## 步骤

1. 工具链与工程骨架 ✅
2. 网络层（JSON/信封/HTTP/WebSocket/RPC 方法）
3. 模型层（域模型、流帧、ChatNode、工具行模型、格式化）
4. 状态层（SessionStore 事件投影、列表/工作区/设置 Store、StreamCoordinator）
5. i18n 文案
6. UI：配对 → 会话列表 → 会话页 → 输入区 → 各类 sheet
7. 系统集成（通知、加密存储、图片、分享、扫码）
8. 构建 + 模拟器自测
