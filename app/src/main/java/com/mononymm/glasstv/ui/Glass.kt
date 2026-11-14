package com.mononymm.glasstv.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mononymm.glasstv.plex.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

object G {
    val bg = Color(0xFF09090C)
    val red = Color(0xFFE8333F)
    val redDeep = Color(0xFF8A0F18)
    val text = Color(0xFFF4F4F6)
    val dim = Color(0xB3F4F4F6)
    val faint = Color(0x73F4F4F6)
    val glass = Color(0x1AFFFFFF)
    val glassHi = Color(0x33FFFFFF)
    val rim = Color(0x24FFFFFF)
    val panel = Color(0xE6101015)
    val cardShape = RoundedCornerShape(10.dp)
    val pillShape = RoundedCornerShape(50)

    val title = TextStyle(color = text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    val h2 = TextStyle(color = text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(color = dim, fontSize = 14.sp, lineHeight = 20.sp)
    val small = TextStyle(color = dim, fontSize = 12.sp)
    val label = TextStyle(color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

val LocalApp = staticCompositionLocalOf<AppModel> { error("no app") }

/**
 * D-pad friendly surface: grows a little on focus with a red glow and bright rim.
 * Center/Enter = click, holding Center (or the Menu key) = long click.
 */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = G.cardShape,
    focusScale: Float = 1.06f,
    idleColor: Color = G.glass,
    focusColor: Color = G.glassHi,
    glow: Boolean = true,
    onFocus: (() -> Unit)? = null,
    focusEnabled: Boolean = true,
    idleRim: Color = G.rim,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) focusScale else 1f, tween(160), label = "scale")
    val glowA by animateFloatAsState(if (focused && glow) 1f else 0f, tween(200), label = "glow")
    var longFired by remember { mutableStateOf(false) }
    LaunchedEffect(focused) { if (focused) onFocus?.invoke() }
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .glow(shape, glowA)
            .clip(shape)
            .background(if (focused) focusColor else idleColor)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color(0xE6FFFFFF) else idleRim, shape)
            .onPreviewKeyEvent { ev ->
                val k = ev.nativeKeyEvent
                val isSelect = k.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    k.keyCode == AndroidKeyEvent.KEYCODE_ENTER || k.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                when {
                    k.keyCode == AndroidKeyEvent.KEYCODE_MENU && onLongClick != null -> {
                        if (ev.type == KeyEventType.KeyUp) onLongClick()
                        true
                    }
                    !isSelect -> false
                    ev.type == KeyEventType.KeyDown -> {
                        if (k.repeatCount == 0) longFired = false
                        if (k.repeatCount >= 1 && !longFired && onLongClick != null) {
                            longFired = true
                            onLongClick()
                        }
                        true
                    }
                    ev.type == KeyEventType.KeyUp -> {
                        if (!longFired) onClick()
                        longFired = false
                        true
                    }
                    else -> false
                }
            }
            .focusable(enabled = focusEnabled, interactionSource = src),
    ) { content(focused) }
}

@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onFocus: (() -> Unit)? = null,
) {
    FocusBox(
        onClick = onClick,
        modifier = modifier,
        shape = G.pillShape,
        focusScale = 1.08f,
        idleColor = if (selected) Color(0x40FFFFFF) else G.glass,
        focusColor = Color(0xF2F4F4F6),
        glow = false,
        onFocus = onFocus,
    ) { f ->
        Text(
            text,
            style = G.label.copy(color = if (f) Color(0xFF0B0B0F) else G.text),
            modifier = Modifier.padding(PaddingValues(horizontal = 16.dp, vertical = 7.dp)),
        )
    }
}

/** Outlined action button (Play, Mark watched, ...). */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: (@Composable (Color) -> Unit)? = null,
) {
    FocusBox(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        focusScale = 1.05f,
        idleColor = if (primary) Color(0x26E8333F) else G.glass,
        focusColor = if (primary) G.red else Color(0xF2F4F4F6),
        glow = primary,
    ) { f ->
        val fg = when {
            f && primary -> Color.White
            f -> Color(0xFF0B0B0F)
            else -> G.text
        }
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                icon(fg)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = G.label.copy(color = fg))
        }
    }
}

