package hu.orajegyzet.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.orajegyzet.data.Db
import hu.orajegyzet.data.NoteStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Jópofa, ötletes folyamatjelző: pörgő ikon + váltakozó, játékos feliratok. */
@Composable
fun ProcessingIndicator(modifier: Modifier = Modifier) {
    val messages = listOf(
        "Hang felpörgetése…",
        "Whisper hegyezi a fülét…",
        "Magyar szavak vadászata…",
        "Mondatok rendezgetése…",
        "Gemma gondolkodóba esett…",
        "Lényeg kiemelése…",
        "Kulcsfogalmak gyűjtése…",
        "Mindjárt kész, csak egy korty kávé…"
    )
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(2500); idx = (idx + 1) % messages.size }
    }
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "angle"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.7f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), repeatMode = RepeatMode.Reverse), label = "pulse"
    )

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("✦", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.rotate(angle).graphicsLayer { alpha = pulse })
        Spacer(Modifier.width(8.dp))
        Text(messages[idx], fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onOpenNote: (Long) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val scope = rememberCoroutineScope()
    val since = LocalDate.now().minusDays(14).toString()
    val notes by db.noteDao().since(since).collectAsState(initial = emptyList())
    val selected = remember { mutableStateListOf<Long>() }
    var confirmDelete by remember { mutableStateOf(false) }

    val expandedTopics = remember { mutableStateMapOf<String, Boolean>() }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Kijelöltek törlése") },
            text = { Text("Biztosan törlöd a kijelölt ${selected.size} jegyzetet?") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selected.toList()
                    scope.launch {
                        ids.forEach { id -> db.noteDao().byId(id)?.let { db.noteDao().delete(it) } }
                    }
                    selected.clear()
                    confirmDelete = false
                }) { Text("Törlés") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Mégse") } }
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (selected.isEmpty()) "Előzmények (Témák & Projektek)" else "${selected.size} kijelölve") },
            navigationIcon = { BackButton(onBack) },
            actions = {
                if (selected.isNotEmpty()) {
                    TextButton(onClick = { selected.clear() }) { Text("Elvet") }
                    TextButton(onClick = { confirmDelete = true }) { Text("Törlés") }
                }
            }
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (notes.isEmpty()) {
                    item {
                        Text(
                            "Nincs jegyzet a korábbi előzményekben.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Témák / Órák / Projektek szerint csoportosítva
                val byTopic = notes.groupBy { it.subject }

                byTopic.forEach { (subject, topicNotes) ->
                    val isExpanded = expandedTopics[subject] ?: false

                    item(key = "header_$subject") {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { expandedTopics[subject] = !isExpanded }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            subject,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                        Text(
                                            "${topicNotes.size} jegyzet / megbeszélés",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        if (isExpanded) "▾ Összecsukás" else "▸ Kibontás (${topicNotes.size})",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                if (isExpanded) {
                                    Column(
                                        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        topicNotes.forEach { n ->
                                            val isSel = selected.contains(n.id)
                                            Card(
                                                onClick = { if (n.status == NoteStatus.DONE) onOpenNote(n.id) },
                                                colors = if (isSel) CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                                ) else CardDefaults.cardColors()
                                            ) {
                                                Row(
                                                    Modifier.fillMaxWidth().padding(10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Checkbox(
                                                        checked = isSel,
                                                        onCheckedChange = {
                                                            if (isSel) selected.remove(n.id) else selected.add(n.id)
                                                        }
                                                    )
                                                    Column(Modifier.weight(1f)) {
                                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                            Text("${n.dateIso} · ${fmt(n.startMin)}", fontWeight = FontWeight.Medium)
                                                            if (n.docCode.isNotBlank()) {
                                                                Text(n.docCode, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                            }
                                                        }
                                                        val preview = when (n.status) {
                                                            NoteStatus.DONE -> n.summary.take(80).ifBlank { "(leirat kész)" }
                                                            NoteStatus.PROCESSING, NoteStatus.QUEUED -> "feldolgozás alatt…"
                                                            NoteStatus.RECORDING -> "● felvétel"
                                                            NoteStatus.ERROR -> "hiba: ${n.error.take(60)}"
                                                        }
                                                        Text(
                                                            preview,
                                                            fontSize = 12.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
