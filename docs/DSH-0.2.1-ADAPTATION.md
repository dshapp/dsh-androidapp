# Android DSH 0.2.1-alpha.2 适配

日期：2026-10-14（Asia/Shanghai）。参考会话 `session-834307c5-341a-4d05-bcf9-eb1940137361`，并对照其扩展适配报告与本机官方 alpha.2 类型/实现。

## 本项目范围

- 原生聊天：PTC meta/reason/error、工具 preparing 与正式调用去重、取消清理；回合完成/停止/失败，仅成功显示 runMs，完成后折叠 reasoning。
- 保留原始项目 cwd；实际工具和文件路径使用 workingDirectory/currentDirectory，工具保存调用时目录。
- external 子智能体保留目录条目，禁止本地打开；子会话翻页/重开 follow 保留父+子+mode 地址。
- 目标 activation 独立于持久化 goal。冷打开、重连、实时 activation、control goal、follow snapshot 与补洞后定向刷新。身份/版本和 epoch 防迟到响应覆盖；active+disarmed 只能显式恢复，未知 activation 不猜测。
- 移除 modeSelectionEnabled，仅有效预设决定选择入口。
- 宿主设置：预设 YAML 只读；凭证引用状态只读，不推测 derived ref 代表供应商认证。模型目录编辑限定公开 id/name/contextWindow/maxTokens，并保留其他原始字段；重置只 unset。刷新保留草稿与最初 expectedRevision，冲突不自动重试。
- 独立插件和自动化原生页面，从会话列表视图菜单进入。管理状态按 API 实例保存；离页不清空聊天草稿；管理页面退出/后台停止读取，默认前台 15 秒只读刷新，不另开第二条 $events。
- 插件官方分组、准确 installTarget、包/组件控制、精确版本风险豁免与原 requestId 安装结果恢复；不自动重装或重启。
- 自动化仅管理已有规则：catalog/list/update/delete/history，保存完整 expected，冲突保留草稿，删除确认；历史 before 为独占消息游标，记录仅证明投递，不代表模型执行完成。没有 schedule/create RPC。

## 安全与能力边界

手机不安装 DSH，不移植 Chrome daemon 更新渠道。未升级/部署/重启宿主或 bridge，未推送代码。插件 companion/React 页面及任意 JS 不在 Android 执行；设置不支持任意 schema、凭证写入或预设 YAML 修改。管理写操作由用户主动触发。已配对手机使用宿主现行 operator 权限，并非 reader 权限隔离。

## 独立 mobile bridge 兼容前置条件

本机 bridge 源码与构建包仍调用旧三参 `wireStream.open(endpoint, payload, abort.signal)`。官方 alpha.2 接口为五参 `(endpoint, payload, uplink, peer, signal)`。因此第 5 参数 signal 为 undefined；官方 `$events` 分支的 `AbortSignal.any` 会抛错，普通流也会丢失正确取消信号。

证据：
- [bridge http.ts:488](</Users/user/dshapp/dsh-mobile-bridge/src/http.ts#L488>)
- [bridge 构建包](</Users/user/dshapp/dsh-mobile-bridge/lib/index.js#L4233>)
- [官方 gateway 类型](</usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-api-gateway/lib/types/types.d.ts#L87>)

应在 bridge 独立项目适配：传真实/空 AsyncIterable uplink，明确 peer 策略，并把 abort.signal 放第 5 参数；补 ready/emit/follow snapshot/cancel/关闭清理回归。经维护许可后再构建部署/重载。本轮未修改该仓库，未确认运行进程实际 bundle，不能把本地代码检查等同真实设备已观测故障。Android 构建和离线回归不能替代 Noise 手机链路端到端验证。

## 验证

最终统一命令：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
```

- **40 个测试套件，207 个用例通过；失败/错误/跳过均为 0。**
- Debug 和 Release 构建通过。Release 未签名，本轮不安装设备、不发布。
- lintDebug 通过：0 errors，8 warnings（保留现有备份配置/过时 SDK 判断/图标等非阻断提示）。未关闭 lint 或添加错误基线。
- git diff --check 通过。
- 新增聊天 13 项、设置 13 项、管理 15 项回归；另补目标显式恢复用例并更新预设测试。
- 完整 lint 中发现并顺手修正既有扫码 opt-in、Markdown 字符常量内嵌 BOM、图片状态生产器检查问题；图片改为显式 remember + LaunchedEffect 加载状态，新目标加载时不再展示旧图片。
- **未做真实手机端到端验证**：无已连接 Android 设备，且上述独立 bridge 五参接口适配尚未部署；不能据此宣称真实手机审批/提问/插件安装链路已通过。

报告：[单测](<../app/build/reports/tests/testDebugUnitTest/index.html>)、[lint](<../app/build/reports/lint-results-debug.html>)。
本地构建产物：[Debug APK](<../app/build/outputs/apk/debug/app-debug.apk>)、[未签名 Release APK](<../app/build/outputs/apk/release/app-release-unsigned.apk>)。
