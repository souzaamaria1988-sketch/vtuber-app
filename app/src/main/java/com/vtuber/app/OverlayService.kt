package com.vtuber.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var imageView: ImageView
    private lateinit var config: VtuberConfig

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Volatile private var talking = false
    private var idleIndex = 0

    private var idle1: Bitmap? = null
    private var idle2: Bitmap? = null
    private var talkingBmp: Bitmap? = null

    private val idleRunnable = object : Runnable {
        override fun run() {
            if (!talking) {
                idleIndex = (idleIndex + 1) % 2
                applyBitmap()
            }
            handler.postDelayed(this, config.idleInterval)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        config = ConfigParser.load(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        loadBitmaps()
        showOverlay()
        handler.postDelayed(idleRunnable, config.idleInterval)
        startVoiceDetection()
    }

    private fun loadBitmaps() {
        idle1 = loadAndCrop(config.idle1Path)
        idle2 = loadAndCrop(config.idle2Path)
        talkingBmp = loadAndCrop(config.talkingPath)
    }

    private fun loadAndCrop(path: String): Bitmap? {
        val raw = runCatching {
            assets.open(path).use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null
        val visible = (raw.height * config.cropTop).toInt().coerceIn(1, raw.height)
        return runCatching {
            Bitmap.createBitmap(raw, 0, 0, raw.width, visible)
        }.getOrDefault(raw)
    }

    private fun showOverlay() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        root = FrameLayout(this)
        imageView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
        }

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            when (config.anchor) {
                "bottom-left" -> Gravity.BOTTOM or Gravity.START
                "bottom-right" -> Gravity.BOTTOM or Gravity.END
                "center" -> Gravity.CENTER
                else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
        )
        root.addView(imageView, lp)
        applyBitmap()
        windowManager.addView(root, params)
    }

    private fun applyBitmap() {
        val bmp = when {
            talking -> talkingBmp
            idleIndex == 0 -> idle1
            else -> idle2
        } ?: return
        imageView.setImageBitmap(bmp)
    }

    private fun startVoiceDetection() {
        scope.launch {
            VoiceDetector(
                threshold = config.volumeThreshold,
                talkFrames = config.talkFrames,
                silenceFrames = config.silenceFrames,
            ).run { isTalking ->
                talking = isTalking
                applyBitmap()
            }
        }
    }

    private fun startForegroundCompat() {
        val channelId = "vtuber_overlay"
        val mgr = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (mgr.getNotificationChannel(channelId) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "VTuber",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }

        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("VTuber ativo")
            .setContentText("Sua personagem esta reagindo a voz")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(idleRunnable)
        scope.cancel()
        if (::root.isInitialized) {
            runCatching { windowManager.removeView(root) }
        }
    }
}
