package com.clawgui.ng

import android.app.Application
import com.clawgui.ng.runtime.RuntimeContainer

class ClawNgApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        installConscrypt()
        RuntimeContainer.init(this)
    }

    /**
     * Register the bundled Conscrypt as a Java Security provider before
     * anything touches SSLContext. Android 14+ ships a Conscrypt under
     * `com.android.org.conscrypt` that's missing methods libadb-android
     * relies on (notably exportKeyingMaterial), so we ship our own and
     * insert it at priority 1 to shadow the system one.
     */
    private fun installConscrypt() {
        runCatching {
            val provider = org.conscrypt.Conscrypt.newProvider()
            java.security.Security.insertProviderAt(provider, 1)
            android.util.Log.i("ClawNgApp", "Conscrypt installed as Security provider #1: ${provider.name}")
        }.onFailure {
            android.util.Log.w("ClawNgApp", "Conscrypt install failed", it)
        }

        // libadb-android's SslUtils.customConscrypt static flag is set lazily
        // on first getSslContext() call by reflectively trying to load
        // org.conscrypt.OpenSSLProvider. On some Android 14 ROMs the load
        // succeeds but the flag still ends up false (suspected init-order
        // race when class verification interleaves with multi-dex). Force
        // the flag to true via reflection so exportKeyingMaterial later
        // dispatches to our bundled Conscrypt rather than the stripped-down
        // system one.
        runCatching {
            val cls = Class.forName("io.github.muntashirakon.adb.SslUtils")
            val field = cls.getDeclaredField("customConscrypt").apply { isAccessible = true }
            field.setBoolean(null, true)
            android.util.Log.i("ClawNgApp", "Forced SslUtils.customConscrypt=true")
        }.onFailure {
            android.util.Log.w("ClawNgApp", "Could not force customConscrypt", it)
        }
    }

    companion object {
        lateinit var instance: ClawNgApp
            private set
    }
}
