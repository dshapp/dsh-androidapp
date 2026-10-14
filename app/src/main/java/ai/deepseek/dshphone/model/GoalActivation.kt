package ai.deepseek.dshphone.model

import kotlinx.serialization.Serializable

/** Process-local state returned by goals/get; never cache it with durable history. */
@Serializable
data class GoalActivationView(val id: String, val revision: Int, val activation: String? = null)
