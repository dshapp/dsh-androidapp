package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.toRoster
import ai.deepseek.dshphone.net.decodeOrNull
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `agentPresets/list` 的模式选择策略。
 *
 * 主机（`dsh-agent-presets` 的 `remoteExportList`）发布 `modeSelectionEnabled`：
 * 部署把它关掉时新会话统一走部署默认模式。Web 收起选择器（`showPicker`）并清掉
 * 暂存的选择，Mac/iOS 的 `agentPresetPickerEnabled` 也不给入口。
 *
 * 手机端曾经只读 `.presets`，这个开关被整个丢掉：关掉策略的部署上仍然摆出预设
 * 选择器。主机侧 `agentPresets/select` **不校验**该策略，所以它不只是多一个入口，
 * 而是真能把预设选上去、绕过部署的默认边界。
 */
class AgentPresetRosterTest {

    @Test
    fun disabledPolicyHidesThePicker() {
        val roster = Json.parseToJsonElement(
            """{"presets":[{"id":"code","trust":"system","isDefault":true}],"authorable":false,"modeSelectionEnabled":false}"""
        ).decodeOrNull<ai.deepseek.dshphone.model.AgentPresetListValue>()!!.toRoster()
        assertFalse("a deployment that disabled mode selection must hide the picker", roster.pickerEnabled)
        assertEquals(1, roster.presets.size)
    }

    @Test
    fun enabledPolicyKeepsThePicker() {
        val roster = Json.parseToJsonElement(
            """{"presets":[],"authorable":true,"modeSelectionEnabled":true}"""
        ).decodeOrNull<ai.deepseek.dshphone.model.AgentPresetListValue>()!!.toRoster()
        assertTrue("mode selection must stay on when the host enables it", roster.pickerEnabled)
    }

    @Test
    fun olderHostWithoutTheFieldDefaultsToEnabled() {
        val roster = Json.parseToJsonElement("""{"presets":[{"id":"standard"}],"authorable":false}""")
            .decodeOrNull<ai.deepseek.dshphone.model.AgentPresetListValue>()!!.toRoster()
        assertTrue("a 0.1.5 host must keep the picker", roster.pickerEnabled)
        assertEquals("standard", roster.presets.single().id)
    }
}
