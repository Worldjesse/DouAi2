package com.douai.module.ui.miuix

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentCallbacks
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import com.douai.module.BuildConfig
import com.douai.module.ui.ModuleTheme

/** Module resources with the host Activity still owning windows, services, and storage. */
class HostComposeContext internal constructor(
    activity: Activity,
    private val moduleContext: Context,
) : ContextWrapper(activity) {
    private var configuredModuleContext = moduleContext
    private var activeConfiguration = Configuration(moduleContext.resources.configuration)
    private val moduleInflater by lazy {
        LayoutInflater.from(baseContext).cloneInContext(this)
    }

    override fun getResources(): Resources = configuredModuleContext.resources

    override fun getAssets(): AssetManager = configuredModuleContext.assets

    /** Refresh only this module context; never update the host's Resources or theme. */
    internal fun refreshConfiguration(configuration: Configuration): Boolean {
        val moduleConfiguration = Configuration(configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (ModuleTheme.isDark(baseContext)) Configuration.UI_MODE_NIGHT_YES
                else Configuration.UI_MODE_NIGHT_NO
        }
        // Framework-owned Resources may already have changed before callbacks run.
        if (activeConfiguration == moduleConfiguration) return false
        configuredModuleContext = moduleContext.createConfigurationContext(moduleConfiguration)
        activeConfiguration = Configuration(moduleConfiguration)
        return true
    }

    /** Standalone form rows inherit AndroidX owners, but still own their resource context. */
    internal fun bindConfiguration(view: View) {
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                refreshConfiguration(baseContext.resources.configuration)
                // Several rows/windows may share this context. Each view still needs delivery.
                view.dispatchConfigurationChanged(Configuration(resources.configuration))
            }

            override fun onLowMemory() = Unit
        }
        var registered = false
        val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (registered) return
                registered = true
                applicationContext.registerComponentCallbacks(callbacks)
                callbacks.onConfigurationChanged(baseContext.resources.configuration)
            }

            override fun onViewDetachedFromWindow(v: View) {
                if (!registered) return
                registered = false
                applicationContext.unregisterComponentCallbacks(callbacks)
            }
        }
        view.addOnAttachStateChangeListener(attachment)
        if (view.isAttachedToWindow) attachment.onViewAttachedToWindow(view)
    }

    override fun getClassLoader(): ClassLoader = HostComposeContext::class.java.classLoader!!

    override fun getSystemService(name: String): Any? =
        if (name == LAYOUT_INFLATER_SERVICE) moduleInflater else super.getSystemService(name)

    companion object {
        @JvmStatic
        fun create(activity: Activity): HostComposeContext {
            // Do not load code through createPackageContext: Compose must keep the module loader.
            val moduleContext = try {
                activity.createPackageContext(
                    BuildConfig.APPLICATION_ID,
                    Context.CONTEXT_IGNORE_SECURITY,
                )
            } catch (failure: android.content.pm.PackageManager.NameNotFoundException) {
                throw IllegalStateException("Module resources are unavailable", failure)
            }
            return HostComposeContext(activity, moduleContext).apply {
                refreshConfiguration(activity.resources.configuration)
            }
        }
    }
}
