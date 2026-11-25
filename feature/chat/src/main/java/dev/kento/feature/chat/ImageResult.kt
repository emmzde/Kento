package dev.kento.feature.chat

import dev.kento.core.text.tr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kento.core.ui.*
import dev.kento.data.db.Attachment
import dev.kento.data.media.*
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageModelPicker(vm: ChatViewModel, dismiss: () -> Unit) {
    val all by vm.attachments.collectAsStateWithLifecycle()
    val id by vm.activeId.collectAsStateWithLifecycle()
    val loading by vm.imageLoading.collectAsStateWithLifecycle()
    val issue by vm.imageCatalogIssue.collectAsStateWithLifecycle()
    val endpoint by vm.images.collectAsStateWithLifecycle()
    val refs = all.count { it.chatId == id && it.messageId == null }
    val models = endpoint.catalog.filter { it.accepts(refs) }
    val selected = endpoint.option(refs)?.id
    // Twenty models with long descriptions: a few letters find one faster than scrolling.
    var query by rememberSaveable { mutableStateOf("") }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
    val shown = remember(models, words) {
        if (words.isEmpty()) models else models.filter { m ->
            val haystack = (m.name + " " + m.id + " " + m.description).lowercase()
            words.all { it in haystack }
        }
    }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = sheetColor) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (refs == 0) tr("Модель для генерации") else tr("Модель для редактирования"), Modifier.weight(1f), fontSize = 20.sp)
            ToolButton(Glyph.Refresh, tr("Обновить каталог изображений"), vm::refreshImages, enabled = !loading)
        }
        if (models.isNotEmpty()) SearchField(query, { query = it }, tr("Поиск модели"),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (issue != null || !loading && models.isEmpty()) Text(issue ?: if (!endpoint.configured) tr("Добавьте ключ OpenUncensored в настройках изображений")
            else if (refs == 0) tr("В каталоге нет моделей для генерации")
            else tr("В каталоге нет модели для такого числа референсов"), Modifier.padding(20.dp), color = Muted)
        if (models.isNotEmpty() && shown.isEmpty()) Text(tr("Совпадений нет"), Modifier.padding(horizontal = 22.dp, vertical = 14.dp), color = Muted)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).imePadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown, key = { it.id }) { model ->
                Row(Modifier.fillMaxWidth().clickable { vm.chooseImage(model.id, refs > 0); dismiss() }
                    .padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(model.name, color = Paper)
                        Text(listOfNotNull(model.id.takeIf { it != model.name }, model.priceText(refs))
                            .joinToString(" · "), color = Muted, fontSize = 13.sp)
                        if (model.description.isNotBlank()) Text(model.description, color = Muted.copy(alpha = .75f),
                            fontSize = 12.5.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (selected == model.id) Symbol(Glyph.Check, tr("Выбрана"), Accent)
                }
            }
        }
    }
}

/** The pending picture: a square where soft colour fields drift and a light band sweeps, under a shimmering caption. */
@Composable
internal fun ImageWaiting() {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); seconds++ } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Shimmer(tr("Создаю изображение"))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp))
            .semantics { contentDescription = tr("Kento создаёт изображение") }) {
            Painting(Modifier.matchParentSize())
            if (seconds >= 3) Text(tr("{0} с", seconds), Modifier.align(Alignment.BottomStart).padding(16.dp),
                color = Paper.copy(alpha = .55f), fontSize = 13.sp)
        }
    }
}

private val Inks = listOf(Accent, Color(0xFF9B7BEA), Color(0xFFEF9A82))

/**
 * Three blurred colour fields wander on slow Lissajous paths and breathe, while a diagonal highlight passes over them,
 * so the card reads as "being painted" rather than as a spinner.
 */
@Composable
private fun Painting(modifier: Modifier) {
    val clock = rememberInfiniteTransition(label = "painting")
    val t by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(12000, easing = LinearEasing)), label = "drift")
    val sweep by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, 300, easing = EaseInOutSine)), label = "sweep")
    val breath by clock.animateFloat(.88f, 1f, infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse), label = "breath")
    Box(modifier.background(Panel).drawBehind {
        val w = size.width
        val h = size.height
        val turn = (2 * PI * t).toFloat()
        Inks.forEachIndexed { i, ink ->
            val phase = i * 2.1f
            val center = Offset(w * (.5f + .3f * sin(turn * (i + 1) + phase)), h * (.5f + .28f * cos(turn * (2 - i % 2) + phase * 1.3f)))
            val radius = w * (.62f - i * .08f) * breath
            drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = .44f - i * .06f), Color.Transparent), center, radius), radius, center)
        }
        val x = (sweep * 2.2f - .6f) * w
        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .09f), Color.Transparent),
            start = Offset(x - w * .35f, 0f), end = Offset(x + w * .35f, h)))
    })
}

/**
 * A finished picture. When it has just been generated it develops like a print: a soft edge runs from top to bottom
 * while the image sharpens from a blur and settles from a slight zoom. Pictures loaded from history appear at once.
 */
@Composable
internal fun GeneratedImage(local: LocalMedia, row: Attachment, reveal: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var full by rememberSaveable(row.id) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, row.id, full) {
        value = withContext(Dispatchers.IO) { runCatching { Images.decode(local.file(row), if (full) 2048 else 960) }.getOrNull() }
    }
    val develop = remember(row.id) { Animatable(if (reveal) 0f else 1f) }
    LaunchedEffect(bitmap != null) {
        if (bitmap != null && develop.value < 1f) develop.animateTo(1f, tween(1800, easing = EaseOutCubic))
    }
    fun notify(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            try { local.saveToGallery(context, row); notify(tr("Сохранено в Pictures/Kento")) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notify(tr("Не удалось сохранить изображение в галерею")) }
            finally { saving = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) save() else notify(tr("Для сохранения в галерею нужен доступ к хранилищу"))
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel)) {
        val picture = bitmap
        if (picture == null) {
            if (reveal) Painting(Modifier.fillMaxWidth().aspectRatio(1f)) else Spacer(Modifier.fillMaxWidth().height(160.dp))
        } else {
            val p = develop.value
            if (p < 1f) Painting(Modifier.matchParentSize())
            Image(picture.asImageBitmap(), tr("Открыть изображение Kento"),
                Modifier.fillMaxWidth().aspectRatio(picture.width.toFloat() / picture.height).clickable { full = true }
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        val zoom = 1.04f - .04f * p
                        scaleX = zoom
                        scaleY = zoom
                    }
                    .drawWithContent {
                        drawContent()
                        if (p < 1f) {
                            val band = size.height * .35f
                            val edge = p * (size.height + band)
                            drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = edge - band, endY = edge),
                                blendMode = BlendMode.DstIn)
                        }
                    }
                    .blur(((1f - p) * 26f).dp))
        }
        AnimatedVisibility(picture != null && develop.value >= 1f, Modifier.align(Alignment.BottomEnd), enter = fadeIn(tween(300))) {
        Box(Modifier.padding(8.dp)) {
            Round(Glyph.Download, tr("Сохранить в галерею"), {
                if (Build.VERSION.SDK_INT <= 28 && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else save()
            }, enabled = !saving, background = Panel.copy(alpha = .9f))
        }
        }
    }
    if (full) Dialog(onDismissRequest = { full = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Night).clickable { full = false }, contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), tr("Изображение Kento"), Modifier.fillMaxSize()) }
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
                Round(Glyph.Close, tr("Закрыть изображение"), { full = false })
            }
        }
    }
}
