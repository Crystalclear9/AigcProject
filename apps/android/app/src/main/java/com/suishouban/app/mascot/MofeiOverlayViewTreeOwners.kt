package com.suishouban.app.mascot

import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Installs the owners Compose normally inherits from an Activity decor view.
 *
 * A WindowManager overlay has no decor tree. The owners must therefore be attached to the
 * actual root passed to WindowManager, not only to its ComposeView child: WindowRecomposer
 * resolves them from that root while the overlay is being attached.
 */
object MofeiOverlayViewTreeOwners {
    fun install(
        root: View,
        lifecycleOwner: LifecycleOwner,
        viewModelStoreOwner: ViewModelStoreOwner,
        savedStateRegistryOwner: SavedStateRegistryOwner,
    ) {
        root.setViewTreeLifecycleOwner(lifecycleOwner)
        root.setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        root.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
    }
}
