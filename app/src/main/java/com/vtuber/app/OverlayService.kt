package com.vtuber.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.*

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var config: VtuberConfig
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val talkingState = mutableStateOf(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        config = ConfigParser.load(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showOverlay()
        scope.launch {
            VoiceDetector(
                threshold = config.volumeThreshold,
                talkFrames = config.talkFrames,
                silenceFrames = config.silenceFrames,
            ).run { talking -> talkingState.value = talking }
        }
    }

    private fun showOverlay() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

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

        composeView = ComposeView(this).apply {
            setContent { VtuberOverlay(config, talkingState.value) }
        }
        windowManager.addView(composeView, params)
    }

    @Composable
    private fun VtuberOverlay(cfg: VtuberConfig, talking: Boolean) {
        var flip by remember { mutableStateOf(false) }

        val idle1 = remember { loadBitmap(cfg.idle1Path) }
        val idle2 = remember { loadBitmap(cfg.idle2Path) }
        val talkingImg = remember { loadBitmap(cfg.talkingPath) }

        LaunchedEffect(talking, cfg.idleInterval) {
            while (!talking) {
                delay(cfg.idleInterval)
                flip = !flip
            }
        }

        val current = when {
            talking -> talkingImg
            flip -> idle2
            else -> idle1
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = when (cfg.anchor) {
                "bottom-left" -> Alignment.BottomStart
                "bottom-right" -> Alignment.BottomEnd
                "center" -> Alignment.Center
                else -> Alignment.BottomCenter
            }
        ) {
            current?.let { bmp ->
                val visible = (bmp.height * cfg.cropTop).toInt().coerceAtLeast(1)
                val cropped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, visible)
                Image(
                    bitmap = cropped.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(cfg.scale.coerceIn(0.1f, 1f))
                )
            }
        }
    }

    private fun loadBitmap(path: String): android.graphics.Bitmap? =
        runCatching {
            assets.open(path).use { android.graphics.BitmapFactory.decodeStream(it) }
        }.getOrNull()

    private fun buildNotification(): Notification {
        val id = "vtuber_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(id) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(id, "VTuber", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return NotificationCompat.Builder(this, id)
            .setContentTitle("VTuber ativo")
            .setContentText("Sua personagem está reagindo à voz")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::composeView.isInitialized) runCatching { windowManager.removeView(composeView) }
    }
}
