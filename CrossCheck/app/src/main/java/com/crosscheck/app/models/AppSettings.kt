package com.crosscheck.app.models

enum class QueryMode {
    ABC,  // All three models
    AC,   // Skip model B (stages 1 and 3 only)
    BC    // Skip model A (stages 2 and 3 only)
}

data class AppSettings(
    val providers: List<ApiProvider> = emptyList(),
    val queryOrder: List<Int> = listOf(0, 1, 2), // Indices into providers list for 1st, 2nd, 3rd query
    val queryMode: QueryMode = QueryMode.ABC,
    val utilityModel: ApiProvider? = null // For auto-naming chats
) {
    fun isValid(): Boolean {
        return providers.isNotEmpty() &&
               queryOrder.size == 3 &&
               queryOrder.all { it in providers.indices }
    }

    fun getProviderForStage(stage: Int): ApiProvider? {
        if (stage !in 0..2) return null
        val providerIndex = queryOrder.getOrNull(stage) ?: return null
        return providers.getOrNull(providerIndex)
    }

    fun getActiveStages(): List<Int> {
        return when (queryMode) {
            QueryMode.ABC -> listOf(0, 1, 2)  // All three stages
            QueryMode.AC -> listOf(0, 2)       // Skip stage 1 (model B)
            QueryMode.BC -> listOf(1, 2)       // Skip stage 0 (model A)
        }
    }

    fun shouldRunStage(stage: Int): Boolean {
        return stage in getActiveStages()
    }
}
