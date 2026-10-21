package io.github.markalexanderhub.spareeye

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.text.format.DateUtils
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.markalexanderhub.spareeye.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var provider: ProcessCameraProvider? = null
    private var countdown: CountDownTimer? = null

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) startPreview()
        else Toast.makeText(this, R.string.need_camera, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        loadSettings()
        wireSettings()

        b.findChat.setOnClickListener { findChat() }
        b.ntfyShare.setOnClickListener { shareTopic() }
        b.ntfyTest.setOnClickListener { ntfyTest() }
        b.howTo.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.how_to).setMessage(R.string.how_to_text)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        b.arm.setOnClickListener { if (countdown == null) startCountdown() else cancelCountdown() }
        b.disarm.setOnClickListener { GuardService.stop(this) }
        b.testShot.setOnClickListener {
            GuardService.testShot(this)
            Toast.makeText(this, R.string.test_sent, Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GuardState.status.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!GuardState.status.value.armed) ensurePermissionsThenPreview()
    }

    override fun onStop() {
        super.onStop()
        stopPreview()
    }

    // ---------------------------------------------------------------- settings

    private fun loadSettings() {
        b.token.setText(prefs.botToken)
        b.room.setText(if (prefs.room == "Home") "" else prefs.room)
        b.sensitivity.value = prefs.sensitivity.toFloat()
        b.cooldown.value = prefs.cooldownSec.toFloat()
        b.cooldownValue.text = getString(R.string.cooldown_value, prefs.cooldownSec)
        b.notifyArm.isChecked = prefs.notifyArm
        b.liveView.isChecked = prefs.liveView
        b.cameraToggle.check(if (prefs.useFrontCamera) R.id.cameraFront else R.id.cameraBack)
        b.chatStatus.text = if (prefs.chatId != 0L) getString(R.string.chat_found, prefs.chatName) else getString(R.string.chat_hint)
        b.ntfyTopic.text = prefs.ntfyTopic
        b.ntfyServer.setText(prefs.ntfyServer)
        val ntfy = prefs.alertMethod == Prefs.METHOD_NTFY
        b.methodToggle.check(if (ntfy) R.id.methodNtfy else R.id.methodTelegram)
        showMethod(ntfy)
    }

    private fun showMethod(ntfy: Boolean) {
        b.ntfyPanel.visibility = if (ntfy) android.view.View.VISIBLE else android.view.View.GONE
        b.telegramPanel.visibility = if (ntfy) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun shareTopic() {
        saveTextFields()
        val text = getString(R.string.ntfy_share_text, prefs.ntfyTopic, prefs.ntfyServer.trimEnd('/'))
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        startActivity(android.content.Intent.createChooser(send, getString(R.string.ntfy_share)))
    }

    private fun ntfyTest() {
        saveTextFields()
        b.ntfyTest.isEnabled = false
        b.ntfyStatus.text = getString(R.string.ntfy_sending)
        val server = prefs.ntfyServer
        val topic = prefs.ntfyTopic
        val hello = getString(R.string.ntfy_hello, prefs.room)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { NtfyClient(server, topic).sendText(getString(R.string.app_name), hello) } }
            b.ntfyTest.isEnabled = true
            b.ntfyStatus.text = r.fold({ getString(R.string.ntfy_sent) }, { getString(R.string.ntfy_error, it.message ?: "?") })
        }
    }

    private fun wireSettings() {
        b.token.setOnFocusChangeListener { _, has -> if (!has) prefs.botToken = b.token.text.toString() }
        b.room.setOnFocusChangeListener { _, has -> if (!has) prefs.room = b.room.text.toString() }
        b.sensitivity.addOnChangeListener { _, v, _ -> prefs.sensitivity = v.toInt() }
        b.cooldown.addOnChangeListener { _, v, _ ->
            prefs.cooldownSec = v.toInt()
            b.cooldownValue.text = getString(R.string.cooldown_value, v.toInt())
        }
        b.methodToggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val ntfy = id == R.id.methodNtfy
            prefs.alertMethod = if (ntfy) Prefs.METHOD_NTFY else Prefs.METHOD_TELEGRAM
            showMethod(ntfy)
        }
        b.ntfyServer.setOnFocusChangeListener { _, has -> if (!has) prefs.ntfyServer = b.ntfyServer.text.toString() }
        b.notifyArm.setOnCheckedChangeListener { _, on -> prefs.notifyArm = on }
        b.liveView.setOnCheckedChangeListener { _, on -> prefs.liveView = on }
        b.cameraToggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            prefs.useFrontCamera = (id == R.id.cameraFront)
            if (provider != null) startPreview()
        }
    }

    private fun saveTextFields() {
        prefs.botToken = b.token.text.toString()
        prefs.room = b.room.text.toString()
        prefs.ntfyServer = b.ntfyServer.text.toString()
    }

    private fun findChat() {
        saveTextFields()
        val token = prefs.botToken
        if (token.isEmpty()) { b.chatStatus.text = getString(R.string.chat_need_token); return }
        b.findChat.isEnabled = false
        b.chatStatus.text = getString(R.string.chat_looking)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tg = TelegramClient(token)
                    val bot = tg.botName()
                    val chat = tg.findChat()
                    bot to chat
                }
            }
            b.findChat.isEnabled = true
            result.onSuccess { (bot, chat) ->
                if (chat == null) {
                    b.chatStatus.text = getString(R.string.chat_none, bot)
                } else {
                    prefs.chatId = chat.id
                    prefs.chatName = chat.name
                    b.chatStatus.text = getString(R.string.chat_found, chat.name)
                    runCatching { withContext(Dispatchers.IO) { TelegramClient(token).sendMessage(chat.id, getString(R.string.tg_hello)) } }
                }
            }.onFailure { e ->
                b.chatStatus.text = getString(R.string.chat_error, e.message ?: "?")
            }
        }
    }

    // ---------------------------------------------------------------- preview while setting up

    private fun ensurePermissionsThenPreview() {
        val wanted = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startPreview() else askPermissions.launch(missing.toTypedArray())
    }

    private fun startPreview() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(b.preview.surfaceProvider) }
                val selector = if (prefs.useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                p.unbindAll()
                p.bindToLifecycle(this, selector, preview)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.err_camera, e.message ?: "?"), Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopPreview() {
        try { provider?.unbindAll() } catch (_: Exception) {}
        provider = null
    }

    // ---------------------------------------------------------------- arming

    private fun startCountdown() {
        saveTextFields()
        if (!prefs.alertsReady) {
            Toast.makeText(this, R.string.arm_needs_alerts, Toast.LENGTH_LONG).show()
        }
        countdown = object : CountDownTimer(20_000, 1000) {
            override fun onTick(ms: Long) { b.arm.text = getString(R.string.arming_in, (ms / 1000) + 1) }
            override fun onFinish() {
                countdown = null
                b.arm.text = getString(R.string.arm)
                stopPreview()
                GuardService.start(this@MainActivity)
            }
        }.start()
    }

    private fun cancelCountdown() {
        countdown?.cancel()
        countdown = null
        b.arm.text = getString(R.string.arm)
    }

    // ---------------------------------------------------------------- showing the state

    private fun render(s: GuardState.Status) {
        val armed = s.armed
        b.setup.visibility = if (armed) android.view.View.GONE else android.view.View.VISIBLE
        b.armedScreen.visibility = if (armed) android.view.View.VISIBLE else android.view.View.GONE
        if (armed) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = 0.02f }
            b.armedTitle.text = getString(R.string.watching, prefs.room)
            val since = if (s.armedAt > 0) DateUtils.getRelativeTimeSpanString(s.armedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) else ""
            val last = if (s.lastMotionAt > 0) DateUtils.getRelativeTimeSpanString(s.lastMotionAt, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS) else getString(R.string.none_yet)
            b.armedStatus.text = getString(R.string.armed_status, since, last, s.alertsSent)
            b.motionMeter.progress = (s.motionLevel * 1000).toInt().coerceIn(0, 1000)
            b.liveUrl.text = s.liveUrl?.let { getString(R.string.live_at, it) } ?: s.note ?: getString(R.string.live_off)
            b.lastError.text = s.lastError ?: ""
            b.lastError.visibility = if (s.lastError == null) android.view.View.GONE else android.view.View.VISIBLE
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (provider == null && hasCamera && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startPreview()
        }
    }
}
