package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.AgentPresetListValue
import ai.deepseek.dshphone.model.toRoster
import ai.deepseek.dshphone.net.decodeOrNull
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** alpha.2 catalog selection depends on usable entries, never removed policy flags. */
class AgentPresetRosterTest {
    private fun roster(raw: String) = Json.parseToJsonElement(raw).decodeOrNull<AgentPresetListValue>()!!.toRoster()

    @Test fun removedPolicyCannotHideUsablePresets() {
        val value = roster("""{"presets":[{"id":"standard"}],"modeSelectionEnabled":false}""")
        assertTrue(value.pickerEnabled)
        assertEquals("standard", value.presets.single().id)
    }

    @Test fun emptyAndBrokenCatalogsCannotOfferSelection() {
        assertFalse(roster("""{"presets":[],"modeSelectionEnabled":true}""").pickerEnabled)
        assertFalse(roster("""{"presets":[{"id":"broken","broken":"Invalid YAML"}]}""").pickerEnabled)
    }

    @Test fun usableOptionsRemainSelectableWithoutLegacyFlag() {
        val value = roster("""{"presets":[{"id":"broken","broken":"Error"},{"id":"standard"}]}""")
        assertTrue(value.pickerEnabled)
        assertEquals(2, value.presets.size)
    }
}
