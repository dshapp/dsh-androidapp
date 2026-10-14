package ai.deepseek.dshphone.model

/** alpha.2 removes deployment mode selection flags; only usable options matter. */
data class AgentPresetRoster(
    val presets: List<AgentPresetEntry>,
    val pickerEnabled: Boolean,
)

fun AgentPresetListValue.toRoster(): AgentPresetRoster =
    AgentPresetRoster(presets = presets, pickerEnabled = presets.any { it.broken == null })
