package hu.orajegyzet.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import hu.orajegyzet.data.Note
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
    // pörgő ikon
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "angle"
    )
    // lüktető pötty
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
    val since = LocalDate.now().minusDays(4).toString() // ma + előző 4 nap = 5 nap
    val notes by db.noteDao().since(since).collectAsState(initial = emptyList())
    val selected = remember { mutableStateListOf<Long>() }
    var confirmDelete by remember { mutableStateOf(false) }

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
            title = { Text(if (selected.isEmpty()) "Korábbi jegyzetek (5 nap)" else "${selected.size} kijelölve") },
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
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (notes.isEmpty()) {
                    item { Text("Nincs jegyzet az elmúlt 5 napból.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                // dátum szerint csoportosítva
                val byDate = notes.groupBy { it.dateIso }
                byDate.forEach { (date, dayNotes) ->
                    item {
                        Text(date, fontWeight = FontWeight.Medium, fontSize = 16.sp,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    items(dayNotes) { n ->
                        val isSel = selected.contains(n.id)
                        Card(colors = if (isSel) CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer)
                            else CardDefaults.cardColors()) {
                            Row(Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isSel,
                                    onCheckedChange = {
                                        if (isSel) selected.remove(n.id) else selected.add(n.id)
                                    }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text("${n.subject} · ${fmt(n.startMin)}", fontWeight = FontWeight.Medium)
                                    val preview = when (n.status) {
                                        NoteStatus.DONE -> n.summary.take(90).ifBlank { "(leirat kész)" }
                                        NoteStatus.PROCESSING, NoteStatus.QUEUED -> "feldolgozás alatt…"
                                        NoteStatus.RECORDING -> "● felvétel"
                                        NoteStatus.ERROR -> "hiba: ${n.error.take(60)}"
                                    }
                                    Text(preview, fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (n.status == NoteStatus.DONE) {
                                    TextButton(onClick = { onOpenNote(n.id) }) { Text("Megnyit") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
