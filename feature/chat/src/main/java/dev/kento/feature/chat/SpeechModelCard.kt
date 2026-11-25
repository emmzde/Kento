package dev.kento.feature.chat

import dev.kento.core.text.tr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kento.core.ui.*
import dev.kento.data.media.SpeechModel

/**
 * Offline dictation needs the Whisper model, which is no longer bundled with the APK. The card offers the one-time
 * download when dictation asks for it and shows progress until the model is ready.
 */
@Composable
internal fun SpeechModelCard(requested: Boolean, dismiss: () -> Unit) {
    val context = LocalContext.current
    val state by SpeechModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(requested) { if (requested) SpeechModel.refresh(context) }
    val visible = requested || state is SpeechModel.State.Loading
    AnimatedVisibility(visible, enter = fadeIn(tween(220)) + expandVertically(), exit = fadeOut(tween(160)) + shrinkVertically()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp))
            .background(SurfaceHigh).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val current = state
            val text = when (current) {
                is SpeechModel.State.Loading -> tr("Скачиваю модель распознавания речи · {0}%", (current.fraction * 100).toInt())
                is SpeechModel.State.Failed -> current.message
                SpeechModel.State.Ready -> tr("Модель речи готова. Нажмите микрофон ещё раз")
                SpeechModel.State.Missing -> tr("На этом устройстве нет встроенного офлайн-распознавания. ") +
                    tr("Kento может скачать модель Whisper ({0} МБ) один раз, дальше диктовка работает без сети", SpeechModel.SIZE_MB)
            }
            Text(text, color = Paper, fontSize = 14.5.sp, lineHeight = 20.sp)
            if (current is SpeechModel.State.Loading)
                LinearProgressIndicator({ current.fraction }, Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                    color = Accent, trackColor = Raised, drawStopIndicator = {})
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                when (current) {
                    is SpeechModel.State.Loading -> Chip(tr("Отменить")) { SpeechModel.cancel() }
                    SpeechModel.State.Ready -> Chip(tr("Понятно"), onClick = dismiss)
                    else -> {
                        Chip(tr("Не сейчас"), onClick = dismiss)
                        Chip(if (current is SpeechModel.State.Failed) tr("Повторить") else tr("Скачать"), primary = true) { SpeechModel.download(context) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(if (primary) Paper else Raised).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(label, color = if (primary) Panel else Paper, fontSize = 14.sp)
    }
}
