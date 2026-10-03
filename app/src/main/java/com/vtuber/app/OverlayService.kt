package com.vtuber.app

import android.Manifest
import android.animation.ValueAnimator
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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.AccelerateDecelerateInterpolator
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
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
import kotlin.math.PI
import kotlin.math.sin

/**
 * Overlay MULTI-PERSONAGEM: cada personagem tem sua propria janela,
 * posicao, tamanho e frames. Animacoes:
 *  - entrada: pop com overshoot + fade, escalonado por personagem;
 *  - idle: flutuacao (bob) com fase diferente por personagem;
 *  - falar: pop de escala (bounce) + brilho + sobe/nitido (estilo Discord).
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_TOGGLE_EDIT = "com.vtuber.app.TOGGLE_EDIT"
        const val ACTION_STOP = "com.vtuber.app.STOP"
        const val ACTION_TOGGLE_MIC = "com.vtuber.app.TOGGLE_MIC"
        private const val HANDLE_DP = 14
        private const val MIN_W_DP = 60
        private const val GLOW_PAD_DP = 8
        private const val BOB_AMP_DP = 5
        private const val BOB_PERIOD_MS = 2600L
    }

    private lateinit var wm: WindowManager
    private lateinit var settings: SpriteSettings
    private var characters: MutableList<CharacterData> = mutableListOf()

    private val layers = mutableListOf<Layer>()

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var voiceDetector: VoiceDetector? = null

    @Volatile private var detectorPaused = false

    private var handleR = 0
    private var minW = 0
    private var glowPad = 0
    private var bobAmpPx = 0f
    private var longPressTimeout = 500L
    private var touchSlopSq = 1

    @Volatile private var talking = false
    private var talkLevel = 0f
    private var talkAnimator: ValueAnimator? = null
    private var bobPhase = 0f
    private var bobAnimator: ValueAnimator? = null

    private var editMode = false
    private var selectedLayer: Layer? = null

    private enum class Drag { NONE, SPRITE, TL, TR, BL, BR }
    private var dragging = Drag.NONE
    private var dragLayer: Layer? = null
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragStartW = 0
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var downLayer: Layer? = null
    private var downRawX = 0f
    private var downRawY = 0f

    private val longPressRunnable = Runnable { downLayer?.let { enterEditMode(it) } }
    private val rotationRetry = Runnable { handleRotation() }

    private val frameRunnable: Runnable = object : Runnable {
        override fun run() {
            layers.forEach { advanceFrame(it) }
            handler.postDelayed(
                this,
                if (talking) settings.talkingIntervalMs else settings.idleIntervalMs
            )
        }
    }

    private val rotationCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = handleRotationSoon()
        override fun onLowMemory() {}
        override fun onTrimMemory(level: Int) {}
    }

    /** Um personagem = uma camada = uma janela propria. */
    private inner class Layer(
        val index: Int,
        val charId: String,
        var xRatio: Float,
        var yRatio: Float,
        var wRatio: Float,
        val idleFrames: MutableList<Bitmap>,
        val talkingFrames: MutableList<Bitmap>,
    ) {
        lateinit var root: LinearLayout
        lateinit var toolbar: LinearLayout
        lateinit var spriteFrame: FrameLayout
        lateinit var spriteView: ImageView
        lateinit var glowView: View
        lateinit var handleTL: View
        lateinit var handleTR: View
        lateinit var handleBL: View
        lateinit var handleBR: View
        lateinit var params: WindowManager.LayoutParams
        var aspect = 1f
        var spriteW = 0
        var spriteH = 0
        var idleIdx = 0
        var talkIdx = 0

        fun computeSize(minWidth: Int) {
            val dm = resources.displayMetrics
            spriteW = (dm.widthPixels * wRatio).toInt().coerceAtLeast(minWidth)
            spriteH = (spriteW / aspect).toInt()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        if (!startForegroundCompat()) return

        settings = SpriteStore.loadSettings(this)
        val loaded = SpriteStore.loadCharacters(this)
        characters = (if (loaded.isEmpty()) SpriteStore.fallbackCharacters() else loaded)
            .toMutableList()

        handleR = dp(HANDLE_DP)
        minW = dp(MIN_W_DP)
        glowPad = dp(GLOW_PAD_DP)
        bobAmpPx = dp(BOB_AMP_DP).toFloat()
        longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        val slop = ViewConfiguration.get(this).scaledTouchSlop * 2
        touchSlopSq = slop * slop

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val config = ConfigParser.load(this)
        characters.forEachIndexed { index, ch -> addLayer(index, ch, config) }
        if (layers.isEmpty()) { stopSelf(); return }

        startBob()
        // entrada "linda": pop escalonado, um personagem apos o outro
        layers.forEachIndexed { i, l -> animateEntrance(l, 130L * i) }
        handler.postDelayed(frameRunnable, settings.idleIntervalMs)
        startVoice()

        applicationContext.registerComponentCallbacks(rotationCallbacks)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundCompat()) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_TOGGLE_EDIT -> toggleEditMode()
            ACTION_TOGGLE_MIC -> toggleDetector()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    // ================== CAMADAS ==================
    private fun addLayer(index: Int, ch: CharacterData, config: VtuberConfig) {
        val idle = mutableListOf<Bitmap>()
        val talking = mutableListOf<Bitmap>()
        ch.frames.forEach { f ->
            val crop = if (f.fileName.isBlank()) config.cropTop else 0f
            val bmp = SpriteStore.loadBitmap(this, f, crop) ?: return@forEach
            if (f.category == "talking") talking.add(bmp) else idle.add(bmp)
        }
        if (idle.isEmpty() && talking.isEmpty()) return
        val base = idle.firstOrNull() ?: talking.first()
        val layer = Layer(index, ch.id, ch.xRatio, ch.yRatio, ch.wRatio, idle, talking)
        layer.aspect = base.width.toFloat() / base.height.toFloat()
        layer.computeSize(minW)
        buildLayerViews(layer)
        addLayerWindow(layer)
        layers.add(layer)
        applyTalkLevelTo(layer)
    }

    private fun buildLayerViews(layer: Layer) {
        layer.root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        layer.toolbar = buildToolbar()
        layer.root.addView(
            layer.toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        layer.spriteFrame = FrameLayout(this).apply { setPadding(handleR, handleR, handleR, handleR) }

        layer.glowView = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0x1AFFFFFF)
                setStroke(dp(3), Color.WHITE)
            }
        }
        layer.spriteFrame.addView(
            layer.glowView,
            FrameLayout.LayoutParams(
                layer.spriteW + 2 * glowPad, layer.spriteH + 2 * glowPad, Gravity.CENTER
            )
        )

        layer.spriteView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
        }
        layer.spriteFrame.addView(
            layer.spriteView,
            FrameLayout.LayoutParams(layer.spriteW, layer.spriteH, Gravity.CENTER)
        )
        layer.spriteView.setImageBitmap(layer.idleFrames.firstOrNull() ?: layer.talkingFrames.firstOrNull())

        layer.handleTL = makeHandle()
        layer.handleTR = makeHandle()
        layer.handleBL = makeHandle()
        layer.handleBR = makeHandle()
        layer.spriteFrame.addView(layer.handleTL, FrameLayout.LayoutParams(2 * handleR, 2 * handleR, Gravity.TOP or Gravity.START))
        layer.spriteFrame.addView(layer.handleTR, FrameLayout.LayoutParams(2 * handleR, 2 * handleR, Gravity.TOP or Gravity.END))
        layer.spriteFrame.addView(layer.handleBL, FrameLayout.LayoutParams(2 * handleR, 2 * handleR, Gravity.BOTTOM or Gravity.START))
        layer.spriteFrame.addView(layer.handleBR, FrameLayout.LayoutParams(2 * handleR, 2 * handleR, Gravity.BOTTOM or Gravity.END))

        layer.spriteFrame.layoutParams = LinearLayout.LayoutParams(
            layer.spriteW + 2 * handleR, layer.spriteH + 2 * handleR
        )
        layer.root.addView(layer.spriteFrame)

        setHandlesVisible(layer, false)
        setupLayerTouches(layer)
    }

    private fun buildToolbar(): LinearLayout {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xEE1D1B20.toInt())
                setStroke(dp(1), 0xFFD0BCFF.toInt())
            }
            visibility = View.GONE
        }
        val next = makeToolbarBtn("⇄") { cycleSelection() }
        val studio = makeToolbarBtn("Studio") { openStudio() }
        val reset = makeToolbarBtn("Resetar") { resetSelected() }
        val done = makeToolbarBtn("Pronto") { exitEditMode() }
        t.addView(next)
        t.addView(View(this), LinearLayout.LayoutParams(dp(4), 1))
        t.addView(studio)
        t.addView(View(this), LinearLayout.LayoutParams(dp(4), 1))
        t.addView(reset)
        t.addView(View(this), LinearLayout.LayoutParams(dp(4), 1))
        t.addView(done)
        return t
    }

    private fun makeToolbarBtn(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(0xFFEADDFF.toInt())
            textSize = 12f
            setPadding(dp(10), dp(9), dp(10), dp(9))
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

    private fun addLayerWindow(layer: Layer) {
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        layer.params = p
        positionFromRatios(layer)
        wm.addView(layer.root, p)
    }

    private fun positionFromRatios(layer: Layer) {
        val dm = resources.displayMetrics
        val fw = layer.spriteW + 2 * handleR
        val fh = layer.spriteH + 2 * handleR
        layer.params.x = (dm.widthPixels * layer.xRatio).toInt() - fw / 2
        layer.params.y = (dm.heightPixels * layer.yRatio).toInt() - fh / 2
        clampLayer(layer)
    }

    private fun clampLayer(layer: Layer) {
        val dm = resources.displayMetrics
        val w = layer.spriteW + 2 * handleR
        val h = layer.spriteH + 2 * handleR
        val minVisible = dp(40)
        layer.params.x = layer.params.x.coerceIn(-w + minVisible, dm.widthPixels - minVisible)
        layer.params.y = layer.params.y.coerceIn(-h + minVisible, dm.heightPixels - minVisible)
    }

    private fun setLayerSize(layer: Layer, w: Int, h: Int) {
        layer.spriteW = w
        layer.spriteH = h
        layer.spriteView.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        layer.glowView.layoutParams = FrameLayout.LayoutParams(
            w + 2 * glowPad, h + 2 * glowPad, Gravity.CENTER
        )
        layer.spriteFrame.layoutParams = LinearLayout.LayoutParams(
            w + 2 * handleR, h + 2 * handleR
        )
    }

    // ================== ANIMACOES ==================
    private fun animateEntrance(layer: Layer, delayMs: Long) {
        layer.spriteFrame.scaleX = 0.25f
        layer.spriteFrame.scaleY = 0.25f
        layer.spriteFrame.alpha = 0f
        layer.spriteFrame.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setStartDelay(delayMs)
            .setDuration(420)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()
    }

    private fun popLayer(layer: Layer) {
        runCatching {
            layer.spriteFrame.animate().cancel()
            layer.spriteFrame.scaleX = 1f
            layer.spriteFrame.scaleY = 1f
            layer.spriteFrame.animate()
                .scaleX(1.06f).scaleY(1.06f)
                .setDuration(130)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    layer.spriteFrame.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(300)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .start()
                }
                .start()
        }
    }

    private fun startBob() {
        bobAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BOB_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                bobPhase = anim.animatedValue as Float
                val dens = resources.displayMetrics.density
                layers.forEach { applyLayerTranslation(it, dens) }
            }
            start()
        }
    }

    // ================== VOZ / EFEITO DISCORD ==================
    private fun startVoice() {
        scope.launch {
            val detector = VoiceDetector(
                appContext = applicationContext,
                threshold = settings.threshold,
                talkFrames = settings.talkFrames,
                silenceFrames = settings.silenceFrames,
            )
            voiceDetector = detector
            detector.run { talkingNow ->
                handler.post {
                    if (talkingNow != talking) {
                        talking = talkingNow
                        layers.forEach { l ->
                            if (talkingNow) {
                                l.talkIdx = 0
                                l.talkingFrames.firstOrNull()?.let { l.spriteView.setImageBitmap(it) }
                            } else {
                                l.idleIdx = 0
                                l.idleFrames.firstOrNull()?.let { l.spriteView.setImageBitmap(it) }
                            }
                        }
                        setTalkingVisual(talkingNow)
                    }
                }
            }
        }
    }

    private fun setTalkingVisual(t: Boolean) {
        val target = if (t) 1f else 0f
        if (t) layers.forEach { popLayer(it) }
        if (target == talkLevel) return
        talkAnimator?.cancel()
        val start = talkLevel
        talkAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim -> applyTalkLevel(anim.animatedValue as Float) }
            start()
        }
    }

    private fun applyTalkLevel(level: Float) {
        talkLevel = level
        layers.forEach { applyTalkLevelTo(it) }
    }

    private fun applyTalkLevelTo(layer: Layer) {
        val dens = resources.displayMetrics.density
        layer.spriteView.alpha = settings.idleAlpha + (1f - settings.idleAlpha) * talkLevel
        val bright = (1f - settings.idleDim) + settings.idleDim * talkLevel
        val v = (bright * 255).toInt().coerceIn(0, 255)
        layer.spriteView.colorFilter = PorterDuffColorFilter(
            Color.rgb(v, v, v), PorterDuff.Mode.MULTIPLY
        )
        layer.glowView.alpha = if (settings.glowWhenTalking) talkLevel * settings.glowAlpha else 0f
        applyLayerTranslation(layer, dens)
    }

    private fun applyLayerTranslation(layer: Layer, dens: Float) {
        val sink = (1f - talkLevel) * settings.idleOffsetDp * dens
        // bob com fase diferente por personagem (ondinha); para quando fala
        val bob = sin((bobPhase + layer.index * 0.13f) * 2.0 * PI).toFloat() * bobAmpPx
        layer.spriteView.translationY = sink + bob
    }

    private fun advanceFrame(layer: Layer) {
        if (editMode) return
        if (talking && layer.talkingFrames.isNotEmpty()) {
            layer.talkIdx = (layer.talkIdx + 1) % layer.talkingFrames.size
            layer.spriteView.setImageBitmap(layer.talkingFrames[layer.talkIdx])
        } else if (layer.idleFrames.isNotEmpty()) {
            layer.idleIdx = (layer.idleIdx + 1) % layer.idleFrames.size
            layer.spriteView.setImageBitmap(layer.idleFrames[layer.idleIdx])
        }
    }

    // ================== PERSISTENCIA ==================
    private fun saveLayerRatio(layer: Layer) {
        val dm = resources.displayMetrics
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) return
        val fw = layer.spriteW + 2 * handleR
        val fh = layer.spriteH + 2 * handleR
        layer.xRatio = ((layer.params.x + fw / 2) / dm.widthPixels.toFloat()).coerceIn(0f, 1f)
        layer.yRatio = ((layer.params.y + fh / 2) / dm.heightPixels.toFloat()).coerceIn(0f, 1f)
        layer.wRatio = layer.spriteW / dm.widthPixels.toFloat()
        persistCharacters()
    }

    private fun persistCharacters() {
        val updated = characters.map { c ->
            val l = layers.firstOrNull { it.charId == c.id }
            if (l != null) c.copy(xRatio = l.xRatio, yRatio = l.yRatio, wRatio = l.wRatio) else c
        }
        characters = updated.toMutableList()
        SpriteStore.saveAll(this, characters, settings)
    }

    // ================== EDIT MODE ==================
    private fun enterEditMode(layer: Layer) {
        editMode = true
        runCatching { layer.spriteView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        selectLayer(layer)
    }

    private fun selectLayer(layer: Layer) {
        selectedLayer?.let { prev ->
            prev.toolbar.visibility = View.GONE
            setHandlesVisible(prev, false)
        }
        selectedLayer = layer
        layer.toolbar.visibility = View.VISIBLE
        setHandlesVisible(layer, true)
    }

    private fun cycleSelection() {
        if (layers.isEmpty()) return
        val cur = selectedLayer
        val idx = if (cur == null) 0 else layers.indexOf(cur)
        selectLayer(layers[(idx + 1) % layers.size])
    }

    private fun exitEditMode() {
        editMode = false
        selectedLayer?.let { l ->
            l.toolbar.visibility = View.GONE
            setHandlesVisible(l, false)
        }
        selectedLayer = null
        persistCharacters()
    }

    private fun toggleEditMode() {
        if (editMode) exitEditMode() else if (layers.isNotEmpty()) enterEditMode(layers.first())
    }

    private fun setHandlesVisible(layer: Layer, v: Boolean) {
        val vis = if (v) View.VISIBLE else View.GONE
        layer.handleTL.visibility = vis
        layer.handleTR.visibility = vis
        layer.handleBL.visibility = vis
        layer.handleBR.visibility = vis
    }

    private fun resetSelected() {
        val layer = selectedLayer ?: return
        layer.xRatio = 0.5f
        layer.yRatio = 0.82f
        layer.wRatio = 0.55f
        layer.computeSize(minW)
        setLayerSize(layer, layer.spriteW, layer.spriteH)
        positionFromRatios(layer)
        runCatching { wm.updateViewLayout(layer.root, layer.params) }
        persistCharacters()
    }

    // ================== ROTACAO ==================
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleRotationSoon()
    }

    private fun handleRotationSoon() {
        if (layers.isEmpty()) return
        handleRotation()
        handler.removeCallbacks(rotationRetry)
        handler.postDelayed(rotationRetry, 350)
    }

    private fun handleRotation() {
        layers.forEach { l ->
            l.computeSize(minW)
            setLayerSize(l, l.spriteW, l.spriteH)
            positionFromRatios(l)
            runCatching { wm.updateViewLayout(l.root, l.params) }
        }
    }

    // ================== TOUCH ==================
    private fun setupLayerTouches(layer: Layer) {
        layer.spriteView.setOnTouchListener { _, e -> onSpriteTouch(layer, e) }
        layer.handleTL.setOnTouchListener { _, e -> onHandleTouch(layer, Drag.TL, e) }
        layer.handleTR.setOnTouchListener { _, e -> onHandleTouch(layer, Drag.TR, e) }
        layer.handleBL.setOnTouchListener { _, e -> onHandleTouch(layer, Drag.BL, e) }
        layer.handleBR.setOnTouchListener { _, e -> onHandleTouch(layer, Drag.BR, e) }
    }

    private fun onSpriteTouch(layer: Layer, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downLayer = layer
                downRawX = e.rawX
                downRawY = e.rawY
                if (!editMode) {
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                } else if (layer === selectedLayer) {
                    beginDrag(layer, Drag.SPRITE, e)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (editMode && dragging == Drag.SPRITE && dragLayer === layer) {
                    moveDrag(e)
                    return true
                }
                if (!editMode) {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (dx * dx + dy * dy > touchSlopSq) {
                        handler.removeCallbacks(longPressRunnable)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (dragging != Drag.NONE && dragLayer === layer) endDrag()
                return true
            }
        }
        return false
    }

    private fun onHandleTouch(layer: Layer, target: Drag, e: MotionEvent): Boolean {
        if (!editMode || layer !== selectedLayer) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { beginDrag(layer, target, e); return true }
            MotionEvent.ACTION_MOVE -> { moveDrag(e); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { endDrag(); return true }
        }
        return false
    }

    private fun beginDrag(layer: Layer, target: Drag, e: MotionEvent) {
        dragging = target
        dragLayer = layer
        dragStartX = layer.params.x
        dragStartY = layer.params.y
        dragStartW = layer.spriteW
        dragStartRawX = e.rawX
        dragStartRawY = e.rawY
    }

    private fun moveDrag(e: MotionEvent) {
        val layer = dragLayer ?: return
        val dx = (e.rawX - dragStartRawX).toInt()
        val dy = (e.rawY - dragStartRawY).toInt()
        when (dragging) {
            Drag.SPRITE -> {
                layer.params.x = dragStartX + dx
                layer.params.y = dragStartY + dy
                clampLayer(layer)
                runCatching { wm.updateViewLayout(layer.root, layer.params) }
            }
            Drag.TL, Drag.TR, Drag.BL, Drag.BR -> {
                val newW = when (dragging) {
                    Drag.TL, Drag.BL -> dragStartW - dx
                    else -> dragStartW + dx
                }.coerceAtLeast(minW)
                val newH = (newW / layer.aspect).toInt()
                val startH = (dragStartW / layer.aspect).toInt()
                setLayerSize(layer, newW, newH)
                layer.params.x = when (dragging) {
                    Drag.TL, Drag.BL -> dragStartX + (dragStartW - newW)
                    else -> dragStartX
                }
                layer.params.y = when (dragging) {
                    Drag.TL, Drag.TR -> dragStartY + (startH - newH)
                    else -> dragStartY
                }
                clampLayer(layer)
                runCatching { wm.updateViewLayout(layer.root, layer.params) }
            }
            Drag.NONE -> {}
        }
    }

    private fun endDrag() {
        val layer = dragLayer
        dragging = Drag.NONE
        dragLayer = null
        layer?.let { saveLayerRatio(it) }
    }

    // ================== ACOES ==================
    private fun openStudio() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        exitEditMode()
    }

    private fun toggleDetector() {
        if (!::settings.isInitialized) return
        detectorPaused = !detectorPaused
        voiceDetector?.let { if (detectorPaused) it.pause() else it.resume() }
        startForegroundCompat()
    }

    // ================== NOTIFICACAO / FGS ==================
    private fun startForegroundCompat(): Boolean {
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

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

        val editIntent = PendingIntent.getForegroundService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_EDIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val micIntent = PendingIntent.getForegroundService(
            this, 3,
            Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_MIC),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getForegroundService(
            this, 2,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val count = layers.size
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("VTuber ativo ($count personagen${if (count == 1) "" else "s"})")
            .setContentText(
                if (detectorPaused) "Detector pausado — microfone 100% livre"
                else "Segure um personagem para editar"
            )
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Editar", editIntent).build())
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (detectorPaused) "Retomar detector" else "Pausar detector",
                    micIntent
                ).build()
            )
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
        bobAnimator?.cancel()
        talkAnimator?.cancel()
        voiceDetector?.stop()
        scope.cancel()
        if (::wm.isInitialized) {
            layers.forEach { l -> runCatching { wm.removeView(l.root) } }
        }
    }
}
