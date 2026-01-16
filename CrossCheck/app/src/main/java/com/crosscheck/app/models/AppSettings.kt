package com.crosscheck.app.models

data class AppSettings(
    val providers: List<ApiProvider> = emptyList(),
    val queryOrder: List<Int> = listOf(0, 1, 2) // Indices into providers list for 1st, 2nd, 3rd query
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
}
