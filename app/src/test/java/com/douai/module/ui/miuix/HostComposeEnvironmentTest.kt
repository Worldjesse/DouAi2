package com.douai.module.ui.miuix

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import androidx.navigationevent.setViewTreeNavigationEventDispatcherOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostComposeEnvironmentTest {
    @Test
    fun activeModuleResourcesFollowSystemNightModeWithoutChangingHostOverrides() {
        RuntimeEnvironment.setQualifiers("+notnight")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val session = HostComposeEnvironment(activity, Runnable {})
        val view = ComposeView(session.context()).also(session::attach)
        activity.setContentView(view)
        val loader = session.context().classLoader
        val theme = activity.theme

        RuntimeEnvironment.setQualifiers("+night")
        val hostOverride = Configuration(activity.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(hostOverride, activity.resources.displayMetrics)
        session.onConfigurationChanged(hostOverride)

        assertEquals(Configuration.UI_MODE_NIGHT_YES,
            session.context().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals(Configuration.UI_MODE_NIGHT_NO,
            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertSame(theme, activity.theme)
        assertSame(loader, session.context().classLoader)
        assertSame(session, view.findViewTreeLifecycleOwner())

        activity.setContentView(View(activity))
        RuntimeEnvironment.setQualifiers("+notnight")
        session.onConfigurationChanged(activity.resources.configuration)
        assertEquals(Configuration.UI_MODE_NIGHT_YES,
            session.context().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        controller.pause().stop().destroy()
    }

    @Test
    fun moduleResourcesKeepHostActivityServicesAndStorageIdentity() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val moduleResources = Resources(
            activity.assets,
            activity.resources.displayMetrics,
            Configuration(activity.resources.configuration),
        )
        val moduleContext = object : ContextWrapper(activity) {
            override fun getResources(): Resources = moduleResources
        }
        val context = HostComposeContext(activity, moduleContext)

        assertNotSame(activity.resources, context.resources)
        assertSame(moduleResources, context.resources)
        assertSame(activity, context.baseContext)
        assertSame(activity.theme, context.theme)
        assertSame(activity.applicationContext, context.applicationContext)
        assertEquals(activity.packageName, context.packageName)
        assertSame(activity.getSystemService(Context.WINDOW_SERVICE), context.getSystemService(Context.WINDOW_SERVICE))
        assertSame(HostComposeContext::class.java.classLoader, context.classLoader)
        assertSame(context, LayoutInflater.from(context).context)
        controller.pause().stop().destroy()
    }

    @Test
    fun dialogTreeHasItsOwnOwnersAndTracksOnlyItsHostLifecycle() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val other = Robolectric.buildActivity(Activity::class.java).setup()
        val session = HostComposeEnvironment(activity, Runnable {})
        val view = ComposeView(session.context()).also(session::attach)
        val dialog = Dialog(activity)
        dialog.setContentView(view)
        dialog.setOnDismissListener { session.close() }
        dialog.show()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertSame(session, view.findViewTreeLifecycleOwner())
        assertSame(session, view.findViewTreeSavedStateRegistryOwner())
        assertSame(session, view.findViewTreeViewModelStoreOwner())
        assertSame(session, view.findViewTreeOnBackPressedDispatcherOwner())
        assertSame(session, view.findViewTreeNavigationEventDispatcherOwner())
        assertNull(activity.window.decorView.findViewTreeNavigationEventDispatcherOwner())
        assertEquals(Lifecycle.State.RESUMED, session.lifecycle.currentState)
        session.onActivityPaused(other.get())
        assertEquals(Lifecycle.State.RESUMED, session.lifecycle.currentState)

        controller.pause()
        assertEquals(Lifecycle.State.STARTED, session.lifecycle.currentState)
        assertTrue(dialog.isShowing)
        controller.stop()
        assertEquals(Lifecycle.State.CREATED, session.lifecycle.currentState)
        controller.start().resume()
        assertEquals(Lifecycle.State.RESUMED, session.lifecycle.currentState)

        dialog.dismiss()
        assertEquals(Lifecycle.State.DESTROYED, session.lifecycle.currentState)
        assertNull(view.findViewTreeLifecycleOwner())
        assertNull(view.findViewTreeNavigationEventDispatcherOwner())
        assertNull(activity.window.decorView.findViewTreeNavigationEventDispatcherOwner())
        session.close()
        other.pause().stop().destroy()
        controller.pause().stop().destroy()
    }

    @Test
    fun detachDisposesCompositionAndClearsViewModelsWithoutADismissCallback() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val session = HostComposeEnvironment(activity, Runnable {})
        val view = ComposeView(session.context()).also(session::attach)
        val model = ClearingModel()
        session.viewModelStore.put("owned", model)
        activity.setContentView(view)
        activity.setContentView(View(activity))

        assertEquals(Lifecycle.State.DESTROYED, session.lifecycle.currentState)
        assertTrue(model.cleared)
        assertNull(view.findViewTreeSavedStateRegistryOwner())
        assertNull(view.findViewTreeNavigationEventDispatcherOwner())
        session.close()
        controller.pause().stop().destroy()
    }

    @Test
    fun activeBackHandlersRunBeforeTheDialogCloseFallback() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var closeCalls = 0
        var innerBackCalls = 0
        val session = HostComposeEnvironment(controller.get(), Runnable { closeCalls++ })
        val view = ComposeView(session.context()).also(session::attach)
        controller.get().setContentView(view)
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                innerBackCalls++
            }
        }
        session.onBackPressedDispatcher.addCallback(session, callback)
        session.onBackPressedDispatcher.onBackPressed()
        assertEquals(1, innerBackCalls)
        assertEquals(0, closeCalls)
        callback.isEnabled = false
        session.onBackPressedDispatcher.onBackPressed()
        assertEquals(1, closeCalls)

        session.close()
        assertFalse(session.onBackPressedDispatcher.hasEnabledCallbacks())
        session.onBackPressedDispatcher.onBackPressed()
        assertEquals(1, closeCalls)
        controller.pause().stop().destroy()
    }

    @Test
    fun moduleNavigationOwnerDoesNotReplaceOrDisposeTheHostDecorOwner() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        assertFalse("The host may be a plain framework Activity", activity is NavigationEventDispatcherOwner)
        val decor = activity.window.decorView
        val hostOwner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        decor.setViewTreeNavigationEventDispatcherOwner(hostOwner)
        val session = HostComposeEnvironment(activity, Runnable {})
        val view = ComposeView(session.context()).also(session::attach)
        activity.setContentView(view)
        try {
            assertSame(session, view.findViewTreeNavigationEventDispatcherOwner())
            assertSame(hostOwner, decor.findViewTreeNavigationEventDispatcherOwner())
            assertNotSame(hostOwner.navigationEventDispatcher, session.navigationEventDispatcher)

            session.close()
            // With the module tag cleared, the still-attached view inherits the host's owner.
            assertSame(hostOwner, view.findViewTreeNavigationEventDispatcherOwner())
            assertSame(hostOwner, decor.findViewTreeNavigationEventDispatcherOwner())
            activity.setContentView(View(activity))
            assertNull(view.findViewTreeNavigationEventDispatcherOwner())

            val hostEvents = mutableListOf<String>()
            val hostHandler = object : NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, true) {
                override fun onBackCompleted() { hostEvents += "host" }
            }
            hostOwner.navigationEventDispatcher.addHandler(hostHandler)
            val hostInput = TestNavigationInput()
            hostOwner.navigationEventDispatcher.addInput(hostInput)
            hostInput.completeBack()
            assertEquals("Closing module UI must leave the host dispatcher usable", listOf("host"), hostEvents)
        } finally {
            session.close()
            decor.setViewTreeNavigationEventDispatcherOwner(null)
            hostOwner.navigationEventDispatcher.dispose()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun navigationHandlersBridgeOldBackBeforeLegacyCallbacksAndCloseBecomesSilent() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val events = mutableListOf<String>()
        val session = HostComposeEnvironment(controller.get(), Runnable { events += "close" })
        val view = ComposeView(session.context()).also(session::attach)
        controller.get().setContentView(view)
        val first = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { events += "first" }
        }
        val second = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { events += "second" }
        }
        session.onBackPressedDispatcher.addCallback(session, first)
        session.onBackPressedDispatcher.addCallback(session, second)
        try {
            session.onBackPressedDispatcher.onBackPressed()
            assertEquals(listOf("second"), events)
            val navigation = object : NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, true) {
                override fun onBackCompleted() { events += "navigation" }
            }
            session.navigationEventDispatcher.addHandler(navigation)
            session.onBackPressedDispatcher.onBackPressed()
            assertEquals("A newly opened NavigationBackHandler must precede the page's old callbacks",
                listOf("second", "navigation"), events)

            navigation.isBackEnabled = false
            session.onBackPressedDispatcher.onBackPressed()
            second.isEnabled = false
            session.onBackPressedDispatcher.onBackPressed()
            first.isEnabled = false
            session.onBackPressedDispatcher.onBackPressed()
            assertEquals("Disabling navigation must preserve legacy callback order and final fallback",
                listOf("second", "navigation", "second", "first", "close"), events)

            navigation.isBackEnabled = true
            session.onBackPressedDispatcher.onBackPressed()
            assertEquals("navigation", events.last())
            val completed = events.toList()
            session.close()
            assertFalse(session.onBackPressedDispatcher.hasEnabledCallbacks())
            assertNull(view.findViewTreeNavigationEventDispatcherOwner())
            session.onBackPressedDispatcher.onBackPressed()
            assertEquals("Closing must remove the bridge and lifecycle callbacks without another fallback", completed, events)
        } finally {
            session.close()
            controller.pause().stop().destroy()
        }
    }

    /** Exercise the public input API rather than inspecting the dispatcher's internal flags. */
    private class TestNavigationInput : androidx.navigationevent.NavigationEventInput() {
        fun completeBack() = dispatchOnBackCompleted()
    }

    private class ClearingModel : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }
}