/** Red outlined play triangle, optically centred. */
@Composable
fun PlayGlyph(color: Color, size: Dp = 14.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.22f, h * 0.12f)
            lineTo(w * 0.90f, h * 0.50f)
            lineTo(w * 0.22f, h * 0.88f)
            close()
        }
        drawPath(p, color, style = Stroke(width = w * 0.13f, join = StrokeJoin.Round))
    }
}

/** Thin progress bar. */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, color: Color = G.red) {
    Box(modifier.height(3.dp).clip(G.pillShape).background(Color(0x40FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(color))
    }
}

val scrimLeft = Brush.horizontalGradient(0f to Color(0xF209090C), 0.45f to Color(0xB309090C), 1f to Color(0x0009090C))
val scrimBottom = Brush.verticalGradient(0f to Color(0x0009090C), 0.55f to Color(0x9909090C), 1f to G.bg)

// ---------------- images ----------------

object Images {
    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt(),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }
    private val gate = Semaphore(6)

    fun cached(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(url: String, headers: Map<String, String>): ImageBitmap? {
        cache.get(url)?.let { return it }
        return gate.withPermit {
            cache.get(url) ?: withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = Net.bytes(url, headers)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }?.also { cache.put(url, it) }
        }
    }
}

@Composable
fun NetImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
) {
    val app = LocalApp.current
    var bmp by remember(url) { mutableStateOf(url?.let { Images.cached(it) }) }
    val shown = bmp != null
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(220), label = "img")
    LaunchedEffect(url) {
        if (bmp == null && url != null) bmp = Images.load(url, app.plex.mediaHeaders())
    }
    Box(modifier) {
        bmp?.let {
            Image(
                it, null,
                Modifier.matchParentSizeCompat().graphicsLayer { this.alpha = alpha },
                contentScale = contentScale,
                alignment = alignment,
            )
        }
    }
}

private fun Modifier.matchParentSizeCompat(): Modifier = this.fillMaxWidth().fillMaxHeight()

/** Request focus once the target is laid out (lazy lists compose a frame later). */
suspend fun androidx.compose.ui.focus.FocusRequester.focusSoon() {
    repeat(25) {
        kotlinx.coroutines.delay(40)
        if (runCatching { requestFocus() }.isSuccess) return
    }
}


/**
 * Soft red halo centred on the element (Android's coloured shadows fall below
 * the view instead of around it, which looked misplaced).
 */
fun Modifier.glow(shape: Shape, strength: Float, color: Color = G.red, radius: Dp = 16.dp): Modifier =
    if (strength <= 0f) this else drawBehind {
        val r = radius.toPx()
        val steps = 8
        for (i in steps downTo 1) {
            val d = r * i / steps
            val outline = shape.createOutline(Size(size.width + 2 * d, size.height + 2 * d), layoutDirection, this)
            translate(-d, -d) {
                drawOutline(outline, color.copy(alpha = 0.055f * strength * (steps - i + 1) / steps * 2f))
            }
        }
    }

@Composable
fun Logo(modifier: Modifier = Modifier) {
    Image(
        androidx.compose.ui.res.painterResource(com.mononymm.glasstv.R.drawable.logo),
        contentDescription = "Glass",
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

/** Centered frosted panel used by the sign-in and server screens. */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0x14FFFFFF))
            .border(1.dp, G.rim, RoundedCornerShape(24.dp))
            .padding(horizontal = 40.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}


/**
 * For lists that snap their focused row into place themselves: stops the list's own
 * "keep the focused item in view" scrolling, which otherwise lands after the snap and
 * leaves the row offset whenever a nested row also scrolled sideways.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val NoAutoScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}
