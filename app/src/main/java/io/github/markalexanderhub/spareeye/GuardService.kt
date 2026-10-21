package io.github.markalexanderhub.spareeye

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches through the camera while armed. Always visible: a persistent notification with a
 * Disarm button, plus the system's own camera-in-use indicator on Android 12 and newer.
 */
class GuardService : LifecycleService() {

    companion object {
        private const val CHANNEL = "watching"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "io.github.markalexanderhub.spareeye.STOP"
        const val ACTION_TEST = "io.github.markalexanderhub.spareeye.TEST"
        const val LIVE_PORT = 8080

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java))
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, GuardService::class.java).setAction(ACTION_STOP)) }
        fun testShot(ctx: Context) { ctx.startService(Intent(ctx, GuardService::class.java).setAction(ACTION_TEST)) }
    }

    private lateinit var prefs: Prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val captureExecutor = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null
    private var detector: MotionDetector? = null
    private var server: MjpegServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var rotation = 0
    private var lastStreamFrame = 0L
    private var lastLevelPush = 0L
    private var lastAlert = -1L
    private var ignoreUntil = 0L
    private val capturing = AtomicBoolean(false)
    private var started = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TEST -> { if (started) snapAndSend(test = true); return START_STICKY }
        }
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(getString(R.string.notif_starting)), type)
        if (!started) {
            started = true
            ignoreUntil = SystemClock.elapsedRealtime() + 3000
            GuardState.update { it.copy(armed = true, armedAt = System.currentTimeMillis(), alertsSent = 0, lastMotionAt = 0L, lastError = null, note = null) }
            holdRadios()
            startLiveView()
            startCamera()
            announce(getString(R.string.tg_armed, prefs.room))
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                bind()
            } catch (e: Exception) {
                fail(getString(R.string.err_camera, e.message ?: "?"))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        val selector = if (prefs.useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        detector = MotionDetector(prefs.sensitivity)

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor) { image ->
            try { analyse(image) } catch (_: Exception) {} finally { image.close() }
        }

        capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(82)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
            )
            .build()

        try {
            p.unbindAll()
            p.bindToLifecycle(this, selector, analysis, capture)
        } catch (e: Exception) {
            fail(getString(R.string.err_camera, e.message ?: "?"))
            return
        }
        updateNotification(getString(R.string.notif_watching, prefs.room))
    }

    private fun analyse(image: ImageProxy) {
        rotation = image.imageInfo.rotationDegrees
        val now = SystemClock.elapsedRealtime()
        val d = detector ?: return
        val plane = image.planes[0]
        val level = d.feed(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)

        if (now - lastLevelPush > 300) {
            lastLevelPush = now
            GuardState.update { it.copy(motionLevel = level) }
        }

        val someoneWatching = (server?.watchers?.get() ?: 0) > 0
        if (someoneWatching && now - lastStreamFrame > 300) {
            lastStreamFrame = now
            latestJpeg = nv21ToJpeg(image.toNv21(), image.width, image.height, 55)
        }

        if (now < ignoreUntil) return
        if (d.isMotion(level) && !capturing.get()) {
            GuardState.update { it.copy(lastMotionAt = System.currentTimeMillis()) }
            val cooldown = prefs.cooldownSec * 1000L
            if (lastAlert >= 0 && now - lastAlert < cooldown) return
            lastAlert = now
            ignoreUntil = now + 2000   // the flash of auto-exposure after a photo is not movement
            d.reset()
            snapAndSend(test = false)
        }
    }

    private fun snapAndSend(test: Boolean) {
        val cap = capture ?: return
        if (!capturing.compareAndSet(false, true)) return
        cap.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bytes = try { jpegUpright(image) } finally { image.close() }
                capturing.set(false)
                scope.launch { deliver(bytes, test) }
            }
            override fun onError(e: ImageCaptureException) {
                capturing.set(false)
                GuardState.update { it.copy(lastError = getString(R.string.err_photo, e.message ?: "?")) }
            }
        })
    }

    /** The JPEG comes out in sensor orientation; rotate it so the photo reads the way the phone stands. */
    private fun jpegUpright(image: ImageProxy): ByteArray {
        val buf = image.planes[0].buffer
        val raw = ByteArray(buf.remaining()).also { buf.get(it) }
        val deg = image.imageInfo.rotationDegrees
        if (deg == 0) return raw
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val out = ByteArrayOutputStream(raw.size)
        rotated.compress(Bitmap.CompressFormat.JPEG, 82, out)
        if (rotated !== bmp) bmp.recycle()
        rotated.recycle()
        return out.toByteArray()
    }

    private fun deliver(jpeg: ByteArray, test: Boolean) {
        if (!prefs.telegramReady) {
            GuardState.update { it.copy(lastError = getString(R.string.err_no_telegram)) }
            return
        }
        val time = DateFormat.getTimeFormat(this).format(Date())
        val caption = if (test) getString(R.string.tg_test, prefs.room, time) else getString(R.string.tg_motion, prefs.room, time)
        try {
            TelegramClient(prefs.botToken).sendPhoto(prefs.chatId, jpeg, caption)
            GuardState.update { it.copy(alertsSent = it.alertsSent + 1, lastError = null) }
        } catch (e: Exception) {
            GuardState.update { it.copy(lastError = getString(R.string.err_send, e.message ?: "?")) }
        }
    }

    private fun announce(text: String) {
        if (!prefs.notifyArm || !prefs.telegramReady) return
        scope.launch {
            try { TelegramClient(prefs.botToken).sendMessage(prefs.chatId, text) } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- live view

    private fun startLiveView() {
        if (!prefs.liveView) return
        try {
            val s = MjpegServer(LIVE_PORT, prefs.liveKey, { latestJpeg }, { rotation }, { prefs.room })
            s.start(5000, true)
            server = s
            val ip = wifiAddress()
            val url = if (ip != null) "http://$ip:$LIVE_PORT/${prefs.liveKey}/" else null
            GuardState.update { it.copy(liveUrl = url, note = if (ip == null) getString(R.string.note_no_wifi) else null) }
        } catch (e: Exception) {
            GuardState.update { it.copy(note = getString(R.string.err_live, e.message ?: "?")) }
        }
    }

    private fun wifiAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }

    // ---------------------------------------------------------------- keep the radios awake

    private fun holdRadios() {
        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SpareEye:wifi").also { it.acquire() }
        } catch (_: Exception) {}
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpareEye:cpu").also { it.acquire() }
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- notification

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GuardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_eye)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.disarm), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun fail(message: String) {
        GuardState.update { it.copy(lastError = message) }
        updateNotification(message)
    }

    override fun onDestroy() {
        announce(getString(R.string.tg_disarmed, prefs.room))
        try { provider?.unbindAll() } catch (_: Exception) {}
        try { server?.stop() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
        GuardState.reset()
        scope.launch { kotlinx.coroutines.delay(1500); scope.cancel() }
        super.onDestroy()
    }
}
