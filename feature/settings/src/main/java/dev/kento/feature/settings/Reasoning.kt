package dev.kento.feature.settings

import dev.kento.core.ui.Reasoning
import dev.kento.data.settings.Settings

/** The levels Refuseless publishes for the selected model, for the model picker. */
internal fun Settings.reasoning() = Reasoning(levels = levels(model), defaultLevel = option()?.defaultEffort)
