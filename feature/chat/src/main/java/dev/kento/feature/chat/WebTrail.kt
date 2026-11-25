package dev.kento.feature.chat

import dev.kento.core.text.tr

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kento.core.ui.*
import dev.kento.data.web.Favicons
import dev.kento.data.web.Trail
import dev.kento.data.web.Visit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded icons by host; a miss stays null so the letter shows at once next time. */
private val iconCache = android.util.LruCache<String, ImageBitmap>(96)

/** A site's icon in a small circle, or its first letter on a colour of its own while there is none. */
@Composable
internal fun SiteIcon(host: String, size: Dp = 20.dp, ring: Color? = null) {
    val icon by produceState(iconCache.get(host), host) {
        if (value == null) value = withContext(Dispatchers.IO) {
            Favicons.bytes(host)?.let { bytes ->
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
            }?.also { iconCache.put(host, it) }
        }
    }
    val shape = CircleShape
    Box(Modifier.size(size).then(if (ring != null) Modifier.border(1.5.dp, ring, shape) else Modifier).padding(if (ring != null) 1.5.dp else 0.dp)
        .clip(shape).background(if (icon != null) Color.White else letterColor(host)), contentAlignment = Alignment.Center) {
        val bitmap = icon
        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize().padding(size / 8), contentScale = ContentScale.Fit)
        else Text(host.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty(), color = Color.White,
            fontSize = (size.value * .5f).sp, fontWeight = FontWeight.Medium)
    }
}

private fun letterColor(host: String): Color {
    val hues = listOf(0xFF5B8DEF, 0xFF9B7BEA, 0xFF4FBF8A, 0xFFE7A44A, 0xFFE57AA6, 0xFF4BB4C8, 0xFFE98C73)
    return Color(hues[(host.hashCode() and Int.MAX_VALUE) % hues.size])
}

/** Up to three icons, each tucked under the previous one, as in a contact stack. */
@Composable
internal fun IconStack(hosts: List<String>, size: Dp = 20.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(-(size / 3))) {
        hosts.take(3).forEach { SiteIcon(it, size, ring = Color.Black) }
    }
}

private fun sitesLabel(n: Int) = if (n == 1) tr("Поиск на одном сайте") else tr("Поиск на {0} сайтах", n)

/**
 * The research line inside a reply. While Kento searches, the text shimmers: first the query itself, then "searching
 * N sites" with the icons of the sites so far, and "skip" lets the model answer with what it has. Afterwards the
 * line stays, quiet, and opens the list of every page.
 */
@Composable
internal fun SearchLine(trail: Trail, searching: Boolean, skip: (() -> Unit)?, open: () -> Unit) {
    val sites = trail.sites
    val label = if (sites.isEmpty() && searching) tr("Поиск по запросу «{0}»", trail.queries.lastOrNull().orEmpty())
        else sitesLabel(sites.size)
    var skipped by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(CircleShape).clickable(enabled = trail.visits.isNotEmpty(), role = Role.Button, onClick = open)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.widthIn(min = 22.dp), contentAlignment = Alignment.CenterStart) {
            if (sites.isEmpty()) Symbol(Glyph.Globe, color = Muted, size = 20.dp) else IconStack(sites.takeLast(3).reversed())
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            AnimatedContent(label, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(140)) }, label = "search") { text ->
                if (searching) Glimmer(text) else Text(text, color = Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (searching && skip != null && !skipped) Text(tr("Пропустить"), Modifier.clip(CircleShape)
            .clickable(role = Role.Button) { skipped = true; skip() }.padding(horizontal = 10.dp, vertical = 6.dp),
            color = Paper, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        else if (!searching && trail.visits.isNotEmpty()) Symbol(Glyph.Right, color = Muted, size = 13.dp)
    }
}

/** Text with a soft light passing through it, slower and wider than the thinking shimmer; up to two lines. */
@Composable
private fun Glimmer(text: String) {
    val sweep = rememberInfiniteTransition(label = "glimmer")
    val x by sweep.animateFloat(-1f, 2f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "x")
    val band = 220f
    val brush = Brush.linearGradient(listOf(Muted.copy(alpha = .5f), Paper, Muted.copy(alpha = .5f)),
        start = androidx.compose.ui.geometry.Offset(x * band * 1.6f - band, 0f),
        end = androidx.compose.ui.geometry.Offset(x * band * 1.6f, 0f))
    Text(text, style = TextStyle(brush = brush, fontSize = 15.sp, lineHeight = 21.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** "Sources" at the end of the actions under a reply: a globe, the icons of the pages read and the word. */
@Composable
internal fun SourcesButton(trail: Trail, open: () -> Unit) {
    val hosts = (trail.read.map { it.host } + trail.sites).distinct().take(2)
    Row(Modifier.height(36.dp).clip(CircleShape).clickable(role = Role.Button, onClick = open).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy((-6).dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
                Symbol(Glyph.Globe, color = Paper.copy(alpha = .85f), size = 19.dp)
            }
            hosts.forEach { SiteIcon(it, 22.dp, ring = Color.Black) }
        }
        Text(tr("Источники"), color = Paper.copy(alpha = .85f), fontSize = 14.5.sp)
    }
}

/**
 * Every page of the research: those Kento opened first, then search results the model only saw, then the queries.
 * A tap opens the page in the browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourcesSheet(trail: Trail, dismiss: () -> Unit) {
    val context = LocalContext.current
    val read = trail.read
    val seen = trail.visits.filterNot { it.read }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = sheetColor, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(tr("Источники"), color = Paper, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(listOfNotNull(tr("Сайтов: {0}", trail.sites.size), tr("прочитано страниц: {0}", read.size).takeIf { read.isNotEmpty() },
                tr("{0} с", trail.seconds).takeIf { trail.seconds > 0 }).joinToString(" · "),
                Modifier.padding(top = 2.dp, bottom = 8.dp), color = Muted, fontSize = 13.5.sp)
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            fun open(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }
            if (read.isNotEmpty()) {
                item { SectionLabel(tr("Прочитано")) }
                items(read, key = { "r" + it.url }) { VisitRow(it, true) { open(it.url) } }
            }
            if (seen.isNotEmpty()) {
                item { SectionLabel(tr("Найдено в поиске")) }
                items(seen, key = { "s" + it.url }) { VisitRow(it, false) { open(it.url) } }
            }
            if (trail.queries.isNotEmpty()) {
                item { SectionLabel(tr("Запросы")) }
                items(trail.queries) { query ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Symbol(Glyph.Search, color = Muted, size = 16.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(query, color = Paper.copy(alpha = .85f), fontSize = 14.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun VisitRow(visit: Visit, read: Boolean, open: () -> Unit) {
    val path = remember(visit.url) {
        android.net.Uri.parse(visit.url).let { uri -> visit.host + (uri.path?.takeIf { it.length > 1 }?.trimEnd('/') ?: "") }
    }
    Row(Modifier.fillMaxWidth().clip(dev.kento.core.ui.GroupShape).clickable(role = Role.Button, onClick = open)
        .padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        SiteIcon(visit.host, 28.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(visit.title.ifBlank { visit.host }, color = if (read) Paper else Paper.copy(alpha = .75f), fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(path, color = Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
