package com.suishouban.app.mascot

import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MofeiOverlayViewTreeOwnersTest {
    @Test
    fun windowManagerRootResolvesAllComposeOwners() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val owner = TestOwners()
            val root = FrameLayout(instrumentation.targetContext)

            MofeiOverlayViewTreeOwners.install(root, owner, owner, owner)

            assertSame(owner, root.findViewTreeLifecycleOwner())
            assertSame(owner, root.findViewTreeViewModelStoreOwner())
            assertSame(owner, root.findViewTreeSavedStateRegistryOwner())
        }
    }

    private class TestOwners : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        override val viewModelStore = ViewModelStore()
        override val lifecycle: Lifecycle = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry = savedStateController.savedStateRegistry

        init {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }
}
