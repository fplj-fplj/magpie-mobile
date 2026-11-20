package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ProviderSummary

/**
 * Enabled models come only from the saved management listing's actual exposed-model flags.
 * Empty chosen alone cannot distinguish implicit-all from select-none; models[].on can.
 * The core applies its exposure limits before setting on, so do not cap or infer picks here.
 * Shared by the provider summary and connectivity candidates. A capability run still confirms
 * the exact provider/model route against the live gateway before sending any checks.
 */
internal fun savedEnabledProviderModelIds(savedProvider: ProviderSummary?): List<String> {
    if (savedProvider == null || savedProvider.off || savedProvider.noModels || !savedProvider.ready) return emptyList()
    return savedProvider.models.asSequence()
        .filter { it.on }
        .map { it.id }
        .filter(String::isNotBlank)
        .distinct()
        .toList()
}
