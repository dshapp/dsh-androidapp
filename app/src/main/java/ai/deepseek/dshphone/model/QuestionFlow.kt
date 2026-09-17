package ai.deepseek.dshphone.model

// 提问卡片的逐题流程：与 Mac 的 `QuestionFlowState`（ComposerFlow.swift）同构。
// 一次只呈现一道题，作答后前进，最后一题凑齐整组答案再一次性提交，
// 所以这里保存的是整组草稿，而不是只有当前这道题的选择。
//
// Swift 那边是 `mutating func`，Kotlin 这边做成不可变值 + 返回新状态，
// Compose 直接把它放进 `mutableStateOf` 就能驱动重组。

/** 一道题的作答草稿。 */
data class QuestionDraft(
    val selected: List<String> = emptyList(),
    val custom: String = "",
    val skipped: Boolean = false,
) {
    /** 选了选项或写了自定义答案，就算答了。 */
    val answered: Boolean get() = selected.isNotEmpty() || custom.isNotBlank()

    /** 答了或明确跳过，这道题就不再拦着提交。 */
    val completed: Boolean get() = answered || skipped
}

/** 底部反馈文案的两种成因：当前这题没答，或整组里还有没完成的题。 */
enum class QuestionFlowValidation { UNANSWERED, INCOMPLETE }

/** 一步操作之后的新状态，以及这一步是否已经把整组问题凑齐、可以直接提交。 */
data class QuestionFlowStep(val state: QuestionFlowState, val submit: Boolean)

data class QuestionFlowState(
    val questions: List<AskUserQuestionItem>,
    val index: Int = 0,
    val drafts: List<QuestionDraft> = questions.map { QuestionDraft() },
    val validation: QuestionFlowValidation? = null,
) {
    val current: AskUserQuestionItem? get() = questions.getOrNull(index)
    val currentDraft: QuestionDraft? get() = drafts.getOrNull(index)
    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index >= questions.size - 1

    fun previous(): QuestionFlowState =
        if (index <= 0) this else copy(index = index - 1, validation = null)

    fun next(): QuestionFlowState =
        if (index + 1 >= questions.size) this else copy(index = index + 1, validation = null)

    /**
     * 选中一个选项。多选是开关，单选是替换；单选且不是最后一题时顺势翻到下一题
     * ——选完就没有别的要看了，让用户再点一次"下一题"是多余的。
     */
    fun choose(label: String): QuestionFlowState {
        val question = current ?: return this
        val draft = currentDraft ?: return this
        val updated = if (question.multiSelect == true) {
            val selected =
                if (draft.selected.contains(label)) draft.selected - label else draft.selected + label
            draft.copy(selected = selected, skipped = false)
        } else {
            QuestionDraft(selected = listOf(label))
        }
        val next = drafts.toMutableList().also { it[index] = updated }
        val advance = question.multiSelect != true && !isLast
        return copy(index = if (advance) index + 1 else index, drafts = next, validation = null)
    }

    /**
     * 选中选项，并告诉调用方整组是否就此可以提交：只有"最后一题的单选"能一键
     * 落地；中途的单选只是翻页，多选要靠提交按钮收口。若落在最后一题却还有更早
     * 的题没完成，就跳回那道题并给出提示——与提交按钮的校验同一条路径。
     */
    fun chooseSettlingBatch(label: String): QuestionFlowStep {
        val settles = isLast && current?.multiSelect != true
        val state = choose(label)
        if (!settles) return QuestionFlowStep(state, false)
        return state.validateForSubmission()
    }

    fun setCustom(value: String): QuestionFlowState {
        val question = current ?: return this
        val draft = currentDraft ?: return this
        val updated = draft.copy(
            custom = value,
            skipped = false,
            // 单选题里自填与选项互斥，免得一道题送出两种答案。
            selected = if (question.multiSelect == true) draft.selected else emptyList(),
        )
        return copy(drafts = drafts.toMutableList().also { it[index] = updated }, validation = null)
    }

    /** 跳过当前这题（= 拒答）；已是最后一题就走提交校验。 */
    fun skip(): QuestionFlowStep {
        if (currentDraft == null) return QuestionFlowStep(this, false)
        val next = drafts.toMutableList().also { it[index] = QuestionDraft(skipped = true) }
        val state = copy(drafts = next, validation = null)
        if (!state.isLast) return QuestionFlowStep(state.copy(index = index + 1), false)
        return state.validateForSubmission()
    }

    /** 主按钮："下一题"或"提交"。当前这题没答就原地提示。 */
    fun continueFlow(): QuestionFlowStep {
        val draft = currentDraft
        if (draft == null || !draft.answered) {
            return QuestionFlowStep(copy(validation = QuestionFlowValidation.UNANSWERED), false)
        }
        if (!isLast) return QuestionFlowStep(copy(index = index + 1, validation = null), false)
        return validateForSubmission()
    }

    /** 整组都完成才放行；否则跳到第一道没完成的题。 */
    fun validateForSubmission(): QuestionFlowStep {
        val missing = drafts.indexOfFirst { !it.completed }
        if (missing < 0) return QuestionFlowStep(copy(validation = null), true)
        return QuestionFlowStep(
            copy(index = missing, validation = QuestionFlowValidation.INCOMPLETE),
            false,
        )
    }

    /** 整组答案的上报编码，与 Mac 的 `responseAnswers()` 一致。 */
    fun answers(): List<QuestionAnswer> = questions.mapIndexed { position, question ->
        val draft = drafts.getOrNull(position) ?: QuestionDraft()
        if (draft.skipped) {
            QuestionAnswer(question.id, emptyList(), null)
        } else {
            val custom = draft.custom.trim()
            QuestionAnswer(
                id = question.id,
                selected = if (custom.isEmpty() || question.multiSelect == true) draft.selected
                else emptyList(),
                custom = custom.ifEmpty { null },
            )
        }
    }
}
