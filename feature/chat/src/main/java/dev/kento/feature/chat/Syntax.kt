package dev.kento.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import dev.kento.core.ui.*

private val tokens = Regex("""(//[^\n]*|\#[^\n]*)|("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|(\b(?:fun|val|var|class|data|return|if|else|when|import|from|def|for|while|in|const|let|function|async|await|public|private|int|void|new|true|false|null|None|SELECT|FROM|WHERE)\b)|(\b\d+(?:\.\d+)?\b)""")
internal fun highlight(code: String) = buildAnnotatedString {
    append(code)
    for (token in tokens.findAll(code)) {
        val color = when {
            token.groups[1] != null -> Muted
            token.groups[2] != null -> Color(0xFFCAD9B3)
            token.groups[3] != null -> Color(0xFFCEB6EA)
            else -> Color(0xFFE6BD90)
        }
        addStyle(SpanStyle(color = color), token.range.first, token.range.last + 1)
    }
}
