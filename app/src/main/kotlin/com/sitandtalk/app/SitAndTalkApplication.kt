package com.sitandtalk.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.sitandtalk.app.push.PushNotifier
import com.sitandtalk.core.model.AppConfig
import com.sitandtalk.core.network.SafeLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SitAndTalkApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var config: AppConfig

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        SafeLog.enabled = BuildConfig.DEBUG
        initFirebaseIfConfigured()
        PushNotifier.ensureChannels(this)
    }

    /**
     * Firebase is initialised from public build configuration only when all values exist. Without it
     * the app runs normally and simply reports push notifications as unavailable.
     */
    private fun initFirebaseIfConfigured() {
        if (!config.isPushConfigured || FirebaseApp.getApps(this).isNotEmpty()) return
        val options = FirebaseOptions.Builder()
            .setProjectId(config.firebaseProjectId)
            .setApplicationId(config.firebaseAppId)
            .setApiKey(config.firebaseApiKey)
            .setGcmSenderId(config.firebaseSenderId)
            .build()
        runCatching { FirebaseApp.initializeApp(this, options) }
            .onFailure { SafeLog.error("push", "firebase_init_failed", it) }
    }
}
