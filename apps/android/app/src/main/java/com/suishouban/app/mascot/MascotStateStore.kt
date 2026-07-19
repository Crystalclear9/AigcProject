package com.suishouban.app.mascot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-local snapshot shared by the foreground ViewModel and the overlay service. The overlay
 * also observes the Room card stream, so card changes remain current while the activity is gone.
 */
class MascotStateStore(initial: MascotState) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<MascotState> = mutableState

    fun update(next: MascotState) {
        mutableState.value = next
    }
}
