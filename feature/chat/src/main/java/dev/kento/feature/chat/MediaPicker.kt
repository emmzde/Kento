package dev.kento.feature.chat

import dev.kento.core.text.tr

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.PixelCopy
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.kento.core.ui.*
import dev.kento.data.db.Attachment
import dev.kento.data.media.*
import dev.kento.data.net.ProviderIssue
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

internal suspend fun captureWindow(activity: Activity, target: File) {
    val view = activity.window.decorView
    if (view.width < 1 || view.height < 1) throw ProviderIssue(tr("Экран ещё не готов к снимку"))
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    try {
        val status = kotlin.coroutines.suspendCoroutine { cont ->
            PixelCopy.request(activity.window, bitmap, { cont.resume(it) }, Handler(Looper.getMainLooper()))
        }
        if (status != PixelCopy.SUCCESS) throw ProviderIssue(tr("Не удалось снять окно Kento"))
        withContext(Dispatchers.IO) { target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    } finally { bitmap.recycle() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaPicker(vm: ChatViewModel, enabled: Boolean) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var chat by rememberSaveable { mutableStateOf("") }
    var capture by rememberSaveable { mutableStateOf("") }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { list ->
        if (list.isNotEmpty()) vm.changeMedia { vm.media.import(chat, list) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) { list ->
        if (list.isNotEmpty()) vm.changeMedia { vm.media.import(chat, list) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val name = capture
        capture = ""
        if (name.isNotEmpty()) {
            if (ok) vm.changeMedia { vm.media.acceptCapture(chat, name) } else vm.media.captureFile(name).delete()
        }
    }
    val hasCamera = remember { Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null }
    val scope = rememberCoroutineScope()
    Box {
        Round(Glyph.Clip, tr("Прикрепить"), { menu = true }, enabled = enabled)
        if (menu) {
            val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            var closing by remember { mutableStateOf(false) }
            fun select(action: () -> Unit) {
                if (closing) return
                closing = true
                scope.launch {
                    sheet.hide()
                    menu = false
                    chat = vm.activeId.value
                    try { action() } catch (_: ActivityNotFoundException) { vm.problem.value = tr("На устройстве нет приложения для этого действия") }
                    catch (_: SecurityException) { vm.problem.value = tr("Android запретил доступ") }
                }
            }
            ModalBottomSheet(onDismissRequest = { menu = false }, sheetState = sheet, containerColor = Panel,
                scrimColor = Color.Black.copy(alpha = .55f), shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 16.dp).size(40.dp, 4.dp).clip(CircleShape).background(Line)) }) {
                // Tiles scroll sideways like a shelf; a partly visible last tile hints that there is more.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    var order = 0
                    Appear(order++) { MediaTile(Glyph.Photo, tr("Фото")) { select { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } } }
                    if (hasCamera) Appear(order++) { MediaTile(Glyph.Camera, tr("Открыть камеру")) { select {
                        val target = vm.media.cameraTarget()
                        capture = target.name
                        camera.launch(FileProvider.getUriForFile(context, context.packageName + ".capture", target))
                    } } }
                    Appear(order++) { MediaTile(Glyph.Clip, tr("Файлы")) { select { files.launch(arrayOf("image/*","application/pdf","audio/*")) } } }
                    Appear(order) { MediaTile(Glyph.Screen, tr("Снимок экрана")) { select {
                        vm.changeMedia {
                            val target = File(vm.media.cameraTarget().path.removeSuffix(".jpg") + ".png")
                            try {
                                captureWindow(context.activity() ?: throw ProviderIssue(tr("Окно Kento недоступно")), target)
                                vm.media.acceptCapture(chat, target.name)
                            } finally { target.delete() }
                        }
                    } } }
                }
                Spacer(Modifier.height(14.dp))
                Appear(4) {
                    SheetAction(Glyph.Art, tr("Создать изображение"), tr("Генерируйте и редактируйте изображения по описанию или по своему фото")) {
                        select { vm.setImageMode(true) }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

/** Fades, lifts and slightly grows its content in after a short per-item delay, so the sheet unfolds as a cascade. */
@Composable
private fun Appear(index: Int, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(70L + index * 45L)
        progress.animateTo(1f, spring(dampingRatio = .82f, stiffness = 360f))
    }
    Box(Modifier.graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 22.dp.toPx()
        val scale = .9f + .1f * p
        scaleX = scale
        scaleY = scale
    }) { content() }
}

@Composable
private fun MediaTile(icon: Glyph, label: String, click: () -> Unit) {
    Column(Modifier.size(width = 108.dp, height = 104.dp).clip(RoundedCornerShape(30.dp)).background(SurfaceHigh)
        .clickable(role = Role.Button, onClick = click).padding(horizontal = 6.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        Symbol(icon, size = 25.dp)
        Text(label, color = Paper, fontSize = 15.sp, lineHeight = 18.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun SheetAction(icon: Glyph, title: String, subtitle: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = click).padding(horizontal = 26.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.padding(top = 2.dp)) { Symbol(icon, color = Paper, size = 25.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Paper, fontSize = 17.sp, lineHeight = 22.sp)
            Text(subtitle, color = Muted, fontSize = 14.5.sp, lineHeight = 20.sp)
        }
    }
}

private enum class Kind(val icon: Glyph, val color: Color, val title: String) {
    Image(Glyph.Photo, Paper, tr("Изображение")),
    Audio(Glyph.Wave, Color(0xFFB9A2F2), tr("Аудио")),
    Pdf(Glyph.File, Color(0xFFEF9A82), tr("PDF-документ")),
    Other(Glyph.File, Accent, tr("Файл"))
}

private fun Attachment.kind() = when {
    mime.startsWith("image/") -> Kind.Image
    mime.startsWith("audio/") -> Kind.Audio
    mime == "application/pdf" -> Kind.Pdf
    else -> Kind.Other
}

private val TileShape = RoundedCornerShape(20.dp)

/** Draft attachments inside the composer: square-ish tiles with a remove badge, nothing but the picture or the name. */
@Composable
internal fun DraftTiles(local: LocalMedia, rows: List<Attachment>, importing: Boolean, remove: ((Attachment) -> Unit)?,
    edit: ((Attachment, ImageEdit) -> Unit)?) {
    var selected by remember { mutableStateOf<Attachment?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row -> key(row.id) { Tile(local, row, 96.dp, 112.dp, remove, { selected = row }) } }
        if (importing) Box(Modifier.size(96.dp, 112.dp).clip(TileShape).background(Raised), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Muted, strokeWidth = 2.dp)
        }
    }
    selected?.let { row -> AttachmentDialog(local, row, edit) { selected = null } }
}

/** Attachments of a sent message, aligned with the bubble they belong to. */
@Composable
internal fun SentAttachments(local: LocalMedia, rows: List<Attachment>, mine: Boolean) {
    if (rows.isEmpty()) return
    var selected by remember { mutableStateOf<Attachment?>(null) }
    Box(Modifier.fillMaxWidth().padding(start = if (mine) 48.dp else 0.dp, bottom = 6.dp),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { row ->
                key(row.id) {
                    val single = rows.size == 1 && row.kind() == Kind.Image
                    Tile(local, row, if (single) 196.dp else 124.dp, if (single) 196.dp else 140.dp, null, { selected = row })
                }
            }
        }
    }
    selected?.let { row -> AttachmentDialog(local, row, null) { selected = null } }
}

@Composable
private fun Tile(local: LocalMedia, row: Attachment, width: Dp, height: Dp, remove: ((Attachment) -> Unit)?, open: () -> Unit) {
    val kind = row.kind()
    Box(Modifier.size(width, height)) {
        Box(Modifier.matchParentSize().clip(TileShape)
            .then(if (kind == Kind.Image) Modifier.background(Raised) else Modifier.background(Raised.copy(alpha = .55f)).border(1.dp, Line, TileShape))
            .clickable(role = Role.Button, onClick = open).semantics(mergeDescendants = true) { contentDescription = row.name }) {
            if (kind == Kind.Image) Thumbnail(local.file(row), 360, Modifier.matchParentSize())
            else Column(Modifier.matchParentSize().padding(start = 14.dp, end = 12.dp, top = 16.dp, bottom = 14.dp)) {
                Symbol(kind.icon, color = kind.color, size = 24.dp)
                Spacer(Modifier.weight(1f))
                Text(row.name.substringBeforeLast('.').ifBlank { row.name }, color = Paper, fontSize = 14.5.sp, lineHeight = 19.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (remove != null) Box(Modifier.align(Alignment.TopEnd).size(36.dp).clip(CircleShape)
            .clickable(role = Role.Button) { remove(row) }.semantics { contentDescription = tr("Убрать {0}", row.name) },
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = .62f)), contentAlignment = Alignment.Center) {
                Symbol(Glyph.Close, color = Paper, size = 13.dp)
            }
        }
    }
}

/** Details on demand: the full picture, its EXIF facts and quick edits; for other files a short description. */
@Composable
private fun AttachmentDialog(local: LocalMedia, row: Attachment, edit: ((Attachment, ImageEdit) -> Unit)?, dismiss: () -> Unit) {
    val ctx = LocalContext.current
    val kind = row.kind()
    val image = kind == Kind.Image
    val metadata by produceState("", row.id) {
        if (image) value = withContext(Dispatchers.IO) { Images.metadata(local.file(row)) }
    }
    AlertDialog(onDismissRequest = dismiss, containerColor = Panel, shape = RoundedCornerShape(28.dp),
        title = { Text(row.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (image) Preview(local.file(row))
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol(kind.icon, color = kind.color, size = 22.dp)
                    Text(kind.title + " · " + Formatter.formatShortFileSize(ctx, row.bytes), color = Muted, fontSize = 14.sp)
                }
                if (image && edit != null) {
                    var actions by remember { mutableStateOf(false) }
                    Box {
                        TextButton({ actions = true }) { Text(tr("Обработать фото")) }
                        DropdownMenu(actions, { actions = false }, containerColor = SurfaceHigh, shape = RoundedCornerShape(16.dp)) {
                            for ((label, op) in listOf(tr("Повернуть на 90°") to ImageEdit("rotate"),
                                tr("Обрезать края на 10%") to ImageEdit("crop", .1, .1, .8, .8),
                                tr("Уменьшить до 1280 px") to ImageEdit("resize"), tr("Сжать JPEG до 75%") to ImageEdit("compress"))) {
                                DropdownMenuItem({ Text(label) }, { actions = false; dismiss(); edit(row, op) })
                            }
                        }
                    }
                }
                if (metadata.isNotBlank()) Text(metadata, color = Muted, fontSize = 13.5.sp, lineHeight = 19.sp)
            }
        }, confirmButton = { TextButton(dismiss) { Text(tr("Закрыть")) } })
}

/**
 * The photo in the details dialog: the frame takes the picture's own proportions (at most 320 dp tall) and sits in
 * the middle, so rounded corners belong to the photo and a tall shot is not shown inside an empty wide box.
 */
@Composable
private fun Preview(file: File) {
    val bitmap by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { Images.decode(file, 1024) }.getOrNull() }
    }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val picture = bitmap
        if (picture == null) Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)).background(Raised))
        else {
            val ratio = picture.width.toFloat() / picture.height
            val width = minOf(maxWidth, 320.dp * ratio)
            Image(picture.asImageBitmap(), null, Modifier.size(width, width / ratio).clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun Thumbnail(file: File, edge: Int, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, file, edge) {
        value = withContext(Dispatchers.IO) { runCatching { Images.decode(file, edge) }.getOrNull() }
    }
    bitmap?.let { Image(it.asImageBitmap(), null, modifier, contentScale = if (edge <= 360) ContentScale.Crop else ContentScale.Fit) }
}
