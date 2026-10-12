package com.douai.module.ui.miuix

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.view.LayoutInflater
import com.douai.module.BuildConfig

/** Module resources with the host Activity still owning windows, services, and storage. */
class HostComposeContext internal constructor(
    activity: Activity,
    private val moduleContext: Context,
) : ContextWrapper(activity) {
    private val moduleInflater by lazy {
        LayoutInflater.from(baseContext).cloneInContext(this)
    }

    override fun getResources(): Resources = moduleContext.resources

    override fun getAssets(): AssetManager = moduleContext.assets

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
                ).createConfigurationContext(activity.resources.configuration)
            } catch (failure: android.content.pm.PackageManager.NameNotFoundException) {
                throw IllegalStateException("Module resources are unavailable", failure)
            }
            return HostComposeContext(activity, moduleContext)
        }
    }
}
