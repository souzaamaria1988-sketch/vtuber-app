package com.vtuber.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService : Service() {

    companion object {
        const val ACTION_TOGGLE_EDIT = "com.vtuber.app.TOGGLE_EDIT"
        const val ACTION_STOP = "com.vtuber.app.STOP"
        private const val HANDLE_DP = 14
        private const val MIN_W_DP = 60
    }

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var spriteFrame: FrameLayout
    private lateinit var spriteView: ImageView
    private lateinit var handleTL: View
    private lateinit var handleTR: View
    private lateinit var handleBL: View
    private lateinit var handleBR: View

    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: Prefs
    private lateinit var config: VtuberConfig

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var voiceDetector: VoiceDetector? = null

    private var idle1: Bitmap? = null
    private var idle2: Bitmap? = null
    private var talkingBmp: Bitmap? = null

    private var aspect = 1f
    private var spriteW = 0
    private var spriteH = 0
    private var handleR = 0
    private var minW = 0
    private var longPressTimeout = 500L
    private var touchSlopSq = 1

    @Volatile private var talking = false
    private var idleIndex = 0
    private var editMode = false

    private enum class Drag { NONE, SPRITE, TL, TR, BL, BR }
    private var dragging = Drag.NONE
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragStartW = 0
    private var dragStartH = 0
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var downRawX = 0f
    private var downRawY = 0f

    private val longPressRunnable = Runnable { enterEditMode() }
    private val rotationRetry = Runnable { handleRotation() }

    // Mais confiavel que Service.onConfigurationChanged sozinho para services
    // sem Activity (o ComponentCallbacks do Application sempre e disparado).
    private val rotationCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = handleRotationSoon()
        override fun onLowMemory() {}
        override fun onTrimMemory(level: Int) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // 1o comando: garante startForeground dentro da janela de 5 segundos
        if (!startForegroundCompat()) return // ja chamou stopSelf()

        prefs = Prefs(this)
        config = ConfigParser.load(this)
        loadBitmaps()

        val base = idle1 ?: run { stopSelf(); return }

        aspect = base.width.toFloat() / base.height.toFloat()
        handleR = dp(HANDLE_DP)
        minW = dp(MIN_W_DP)
        longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        val slop = ViewConfiguration.get(this).scaledTouchSlop * 2
        touchSlopSq = slop * slop

        computeInitialGeometry()
        buildUI()
        showOverlay()
        startIdle()
        startVoice()

        applicationContext.registerComponentCallbacks(rotationCallbacks)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Idempotente: reafirma o FGS a cada start (cobre restarts e acoes da
        // notificacao; em caso de mic revogado no Android 14+, encerra limpo).
        if (!startForegroundCompat()) return START_NOT_STICKY

        when (intent?.action) {
            ACTION_TOGGLE_EDIT -> toggleEditMode()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    // ================== GEOMETRIA ==================
    private fun computeInitialGeometry() {
        val dm = resources.displayMetrics
        spriteW = if (prefs.hasPosition()) {
            (dm.widthPixels * prefs.wRatio).toInt().coerceAtLeast(minW)
        } else {
            (dm.widthPixels * 0.55f).toInt()
        }
        spriteH = (spriteW / aspect).toInt()
    }

    private fun computeInitialPosition() {
        val dm = resources.displayMetrics
        if (prefs.hasPosition()) {
            params.x = (dm.widthPixels * prefs.xRatio).toInt()
            params.y = (dm.heightPixels * prefs.yRatio).toInt()
        } else {
            params.x = (dm.widthPixels - spriteW) / 2 - handleR
            params.y = dm.heightPixels - spriteH - dp(180)
        }
    }

    private fun saveAsRatio() {
        val dm = resources.displayMetrics
        if (dm.widthPixels > 0 && dm.heightPixels > 0) {
            prefs.xRatio = params.x.toFloat() / dm.widthPixels
            prefs.yRatio = params.y.toFloat() / dm.heightPixels
            prefs.wRatio = spriteW.toFloat() / dm.widthPixels
        }
    }

    private fun clampToScreen() {
        val dm = resources.displayMetrics
        val w = spriteW + 2 * handleR
        val h = spriteH + 2 * handleR
        val minVisible = dp(40)
        params.x = params.x.coerceIn(-w + minVisible, dm.widthPixels - minVisible)
        params.y = params.y.coerceIn(-h + minVisible, dm.heightPixels - minVisible)
    }

    // ================== IMAGENS ==================
    private fun loadBitmaps() {
        idle1 = loadCrop(config.idle1Path)
        idle2 = loadCrop(config.idle2Path)
        talkingBmp = loadCrop(config.talkingPath)
    }

    private fun loadCrop(path: String): Bitmap? {
        val raw = runCatching {
            assets.open(path).use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null
        val visible = (raw.height * config.cropTop).toInt().coerceIn(1, raw.height)
        return runCatching {
            Bitmap.createBitmap(raw, 0, 0, raw.width, visible)
        }.getOrDefault(raw)
    }

    // ================== UI ==================
    private fun buildUI() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        toolbar = buildToolbar()
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        spriteFrame = FrameLayout(this).apply { setPadding(handleR, handleR, handleR, handleR) }

        spriteView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
        }
        spriteFrame.addView(spriteView, FrameLayout.LayoutParams(spriteW, spriteH, Gravity.CENTER))

        handleTL = makeHandle()
        handleTR = makeHandle()
        handleBL = makeHandle()
        handleBR = makeHandle()

        spriteFrame.addView(handleTL, FrameLayout.LayoutParams(2 * handleR, 2 * handleR, Gravity.TOP or Gravity.START))
        spriteFrame.addView(handleTR, FrameLayout.LayoutParams(2 * handleR, 2 * handleR, Gravity.TOP or Gravity.END))
        spriteFrame.addView(handleBL, FrameLayout.LayoutParams(2 * handleR, 2 * handleR, Gravity.BOTTOM or Gravity.START))
        spriteFrame.addView(handleBR, FrameLayout.LayoutParams(2 * handleR, 2 * handleR, Gravity.BOTTOM or Gravity.END))

        spriteFrame.layoutParams = LinearLayout.LayoutParams(spriteW + 2 * handleR, spriteH + 2 * handleR)
        root.addView(spriteFrame)

        setHandlesVisible(false)
        applyBitmap()
        setupTouches()
    }

    private fun buildToolbar(): LinearLayout {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xEE1D1B20.toInt())
                setStroke(dp(1), 0xFFD0BCFF.toInt())
            }
            visibility = View.GONE
        }

        val reset = makeToolbarBtn("Resetar") { resetPosition() }
        val done = makeToolbarBtn("Pronto") { exitEditMode() }

        t.addView(reset)
        val spacer = View(this)
        t.addView(spacer, LinearLayout.LayoutParams(dp(8), 1))
        t.addView(done)
        return t
    }

    private fun makeToolbarBtn(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(0xFFEADDFF.toInt())
            textSize = 14f
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF4F378B.toInt())
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun makeHandle(): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(dp(3), 0xFFD0BCFF.toInt())
        }
        visibility = View.GONE
    }

    private fun showOverlay() {
        // WRAP_CONTENT + NOT_TOUCH_MODAL: a janela ocupa so o tamanho do sprite;
        // toques fora dela passam para o app de baixo.
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        computeInitialPosition()
        clampToScreen()

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.addView(root, params)
    }

    private fun applyBitmap() {
        val bmp = when {
            talking -> talkingBmp ?: idle1
            idleIndex == 0 -> idle1
            else -> idle2
        } ?: return
        spriteView.setImageBitmap(bmp)
    }

    // ================== ANIMACAO IDLE ==================
    private fun startIdle() {
        val r = object : Runnable {
            override fun run() {
                if (!talking && !editMode) {
                    idleIndex = (idleIndex + 1) % 2
                    applyBitmap()
                }
                handler.postDelayed(this, config.idleInterval)
            }
        }
        handler.postDelayed(r, config.idleInterval)
    }

    // ================== VOZ ==================
    private fun startVoice() {
        val threshold = if (prefs.threshold > 0f) prefs.threshold.toDouble() else config.volumeThreshold
        scope.launch {
            val detector = VoiceDetector(
                threshold = threshold,
                talkFrames = config.talkFrames,
                silenceFrames = config.silenceFrames,
            )
            voiceDetector = detector
            // CORRECAO: o callback chega em thread de background (Dispatchers.Default).
            // Toda chamada que toca em Views precisa voltar para a main thread.
            detector.run { talkingNow ->
                handler.post {
                    talking = talkingNow
                    if (!editMode && ::spriteView.isInitialized) applyBitmap()
                }
            }
        }
    }

    // ================== ROTACAO ==================
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleRotationSoon()
    }

    private fun handleRotationSoon() {
        if (!::root.isInitialized || !::params.isInitialized || !::wm.isInitialized) return
        // Aplica imediatamente...
        handleRotation()
        // ...e repete depois: os displayMetrics do Service podem ainda estar
        // defasados no instante do callback de configuracao.
        handler.removeCallbacks(rotationRetry)
        handler.postDelayed(rotationRetry, 350)
    }

    private fun handleRotation() {
        if (!::root.isInitialized || !::params.isInitialized || !::wm.isInitialized) return

        val dm = resources.displayMetrics
        val newW = dm.widthPixels
        val newH = dm.heightPixels
        if (newW <= 0 || newH <= 0) return

        val xRatio = if (prefs.xRatio >= 0f) prefs.xRatio else 0.5f
        val yRatio = if (prefs.yRatio >= 0f) prefs.yRatio else 0.9f
        val wRatio = if (prefs.wRatio > 0f) prefs.wRatio else 0.55f

        spriteW = (newW * wRatio).toInt().coerceAtLeast(minW)
        spriteH = (spriteW / aspect).toInt()

        spriteView.layoutParams = FrameLayout.LayoutParams(spriteW, spriteH, Gravity.CENTER)
        spriteFrame.layoutParams = LinearLayout.LayoutParams(spriteW + 2 * handleR, spriteH + 2 * handleR)

        params.x = (newW * xRatio).toInt()
        params.y = (newH * yRatio).toInt()

        clampToScreen()
        runCatching { wm.updateViewLayout(root, params) }
    }

    // ================== TOUCH ==================
    private fun setupTouches() {
        spriteView.setOnTouchListener { _, e -> onSpriteTouch(e) }
        handleTL.setOnTouchListener { _, e -> onHandleTouch(Drag.TL, e) }
        handleTR.setOnTouchListener { _, e -> onHandleTouch(Drag.TR, e) }
        handleBL.setOnTouchListener { _, e -> onHandleTouch(Drag.BL, e) }
        handleBR.setOnTouchListener { _, e -> onHandleTouch(Drag.BR, e) }
    }

    private fun onSpriteTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                if (!editMode) {
                    // aguarda o tempo padrao de long-press do sistema
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                } else {
                    beginDrag(Drag.SPRITE, e)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (editMode) {
                    if (dragging == Drag.SPRITE) moveDrag(e)
                    return true
                }
                // CORRECAO (bug critico): so cancela o long-press se o dedo
                // REALMENTE se moveu. O jitter natural de 1-2 px cancelava o
                // timer antes de disparar — por isso o editor nunca abria.
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (dx * dx + dy * dy > touchSlopSq) {
                    handler.removeCallbacks(longPressRunnable)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (editMode && dragging == Drag.SPRITE) endDrag()
                return true
            }
        }
        return false
    }

    private fun onHandleTouch(target: Drag, e: MotionEvent): Boolean {
        if (!editMode) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { beginDrag(target, e); return true }
            MotionEvent.ACTION_MOVE -> { moveDrag(e); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { endDrag(); return true }
        }
        return false
    }

    private fun beginDrag(target: Drag, e: MotionEvent) {
        dragging = target
        dragStartX = params.x
        dragStartY = params.y
        dragStartW = spriteW
        dragStartH = spriteH
        dragStartRawX = e.rawX
        dragStartRawY = e.rawY
    }

    private fun moveDrag(e: MotionEvent) {
        val dx = (e.rawX - dragStartRawX).toInt()
        val dy = (e.rawY - dragStartRawY).toInt()

        when (dragging) {
            Drag.SPRITE -> {
                params.x = dragStartX + dx
                params.y = dragStartY + dy
                clampToScreen()
                wm.updateViewLayout(root, params)
            }
            Drag.TL, Drag.TR, Drag.BL, Drag.BR -> {
                val newW = when (dragging) {
                    Drag.TL, Drag.BL -> dragStartW - dx
                    else -> dragStartW + dx
                }.coerceAtLeast(minW)
                val newH = (newW / aspect).toInt()
                val startH = (dragStartW / aspect).toInt()

                spriteW = newW
                spriteH = newH

                spriteView.layoutParams = FrameLayout.LayoutParams(newW, newH, Gravity.CENTER)
                spriteFrame.layoutParams = LinearLayout.LayoutParams(newW + 2 * handleR, newH + 2 * handleR)

                // mantem o canto oposto ancorado
                params.x = when (dragging) {
                    Drag.TL, Drag.BL -> dragStartX + (dragStartW - newW)
                    else -> dragStartX
                }
                params.y = when (dragging) {
                    Drag.TL, Drag.TR -> dragStartY + (startH - newH)
                    else -> dragStartY
                }
                clampToScreen()
                wm.updateViewLayout(root, params)
            }
            Drag.NONE -> {}
        }
    }

    private fun endDrag() {
        dragging = Drag.NONE
        saveAsRatio()
    }

    // ================== EDIT MODE ==================
    private fun enterEditMode() {
        if (editMode) return
        editMode = true
        runCatching { spriteView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        toolbar.visibility = View.VISIBLE
        setHandlesVisible(true)
        applyBitmap()
        // espera o toolbar ser medido para deslocar a janela e manter o sprite no lugar
        handler.postDelayed({
            if (!editMode || !::params.isInitialized) return@postDelayed
            val th = toolbar.height
            if (th > 0) {
                params.y -= th
                clampToScreen()
                runCatching { wm.updateViewLayout(root, params) }
            }
        }, 80)
    }

    private fun exitEditMode() {
        if (!editMode) return
        editMode = false
        val th = toolbar.height
        toolbar.visibility = View.GONE
        setHandlesVisible(false)
        if (th > 0) params.y += th
        clampToScreen()
        runCatching { wm.updateViewLayout(root, params) }
        saveAsRatio()
        applyBitmap()
    }

    private fun toggleEditMode() {
        if (!::root.isInitialized) return
        if (editMode) exitEditMode() else enterEditMode()
    }

    private fun setHandlesVisible(v: Boolean) {
        val vis = if (v) View.VISIBLE else View.GONE
        handleTL.visibility = vis
        handleTR.visibility = vis
        handleBL.visibility = vis
        handleBR.visibility = vis
    }

    private fun resetPosition() {
        val dm = resources.displayMetrics
        spriteW = (dm.widthPixels * 0.55f).toInt()
        spriteH = (spriteW / aspect).toInt()
        params.x = (dm.widthPixels - spriteW) / 2 - handleR
        params.y = dm.heightPixels - spriteH - dp(180)
        spriteView.layoutParams = FrameLayout.LayoutParams(spriteW, spriteH, Gravity.CENTER)
        spriteFrame.layoutParams = LinearLayout.LayoutParams(spriteW + 2 * handleR, spriteH + 2 * handleR)
        clampToScreen()
        wm.updateViewLayout(root, params)
        saveAsRatio()
    }

    // ================== NOTIFICACAO / FGS ==================
    private fun startForegroundCompat(): Boolean {
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        // Android 14+: FGS do tipo microphone exige RECORD_AUDIO concedido no
        // momento do startForeground, senao o sistema lanca SecurityException.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !micGranted) {
            stopSelf()
            return false
        }

        val channelId = "vtuber_overlay"
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(channelId) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(channelId, "VTuber", NotificationManager.IMPORTANCE_LOW)
            )
        }

        // getForegroundService (nao getService): garante inicio como foreground
        // mesmo que o app esteja em background na hora do toque na notificacao
        val editIntent = PendingIntent.getForegroundService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_EDIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getForegroundService(
            this, 2,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("VTuber ativo")
            .setContentText("Toque e segure o personagem para editar")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Editar", editIntent).build())
            .addAction(Notification.Action.Builder(null, "Parar", stopIntent).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif)
        }
        return true
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        runCatching { applicationContext.unregisterComponentCallbacks(rotationCallbacks) }
        handler.removeCallbacksAndMessages(null)
        voiceDetector?.stop() // desbloqueia o read() e libera o microfone ja
        scope.cancel()
        if (::root.isInitialized && ::wm.isInitialized) {
            runCatching { wm.removeView(root) }
        }
    }
}
