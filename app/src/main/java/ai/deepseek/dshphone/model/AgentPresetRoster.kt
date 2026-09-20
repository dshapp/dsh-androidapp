package ai.deepseek.dshphone.model

/**
 * 主机侧 Agent 预设目录的可选性：一行预设 + 这个部署允不允许新会话选模式。
 *
 * 主机（`dsh-agent-presets` 的 `remoteExportList`）把 `modeSelectionEnabled` 和
 * `presets` 放在同一个返回值里；关掉时新会话统一走部署默认模式。Web 收起选择器
 * （`showPicker`）并清掉暂存的选择，Mac/iOS 的 `agentPresetPickerEnabled` 也不给
 * 入口。主机侧 `agentPresets/select` **不校验**这个策略，所以要是不看它，手机端
 * 摆出的选择器就真能把预设选上去、绕过部署的默认边界。
 *
 * 0.1.6 之前的主机不发布这个字段，缺省按允许处理（与 Mac
 * `AgentPresetListValue.modeSelectionEnabled` 的默认 true 同一口径）。
 */
data class AgentPresetRoster(
    val presets: List<AgentPresetEntry>,
    val pickerEnabled: Boolean,
)

fun AgentPresetListValue.toRoster(): AgentPresetRoster =
    AgentPresetRoster(presets = presets, pickerEnabled = modeSelectionEnabled)
