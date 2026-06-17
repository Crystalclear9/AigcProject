package com.suishouban.app.domain

import com.suishouban.app.data.model.AnalyzeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ActionEnhancementInput(
    val ocrText: String,
    val screenshotTime: String? = null,
    val source: String = "local",
)

interface ActionEnhancer {
    suspend fun enhance(input: ActionEnhancementInput): AnalyzeResult
}

class LocalRuleActionEnhancer(
    private val extractor: LocalActionExtractor = LocalActionExtractor(),
) : ActionEnhancer {
    override suspend fun enhance(input: ActionEnhancementInput): AnalyzeResult {
        return withContext(Dispatchers.Default) {
            extractor.extract(input.ocrText)
        }
    }
}
