package ai.deepseek.dshphone.state

import ai.deepseek.dshphone.model.SessionAwaitingKind

/**
 * 状态排序的一档（`orderBy: "status"`）。
 *
 * [key] 就是排序键，声明顺序即产品规则，从上到下就是列表里该出现的顺序。
 * 与 Mac `SessionStatusRank` 同一套档位：把这条规则抽成纯值函数，是因为它同时
 * 被列表行与"最需要我"的排序使用，而且只有纯函数能在 JVM 单测里逐档钉住。
 */
enum class SessionStatusRank(val key: Int) {
    /** 这一轮正在跑——本会话的，或某个后代子智能体的。 */
    RUNNING(0),

    /** 用户不在场时结束、且以失败收场的一轮。 */
    FAILED_UNREAD(1),

    /** 用户不在场时结束的一轮。 */
    DONE_UNREAD(2),

    /** 工具调用停在等待用户批准。 */
    APPROVAL(3),

    /** 提问停在等待用户回答（含计划评审）。 */
    QUESTION(4),

    /**
     * 输入框里存着用户打了却没发出去的文字。
     *
     * 排在上面每一档之下：那些都是"别的东西（智能体，或只有用户能做的决定）被挡住、
     * 正在等人"，而未发出的草稿什么也没挡住——它是用户自己没做完的事，所以仍要
     * 高过一条无话可说的会话。
     */
    DRAFT(5),

    /** 无事可报；纯按最近活动排序。 */
    IDLE(6),
}

/**
 * 一条会话落在哪一档。
 *
 * 每个输入都是调用方手里已经有的纯值，所以这里不伸手去取任何 Store：调用方决定
 * 每个事实从哪来，这里只决定这些事实凑在一起意味着什么。第一个命中的档位胜出，
 * 因此同时满足多项的会话（比如正在跑、又带着一条未读完成）报最紧急的那一档。
 *
 * 与 Mac `SessionStatusOrder.rank` 逐档对齐。
 */
fun sessionStatusRank(
    isRunning: Boolean,
    isUnread: Boolean,
    didFail: Boolean,
    awaiting: SessionAwaitingKind?,
    hasDraft: Boolean,
): SessionStatusRank {
    if (isRunning) return SessionStatusRank.RUNNING
    if (isUnread) return if (didFail) SessionStatusRank.FAILED_UNREAD else SessionStatusRank.DONE_UNREAD
    when (awaiting) {
        SessionAwaitingKind.APPROVAL -> return SessionStatusRank.APPROVAL
        // 计划评审在排序档位里就是一次提问；只有行上的徽标文字区分它（web visiblePendingKind）。
        SessionAwaitingKind.PLAN_REVIEW, SessionAwaitingKind.QUESTION -> return SessionStatusRank.QUESTION
        null -> Unit
    }
    if (hasDraft) return SessionStatusRank.DRAFT
    return SessionStatusRank.IDLE
}
