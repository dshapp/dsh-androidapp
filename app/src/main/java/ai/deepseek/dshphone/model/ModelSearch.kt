package ai.deepseek.dshphone.model

/** 目录里模型超过这个数才出搜索框（web `ModelSelect` 的阈值）。 */
const val MODEL_SEARCH_THRESHOLD = 4

/**
 * web `orderModelProviders`：DeepSeek 账号第一、DeepSeek 官方第二，其余按目录原顺序。
 */
fun orderModelProviders(groups: List<ModelProviderGroup>): List<ModelProviderGroup> {
    fun rank(id: String) = when (id) {
        "deepseek-account" -> 0
        "deepseek-official" -> 1
        else -> 2
    }
    return groups.sortedBy { rank(it.id) } // sortedBy 是稳定排序，同档保持目录顺序。
}

/**
 * 模型面板要画的分组：供应商排好序，每组的模型按查询词排名，空组隐藏。
 * 查询为空时原样返回（仍然去掉没有模型的组）。
 */
fun visibleModelGroups(groups: List<ModelProviderGroup>, query: String): List<ModelProviderGroup> {
    val trimmed = query.trim()
    return orderModelProviders(groups).mapNotNull { group ->
        val models = rankByName(group.models, trimmed) { it.name }
        if (models.isEmpty()) null else group.copy(models = models)
    }
}

/**
 * web `rankByName`：名字里按顺序含有查询词全部字符（大小写不敏感）的才保留；前缀命中在前，
 * 再按对齐得分，最后按原顺序。查询为空时原样返回。
 */
fun <T> rankByName(items: List<T>, query: String, name: (T) -> String): List<T> {
    val needle = query.lowercase()
    if (needle.isEmpty()) return items
    data class Hit<T>(val item: T, val index: Int, val prefix: Boolean, val score: Int)
    return items.mapIndexedNotNull { index, item ->
        val lower = name(item).lowercase()
        fuzzyScore(lower, needle)?.let { Hit(item, index, lower.startsWith(needle), it) }
    }.sortedWith(
        compareByDescending<Hit<T>> { it.prefix }.thenByDescending { it.score }.thenBy { it.index }
    ).map { it.item }
}

/**
 * 子序列对齐得分（与 web / Mac `fuzzyScore` 同一套 DP）：连续命中加 4，词首（开头、`-`、`_` 之后）
 * 加 8，跳过的字符按距离扣分。不是子序列时返回 null。
 */
private fun fuzzyScore(name: String, query: String): Int? {
    if (query.isEmpty()) return 0
    if (query.length > name.length) return null
    val noMatch = Int.MIN_VALUE / 4
    fun bonus(index: Int) = if (index == 0 || name[index - 1] == '-' || name[index - 1] == '_') 8 else 0
    var previous = IntArray(name.length) { noMatch }
    for (index in name.indices) if (name[index] == query[0]) previous[index] = 1 + bonus(index) - index
    for (queryIndex in 1 until query.length) {
        val current = IntArray(name.length) { noMatch }
        var bestGapped = noMatch
        for (index in name.indices) {
            val gapped = index - 2
            if (gapped >= 0 && previous[gapped] != noMatch) bestGapped = maxOf(bestGapped, previous[gapped] + gapped)
            if (name[index] != query[queryIndex]) continue
            val gain = 1 + bonus(index)
            if (index > 0 && previous[index - 1] != noMatch) current[index] = previous[index - 1] + gain + 4
            if (bestGapped != noMatch) current[index] = maxOf(current[index], bestGapped + gain + 1 - index)
        }
        previous = current
    }
    val best = previous.maxOrNull() ?: noMatch
    return if (best == noMatch) null else best
}
