package com.douai.module.ui.miuix

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.setViewTreeNavigationEventDispatcherOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Owns only the module view tree; host AndroidX owners are never cast or replaced. */
class HostComposeEnvironment(
    private val activity: Activity,
    onBack: Runnable,
) : AutoCloseable, LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner,
    OnBackPressedDispatcherOwner, NavigationEventDispatcherOwner,
    Application.ActivityLifecycleCallbacks, ComponentCallbacks {

    init {
        checkMainThread()
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private var closed = false
    private var attached = false
    private var hostState = Lifecycle.State.RESUMED

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    override val onBackPressedDispatcher = OnBackPressedDispatcher {
        if (!closed) onBack.run()
    }

    private var navigationBridgeAttached = false
    private val navigationBridge: OnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (!closed) navigationInput.backCompleted()
        }

        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            if (!closed) navigationInput.backStarted(backEvent.toNavigationEvent())
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            if (!closed) navigationInput.backProgressed(backEvent.toNavigationEvent())
        }

        override fun handleOnBackCancelled() {
            if (!closed) navigationInput.backCancelled()
        }
    }
    private val navigationInput: ModuleNavigationInput = ModuleNavigationInput { hasEnabledHandlers ->
        if (!closed) {
            navigationBridge.isEnabled = hasEnabledHandlers
            if (hasEnabledHandlers && navigationBridgeAttached) {
                // A newly opened Miuix overlay receives back before the underlying page.
                // Re-registering only our bridge preserves ordering among existing callbacks.
                navigationBridge.remove()
                onBackPressedDispatcher.addCallback(this, navigationBridge)
            }
        }
    }
    override val navigationEventDispatcher: NavigationEventDispatcher = NavigationEventDispatcher {
        // The fallback must not re-enter OnBackPressedDispatcher and its active bridge.
        if (!closed) onBack.run()
    }.also { it.addInput(navigationInput) }

    private val viewContext = HostComposeContext.create(activity)
    private var ownedView: ComposeView? = null

    private val attachmentListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            if (closed) return
            attached = true
            lifecycleRegistry.currentState = hostState
        }

        override fun onViewDetachedFromWindow(view: View) {
            close()
        }
    }

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun context(): HostComposeContext = viewContext

    /** Each environment belongs to one view, including rows within the same dialog. */
    fun attach(view: ComposeView) {
        checkMainThread()
        check(!closed) { "The host Compose environment is already closed" }
        check(ownedView == null) { "Each host Compose environment must own exactly one view" }
        check(view.context === viewContext) { "The Compose view must use the module resource context" }
        ownedView = view
        if (view.id == View.NO_ID) view.id = View.generateViewId()
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeOnBackPressedDispatcherOwner(this)
        view.setViewTreeNavigationEventDispatcherOwner(this)
        onBackPressedDispatcher.addCallback(this, navigationBridge)
        navigationBridgeAttached = true
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.addOnAttachStateChangeListener(attachmentListener)
        activity.application.registerActivityLifecycleCallbacks(this)
        activity.application.registerComponentCallbacks(this)
        if (view.isAttachedToWindow) attachmentListener.onViewAttachedToWindow(view)
    }

    /** Call after the dialog is created; its existing dismiss listener must also call close(). */
    fun bindBackNavigation(dialog: Dialog) {
        checkMainThread()
        check(!closed) { "The host Compose environment is already closed" }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK || closed) {
                false
            } else {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    onBackPressedDispatcher.onBackPressed()
                }
                true
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            dialog.window?.let { onBackPressedDispatcher.setOnBackInvokedDispatcher(it.onBackInvokedDispatcher) }
        }
    }

    override fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        attached = false
        activity.application.unregisterActivityLifecycleCallbacks(this)
        activity.application.unregisterComponentCallbacks(this)
        val view = ownedView
        ownedView = null
        navigationBridgeAttached = false
        navigationBridge.remove()
        view?.removeOnAttachStateChangeListener(attachmentListener)
        view?.setViewTreeNavigationEventDispatcherOwner(null)
        try {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        } finally {
            try {
                // NavigationEventHandler DisposableEffects remove their handlers here; keep the
                // dispatcher alive until this view's composition has finished disposing.
                view?.disposeComposition()
            } finally {
                try {
                    navigationEventDispatcher.dispose()
                } finally {
                    viewModelStore.clear()
                    view?.setViewTreeLifecycleOwner(null)
                    view?.setViewTreeSavedStateRegistryOwner(null)
                    view?.setViewTreeViewModelStoreOwner(null)
                    view?.setTag(androidx.activity.R.id.view_tree_on_back_pressed_dispatcher_owner, null)
                }
            }
        }
    }

    private fun moveHostState(owner: Activity, state: Lifecycle.State) {
        if (closed || owner !== activity) return
        hostState = state
        if (attached) lifecycleRegistry.currentState = state
    }

    override fun onActivityStarted(activity: Activity) = moveHostState(activity, Lifecycle.State.STARTED)
    override fun onActivityResumed(activity: Activity) {
        if (!closed && activity === this.activity) onConfigurationChanged(activity.resources.configuration)
        moveHostState(activity, Lifecycle.State.RESUMED)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (closed) return
        viewContext.refreshConfiguration(activity.resources.configuration)
        // Keep the existing composition, selection, and unsaved inputs alive. Another view may
        // already have refreshed this shared context, but this owner must still receive delivery.
        ownedView?.dispatchConfigurationChanged(Configuration(viewContext.resources.configuration))
    }

    override fun onLowMemory() = Unit
    override fun onActivityPaused(activity: Activity) = moveHostState(activity, Lifecycle.State.STARTED)
    override fun onActivityStopped(activity: Activity) = moveHostState(activity, Lifecycle.State.CREATED)
    override fun onActivityDestroyed(activity: Activity) {
        if (activity === this.activity) close()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Host Compose UI must run on the main thread" }
    }

    /** Public 1.1.2 input API bridges the existing Android back dispatcher without host casts. */
    private class ModuleNavigationInput(private val enabledChanged: (Boolean) -> Unit) : NavigationEventInput() {
        override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) {
            enabledChanged(hasEnabledHandlers)
        }

        fun backStarted(event: NavigationEvent) = dispatchOnBackStarted(event)
        fun backProgressed(event: NavigationEvent) = dispatchOnBackProgressed(event)
        fun backCancelled() = dispatchOnBackCancelled()
        fun backCompleted() = dispatchOnBackCompleted()
    }
}
