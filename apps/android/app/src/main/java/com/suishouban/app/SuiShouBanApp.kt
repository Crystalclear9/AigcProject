package com.suishouban.app

import android.app.Application
import com.suishouban.app.data.repository.ActionCardRepository
import com.suishouban.app.data.repository.AppSettingsRepository
import com.suishouban.app.data.repository.NotificationCandidatePolicy
import com.suishouban.app.data.repository.NotificationCandidateRepository
import com.suishouban.app.data.repository.CardRefinementRepository
import com.suishouban.app.data.repository.UserProfileRepository
import com.suishouban.app.data.local.AppDatabase
import com.suishouban.app.ocr.TextRecognitionService
import com.suishouban.app.reminder.CalendarSyncer
import com.suishouban.app.reminder.ReminderScheduler
import com.suishouban.app.reminder.PriorityCalibrationWorker
import com.suishouban.app.mascot.MascotAnimationHint
import com.suishouban.app.mascot.MascotColorRole
import com.suishouban.app.mascot.MascotMood
import com.suishouban.app.mascot.MascotState
import com.suishouban.app.mascot.MascotStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SuiShouBanApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
    lateinit var notificationCandidateRepository: NotificationCandidateRepository
        private set
    lateinit var userProfileRepository: UserProfileRepository
        private set
    lateinit var cardRefinementRepository: CardRefinementRepository
        private set
    val mascotStateStore = MascotStateStore(
        MascotState(
            mood = MascotMood.IDLE,
            userMessage = "墨斐正在待命",
            colorRole = MascotColorRole.DEFAULT,
            animationHint = MascotAnimationHint.BREATHE,
        ),
    )

    override fun onCreate() {
        super.onCreate()
        settingsRepository = AppSettingsRepository(this)
        cardRepository = ActionCardRepository(this, settingsRepository)
        textRecognitionService = TextRecognitionService()
        reminderScheduler = ReminderScheduler(this)
        calendarSyncer = CalendarSyncer(this)
        userProfileRepository = UserProfileRepository(AppDatabase.get(this).userProfileDao())
        cardRefinementRepository = CardRefinementRepository(
            context = this,
            dao = AppDatabase.get(this).cardRefinementDao(),
            settingsRepository = settingsRepository,
            profileRepository = userProfileRepository,
            reminderScheduler = reminderScheduler,
            textRecognitionService = textRecognitionService,
        )
        notificationCandidateRepository = NotificationCandidateRepository(
            dao = AppDatabase.get(this).notificationCandidateDao(),
            policy = NotificationCandidatePolicy(packageName),
        )
        PriorityCalibrationWorker.schedule(this)
    }
}
