package com.suishouban.app.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.suishouban.app.data.model.ReminderNode

class ReminderNodeConverter {
    private val gson = Gson()
    private val type = object : TypeToken<List<ReminderNode>>() {}.type

    @TypeConverter
    fun fromReminderNodes(value: List<ReminderNode>): String = gson.toJson(value)

    @TypeConverter
    fun toReminderNodes(value: String?): List<ReminderNode> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<ReminderNode>>(value, type) }
            .getOrDefault(emptyList())
    }
}
