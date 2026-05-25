package com.suishouban.app.data.repository

object EngineLabels {
    fun withPrefix(engine: String, prefix: String?): String {
        val normalized = if (engine == "local-rules") "rules" else engine
        return if (prefix.isNullOrBlank() || normalized.startsWith("$prefix+")) {
            normalized
        } else {
            "$prefix+$normalized"
        }
    }
}
