package com.guardiangrid

import android.app.Application
import android.util.Log
import com.guardiangrid.modules.communication.security.TelegramConfigManager
import java.io.File

/**
 * Android Application entrypoint for GuardianGrid.
 * Manages the application-level lifecycle, bootstraps offline AI models,
 * and maintains the singleton [GuardianGridContainer].
 */
class GuardianGridApp : Application() {

    lateinit var container: GuardianGridContainer
        internal set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Ensure bundled offline AI models (YAMNet) are extracted to internal storage before subsystems access them
        copyModelAssetsIfNeeded()

        // Initialize production dependency container with applicationContext
        container = DefaultGuardianGridContainer(this)

        // Bootstrap Keystore-backed secure Telegram credentials
        TelegramConfigManager.initializeWithStorage(container.secureCredentialStore)

        // Restore persistent user settings and trusted contacts
        container.settingsRepository.syncToStateHolder()

        Log.i(TAG, "GuardianGrid Application initialized with application-scoped container.")
    }

    /**
     * Extracts bundled TFLite models from APK assets to internal app storage.
     */
    fun copyModelAssetsIfNeeded() {
        try {
            val modelsDir = File(filesDir, "models")
            if (!modelsDir.exists()) {
                modelsDir.mkdirs()
            }
            val assetFiles = assets.list("models") ?: emptyArray()
            for (filename in assetFiles) {
                val outFile = File(modelsDir, filename)
                if (!outFile.exists() || outFile.length() == 0L) {
                    assets.open("models/$filename").use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed copying model assets: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "GuardianGridApp"
        lateinit var instance: GuardianGridApp
            internal set
    }
}
