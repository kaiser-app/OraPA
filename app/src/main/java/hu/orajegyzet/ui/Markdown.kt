package hu.orajegyzet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Rendes, ikonos vissza-gomb a címsorokhoz. */
@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            painter = painterResource(hu.orajegyzet.R.drawable.ic_back),
            contentDescription = "Vissza"
        )
    }
}

/**
 * Könnyű markdown-megjelenítő: címsorok (#, ##, ###), félkövér (**szöveg**),
 * felsorolás (* vagy -). Nem teljes markdown, de a jegyzetekhez bőven elég.
 */
@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        md.lines().forEach { raw ->
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            when {
                line.isBlank() -> Spacer(Modifier.height(6.dp))
                trimmed.startsWith("### ") -> Text(
                    parseInline(trimmed.removePrefix("### ")),
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
                trimmed.startsWith("## ") -> Text(
                    parseInline(trimmed.removePrefix("## ")),
                    fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
                trimmed.startsWith("# ") -> Text(
                    parseInline(trimmed.removePrefix("# ")),
                    fontWeight = FontWeight.Bold, fontSize = 19.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                trimmed.startsWith("* ") || trimmed.startsWith("- ") -> {
                    val indent = line.length - trimmed.length
                    val content = trimmed.removePrefix("* ").removePrefix("- ")
                    Row(Modifier.padding(start = (8 + indent * 4).dp, top = 1.dp, bottom = 1.dp)) {
                        Text("•  ")
                        Text(parseInline(content))
                    }
                }
                else -> Text(parseInline(line), modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

private fun parseInline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        val start = s.indexOf("**", i)
        if (start < 0) { append(s.substring(i)); break }
        append(s.substring(i, start))
        val end = s.indexOf("**", start + 2)
        if (end < 0) { append(s.substring(start)); break }
        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        append(s.substring(start + 2, end))
        pop()
        i = end + 2
    }
}
