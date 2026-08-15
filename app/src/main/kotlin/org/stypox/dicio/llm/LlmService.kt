package org.stypox.dicio.llm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.stypox.dicio.R
import org.stypox.dicio.settings.datastore.UserSettings
import androidx.datastore.core.DataStore
import javax.inject.Inject

/**
 * Foreground service that keeps the on-device LLM **warm** so answers don't pay the multi-second
 * model-load cost on every request. It loads the model on start and unloads it on strong memory
 * pressure (so the OS is less likely to kill the whole app), reloading lazily on the next request.
 *
 * The model itself lives in the singleton [GgufModelManager]/[LlmEngine], so unloading here only
 * frees native memory; the managers stay valid.
 *
 * Started from settings when the user enables the local AI; stopped when they disable it.
 */
@AndroidEntryPoint
class LlmService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    @Inject
    lateinit var modelManager: GgufModelManager

    @Inject
    lateinit var dataStore: DataStore<UserSettings>

    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(this, NotificationManager::class.java)!!
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            modelManager.unload()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()

        scope.launch {
            val settings = dataStore.data.first()
            if (!settings.llmEnabled) {
                stopSelf()
                return@launch
            }
            val url = settings.llmModelUrl.ifBlank { GgufModelManager.defaultModelUrl }
            modelManager.refresh(enabled = true, modelUrl = url)
            modelManager.ensureReady(url)
        }

        return START_STICKY
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // free the (large) native model buffers when the system is under memory pressure; it will
        // be reloaded lazily on the next request via GgufModelManager.ensureReady
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Log.i(TAG, "Unloading LLM due to memory pressure (level=$level)")
            modelManager.unload()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Local AI is ready")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Local AI",
                NotificationManager.IMPORTANCE_LOW,
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private val TAG = LlmService::class.simpleName
        private const val CHANNEL_ID = "llm_service"
        private const val NOTIFICATION_ID = 5382
        const val ACTION_STOP = "org.stypox.dicio.llm.STOP"

        fun start(context: Context) {
            val intent = Intent(context, LlmService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LlmService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
