package com.suishouban.app

import android.app.Application
import com.suishouban.app.data.repository.ActionCardRepository
import com.suishouban.app.data.repository.AppSettingsRepository
import com.suishouban.app.ocr.TextRecognitionService
import com.suishouban.app.reminder.CalendarSyncer
import com.suishouban.app.reminder.ReminderScheduler

class SuiShouBanApp : Application() {
    lateinit var settingsRepository: AppSettingsRepository
        private set
    lateinit var cardRepository: ActionCardRepository
        private set
    lateinit var textRecognitionService: TextRecognitionService
        private set
    lateinit var reminderScheduler: ReminderScheduler
        private set
    lateinit var calendarSyncer: CalendarSyncer
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = AppSettingsRepository(this)
        cardRepository = ActionCardRepository(this, settingsRepository)
        textRecognitionService = TextRecognitionService()
        reminderScheduler = ReminderScheduler(this)
        calendarSyncer = CalendarSyncer(this)
    }
}
