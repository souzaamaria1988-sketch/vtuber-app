
package com.vtuber.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] ?: hasMic()
        if (micGranted) startOverlayIfPossible()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) startOverlayIfPossible()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val versionLabel = appVersionLabel()
        setContent {
            VtuberTheme {
                StudioScreen(
                    versionText = versionLabel,
                    onStart = { onStartClicked() }
                )
            }
        }
    }

    private fun onStartClicked() {
        val toRequest = mutableListOf<String>()
        if (!hasMic()) toRequest.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !hasNotifications()) {
            toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (toRequest.isEmpty()) startOverlayIfPossible()
        else requestPermissions.launch(toRequest.toTypedArray())
    }

    private fun hasMic(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifications(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    private fun startOverlayIfPossible() {
        if (!Settings.canDrawOverlays(this)) {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
            return
        }
        // reinicia o servico para recarregar frames/settings do Studio
        runCatching { stopService(Intent(this, OverlayService::class.java)) }
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
    }

    @Suppress("DEPRECATION")
    private fun appVersionLabel(): String {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
            "v" + (info.versionName ?: "?") + " - build " + code
        } catch (e: Exception) {
            "v? - build ?"
        }
    }
}

@Composable
fun VtuberTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFFD0BCFF),
        onPrimary = Color(0xFF381E72),
        primaryContainer = Color(0xFF4F378B),
        onPrimaryContainer = Color(0xFFEADDFF),
        surface = Color(0xFF141218),
        surfaceVariant = Color(0xFF49454F),
        onSurface = Color(0xFFE6E0E9),
        onSurfaceVariant = Color(0xFFCAC4D0),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun StudioScreen(versionText: String, onStart: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var frames by remember { mutableStateOf(SpriteStore.loadFrames(ctx)) }
    var settings by remember { mutableStateOf(SpriteStore.loadSettings(ctx)) }
    var talking by remember { mutableStateOf(false) }
    var previewOn by remember { mutableStateOf(false) }
    var frameIdx by remember { mutableStateOf(0) }
    var calibrating by remember { mutableStateOf(false) }
    var calibMsg by remember { mutableStateOf("") }
    var editingFrame by remember { mutableStateOf<FrameItem?>(null) }

    fun save() { SpriteStore.save(ctx, frames, settings) }

    val effective = remember(frames) {
        if (frames.isEmpty()) SpriteStore.fallbackFrames() else frames
    }
    val idleList = effective.filter { it.category == "idle" }
    val talkList = effective.filter { it.category == "talking" }

    val bitmaps by produceState<Map<String, Bitmap>>(emptyMap(), effective) {
        value = withContext(Dispatchers.IO) {
            val m = mutableMapOf<String, Bitmap>()
            effective.forEach { f -> SpriteStore.loadBitmap(ctx, f)?.let { b -> m[f.id] = b } }
            m
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val added = withContext(Dispatchers.IO) { SpriteStore.importFrames(ctx, uris) }
            if (added.isNotEmpty()) {
                frames = frames + added
                save()
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (!granted) previewOn = false }

    // animacao de frames no preview
    LaunchedEffect(previewOn, talking, settings.idleIntervalMs, settings.talkingIntervalMs) {
        while (previewOn) {
            delay(if (talking) settings.talkingIntervalMs else settings.idleIntervalMs)
            frameIdx++
        }
    }

    // microfone ao vivo no preview
    LaunchedEffect(previewOn, settings.threshold, settings.talkFrames, settings.silenceFrames) {
        if (!previewOn) return@LaunchedEffect
        val main = Handler(Looper.getMainLooper())
        val det = VoiceDetector(ctx, settings.threshold, settings.talkFrames, settings.silenceFrames)
        det.run { t -> main.post { talking = t } }
    }

    // visual estilo Discord (animado)
    val talkAnim by animateFloatAsState(if (talking) 1f else 0f, tween(220))
    val alpha = settings.idleAlpha + (1f - settings.idleAlpha) * talkAnim
    val bright = (1f - settings.idleDim) + settings.idleDim * talkAnim
    val density = LocalDensity.current
    val sinkPx = with(density) { settings.idleOffsetDp.dp.toPx() } * (1f - talkAnim)
    val glowA = if (settings.glowWhenTalking) settings.glowAlpha * talkAnim else 0f

    val currentList = if (talking) talkList.ifEmpty { idleList } else idleList
    val currentFrame = currentList.getOrNull(frameIdx % currentList.size.coerceAtLeast(1))
    val currentBmp = currentFrame?.let { bitmaps[it.id] }
    val baseBmp = bitmaps[(idleList.ifEmpty { talkList }).firstOrNull()?.id]
    val aspect = if (baseBmp != null && baseBmp.height > 0)
        baseBmp.width.toFloat() / baseBmp.height else 1f

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Rounded.Mic, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(6.dp))
            Text("VTuber Studio", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(versionText, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(14.dp))

            // ==================== PREVIEW AO VIVO ====================
            // CORRECAO: o gesto de arraste agora fica numa Box INTERNA (dentro
            // do escopo do BoxWithConstraints, onde "constraints" existe).
            // Antes estava no modifier externo � fora do escopo.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF100E14))
            ) {
                val cw = constraints.maxWidth.toFloat()
                val ch = constraints.maxHeight.toFloat()

                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(cw, ch) {
                            detectDragGestures(
                                onDrag = { change, drag ->
                                    change.consume()
                                    settings = settings.copy(
                                        xRatio = (settings.xRatio + drag.x / cw).coerceIn(0f, 1f),
                                        yRatio = (settings.yRatio + drag.y / ch).coerceIn(0f, 1f),
                                    )
                                },
                                onDragEnd = { save() }
                            )
                        }
                ) {
                    val spriteW = cw * settings.wRatio
                    val spriteH = spriteW / aspect
                    val glowPad = with(density) { 8.dp.toPx() }
                    val ox = settings.xRatio * cw - spriteW / 2f
                    val oy = settings.yRatio * ch - spriteH / 2f + sinkPx

                    if (settings.glowWhenTalking) {
                        Box(
                            Modifier
                                .offset { IntOffset((ox - glowPad).roundToInt(), (oy - glowPad).roundToInt()) }
                                .size(
                                    with(density) { (spriteW + 2 * glowPad).toDp() },
                                    with(density) { (spriteH + 2 * glowPad).toDp() }
                                )
                                .border(3.dp, Color.White.copy(alpha = glowA), RoundedCornerShape(18.dp))
                                .background(Color.White.copy(alpha = glowA * 0.12f), RoundedCornerShape(18.dp))
                        )
                    }
                    if (currentBmp != null) {
                        Image(
                            bitmap = currentBmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }
                                .size(
                                    with(density) { spriteW.toDp() },
                                    with(density) { spriteH.toDp() }
                                ),
                            contentScale = ContentScale.Fit,
                            alpha = alpha,
                            // CORRECAO: ColorMatrix do Compose nao tem setScale.
                            // Escurecimento = multiplicar RGB por um cinza (Multiply).
                            colorFilter = if (bright < 0.99f) {
                                ColorFilter.tint(Color(bright, bright, bright), BlendMode.Multiply)
                            } else null
                        )
                    } else {
                        Text(
                            "Importe imagens (ou use as 3 padro)\npara comear",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    if (previewOn) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (talking) Color(0xFF7DDB8A) else Color(0xFF57525E))
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Arraste o personagem no preview para posicionar.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // ==================== FRAMES ====================
            SectionTitle("Frames � idle: ${idleList.size} � talking: ${talkList.size}")
            Text(
                "Nome do arquivo com \"talk\"  lista Falando; nmero no fim = ordem " +
                "(ex.: idle3.png, talking2.png). Toque numa thumb para reordenar/trocar/remover.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text("IDLE (calado)", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow {
                items(idleList) { f ->
                    FrameThumb(bitmaps[f.id], f.label) { editingFrame = f }
                }
                item {
                    ImportTile { importLauncher.launch("image/*") }
                }
            }
            Text("TALKING (falando)", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow {
                items(talkList) { f ->
                    FrameThumb(bitmaps[f.id], f.label) { editingFrame = f }
                }
                item {
                    ImportTile { importLauncher.launch("image/*") }
                }
            }

            // ==================== POSICAO ====================
            SectionTitle("Posio e tamanho")
            SettingSlider("Tamanho", settings.wRatio, 0.15f..0.95f,
                "${(settings.wRatio * 100).toInt()}%",
                { settings = settings.copy(wRatio = it) }, { save() })
            Row(Modifier.fillMaxWidth()) {
                Button(onClick = {
                    settings = settings.copy(xRatio = 0.5f, yRatio = 0.5f); save()
                }, modifier = Modifier.weight(1f)) { Text("Centralizar") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    settings = settings.copy(xRatio = 0.5f, yRatio = 0.85f); save()
                }, modifier = Modifier.weight(1f)) { Text("Fundo") }
            }

            // ==================== ESTILO DISCORD ====================
            SectionTitle("Estilo Discord (silncio  fala)")
            SettingSlider("Transparncia no silncio", settings.idleAlpha, 0.1f..1f,
                "${(settings.idleAlpha * 100).toInt()}%",
                { settings = settings.copy(idleAlpha = it) }, { save() })
            SettingSlider("Escurecer no silncio", settings.idleDim, 0f..0.8f,
                "${(settings.idleDim * 100).toInt()}%",
                { settings = settings.copy(idleDim = it) }, { save() })
            SettingSlider("Descer no silncio", settings.idleOffsetDp, 0f..40f,
                "${settings.idleOffsetDp.toInt()} dp",
                { settings = settings.copy(idleOffsetDp = it) }, { save() })
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Brilho branco ao falar", fontSize = 13.sp)
                Switch(checked = settings.glowWhenTalking, onCheckedChange = {
                    settings = settings.copy(glowWhenTalking = it); save()
                })
            }
            if (settings.glowWhenTalking) {
                SettingSlider("Intensidade do brilho", settings.glowAlpha, 0.1f..1f,
                    "${(settings.glowAlpha * 100).toInt()}%",
                    { settings = settings.copy(glowAlpha = it) }, { save() })
            }

            // ==================== ANIMACAO E MIC ====================
            SectionTitle("Animao e microfone")
            SettingSlider("Velocidade idle", settings.idleIntervalMs / 1000f, 0.08f..1.2f,
                "${settings.idleIntervalMs} ms",
                { settings = settings.copy(idleIntervalMs = (it * 1000).toLong()) }, { save() })
            SettingSlider("Velocidade falando", settings.talkingIntervalMs / 1000f, 0.06f..0.4f,
                "${settings.talkingIntervalMs} ms",
                { settings = settings.copy(talkingIntervalMs = (it * 1000).toLong()) }, { save() })
            SettingSlider("Sensibilidade (menor = mais fcil)", settings.threshold.toFloat(),
                80f..1500f, "${settings.threshold.toInt()}",
                { settings = settings.copy(threshold = it.toDouble()) }, { save() })
            Button(
                onClick = {
                    scope.launch {
                        calibrating = true; calibMsg = ""
                        val det = VoiceDetector(ctx, settings.threshold)
                        val rms = det.calibrateNoiseFloor()
                        if (rms != null) {
                            val t = (rms * 1.6).coerceIn(120.0, 1500.0)
                            settings = settings.copy(threshold = t); save()
                            calibMsg = "Rudo ambiente: ${rms.toInt()}  sensibilidade ${t.toInt()}"
                        } else {
                            calibMsg = "No consegui medir (microfone ocupado?)"
                        }
                        calibrating = false
                    }
                },
                enabled = !calibrating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (calibrating) "Calibrando� fique quietinho" else " Calibrar microfone")
            }
            if (calibMsg.isNotEmpty()) {
                Text(calibMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center)
            }

            // ==================== ACOES ====================
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (previewOn) {
                        previewOn = false
                    } else {
                        previewOn = true
                        if (ContextCompat.checkSelfPermission(
                                ctx, Manifest.permission.RECORD_AUDIO
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (previewOn) " Parar preview ao vivo" else " Preview ao vivo (microfone)")
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("Ativar overlay")
            }
            Spacer(Modifier.height(6.dp))
            Text("Tocar \"Ativar overlay\" de novo reinicia o overlay com as mudanas.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(30.dp))
        }
    }

    // dialog de edicao de frame
    editingFrame?.let { f ->
        AlertDialog(
            onDismissRequest = { editingFrame = null },
            title = { Text(f.label) },
            text = {
                Text(if (f.category == "idle") "Categoria: Idle (calado)" else "Categoria: Talking (falando)")
            },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        frames = moveFrame(frames, f.id, -1); save()
                    }) { Text(" Mover para trs") }
                    TextButton(onClick = {
                        frames = moveFrame(frames, f.id, +1); save()
                    }) { Text("Mover para frente ") }
                    TextButton(onClick = {
                        frames = frames.map {
                            if (it.id == f.id)
                                it.copy(category = if (it.category == "idle") "talking" else "idle")
                            else it
                        }
                        save(); editingFrame = null
                    }) { Text("Trocar para ${if (f.category == "idle") "Talking" else "Idle"}") }
                    TextButton(onClick = {
                        frames = frames.filterNot { it.id == f.id }
                        SpriteStore.deleteFrame(ctx, f)
                        save(); editingFrame = null
                    }) { Text("Remover", color = Color(0xFFF2B8B5)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { editingFrame = null }) { Text("Fechar") }
            }
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)
    )
}

@Composable
fun SettingSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(display, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            onValueChangeFinished = onDone
        )
    }
}

@Composable
fun FrameThumb(bmp: Bitmap?, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(86.dp).clickable { onClick() }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(74.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF26232B)),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("�", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            label, fontSize = 9.sp, maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ImportTile(onClick: () -> Unit) {
    Box(
        Modifier.size(74.dp).clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF4F378B))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Add, null, tint = Color(0xFFEADDFF))
    }
}

private fun moveFrame(list: List<FrameItem>, id: String, delta: Int): List<FrameItem> {
    val idx = list.indexOfFirst { it.id == id }
    if (idx < 0) return list
    val cat = list[idx].category
    val indices = list.indices.filter { list[it].category == cat }
    val pos = indices.indexOf(idx)
    val newPos = pos + delta
    if (newPos < 0 || newPos >= indices.size) return list
    val other = indices[newPos]
    val copy = list.toMutableList()
    val tmp = copy[idx]
    copy[idx] = copy[other]
    copy[other] = tmp
    return copy.toList()
}
