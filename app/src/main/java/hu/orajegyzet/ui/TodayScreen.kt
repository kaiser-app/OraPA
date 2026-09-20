package hu.orajegyzet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import hu.orajegyzet.data.*
import hu.orajegyzet.work.ProcessingWorker
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

fun fmt(min: Int) = "%d:%02d".format(min / 60, min % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenNote: (Long) -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onStartRecording: (Long?, String?) -> Unit,
    onStopRecording: () -> Unit
) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val today = LocalDate.now()
    val lessons by db.lessonDao().byDay(today.dayOfWeek.value)
        .collectAsState(initial = emptyList())
    val notes by db.noteDao().all()
        .map { list -> list.filter { it.dateIso == today.toString() } }
        .collectAsState(initial = emptyList())
    val recording = notes.any { it.status == NoteStatus.RECORDING }
    var showTopicDialog by remember { mutableStateOf(false) }
    var topic by remember { mutableStateOf("") }

    if (showTopicDialog) {
        AlertDialog(
            onDismissRequest = { showTopicDialog = false },
            title = { Text("Miről szól a felvétel?") },
            text = {
                OutlinedTextField(topic, { topic = it },
                    label = { Text("Téma (pl. értekezlet, előadás)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    showTopicDialog = false
                    onStartRecording(null, topic.ifBlank { "Jegyzet" })
                    topic = ""
                }) { Text("Felvétel indítása") }
            },
            dismissButton = { TextButton(onClick = { showTopicDialog = false }) { Text("Mégse") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fülelő · ma") },
                actions = {
                    TextButton(onClick = onOpenAssistant) { Text("Projekt AI") }
                    TextButton(onClick = onOpenHistory) { Text("Korábbi") }
                    TextButton(onClick = onOpenSchedule) { Text("Órarend") }
                    TextButton(onClick = onOpenSettings) { Text("Beállítások") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                if (recording) onStopRecording()
                else {
                    val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
                    val current = lessons.firstOrNull { nowMin in it.startMin until it.endMin }
                    if (current != null) onStartRecording(current.id, null)
                    else showTopicDialog = true
                }
            }) {
                Text(if (recording) "■ Felvétel leállítása" else "● Felvétel indítása")
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text("Mai órák", fontWeight = FontWeight.Medium, fontSize = 18.sp)
                    if (lessons.isEmpty()) Text("Nincs felvitt óra mára — add hozzá az órarendben, vagy fényképezd be.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(lessons) { l ->
                    val note = notes.firstOrNull { it.lessonId == l.id }
                    Card {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.subject, fontWeight = FontWeight.Medium)
                                Text("${fmt(l.startMin)}–${fmt(l.endMin)}" +
                                    (if (l.room.isNotBlank()) " · ${l.room}" else ""),
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            when (note?.status) {
                                NoteStatus.RECORDING -> Text("● felvétel", color = MaterialTheme.colorScheme.error)
                                NoteStatus.QUEUED -> ProcessingIndicator()
                                NoteStatus.PROCESSING -> ProcessingIndicator()
                                NoteStatus.DONE -> TextButton(onClick = { onOpenNote(note.id) }) { Text("Jegyzet") }
                                NoteStatus.ERROR -> Text("hiba", color = MaterialTheme.colorScheme.error)
                                null -> {}
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Mai jegyzetek", fontWeight = FontWeight.Medium, fontSize = 18.sp)
                }
                val processing = notes.filter {
                    it.status == NoteStatus.PROCESSING || it.status == NoteStatus.QUEUED
                }
                items(processing) { n ->
                    Card {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${n.subject} · ${fmt(n.startMin)}", fontWeight = FontWeight.Medium)
                                ProcessingIndicator(Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
                items(notes.filter { it.status == NoteStatus.DONE }) { n ->
                    Card(onClick = { onOpenNote(n.id) }) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("${n.subject} · ${fmt(n.startMin)}", fontWeight = FontWeight.Medium)
                            Text(n.summary.take(140) + if (n.summary.length > 140) "…" else "",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(noteId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val note by Db.get(ctx).noteDao().byIdFlow(noteId).collectAsState(initial = null)
    val n = note ?: return

    var editing by remember { mutableStateOf(false) }
    var eSummary by remember { mutableStateOf("") }
    var eStructured by remember { mutableStateOf("") }
    var eTranscript by remember { mutableStateOf("") }

    // Magyar felolvasó (beépített Android TTS, offline)
    val tts = remember {
        val holder = arrayOfNulls<TextToSpeech>(1)
        holder[0] = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) holder[0]?.setLanguage(Locale("hu", "HU"))
        }
        holder[0]!!
    }
    var speaking by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { runCatching { tts.stop(); tts.shutdown() } } }

    fun fullText(): String = buildString {
        append(n.subject).append(" · ").append(n.dateIso).append("\n\n")
        if (n.summary.isNotBlank()) append("ÖSSZEFOGLALÓ\n").append(n.summary).append("\n\n")
        if (n.structured.isNotBlank()) append("JEGYZET\n").append(n.structured).append("\n\n")
        if (n.keywords.isNotBlank()) append("KULCSFOGALMAK\n").append(n.keywords).append("\n\n")
        if (n.transcript.isNotBlank()) append("LEIRAT\n").append(n.transcript)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(n.subject) }, navigationIcon = { BackButton(onBack) })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("${n.dateIso} · ${fmt(n.startMin)}–${fmt(n.endMin)}" +
                    (n.processedOnline?.let { if (it) " · online" else " · offline" } ?: ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }

            if (!editing) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("jegyzet", fullText()))
                            }) { Text("Másolás") }
                            TextButton(onClick = {
                                val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, fullText())
                                ctx.startActivity(Intent.createChooser(i, "Jegyzet megosztása"))
                            }) { Text("Megosztás") }
                            TextButton(onClick = {
                                eSummary = n.summary; eStructured = n.structured; eTranscript = n.transcript
                                editing = true
                            }) { Text("Szerkesztés") }
                            TextButton(onClick = {
                                if (speaking) { runCatching { tts.stop() }; speaking = false }
                                else {
                                    val text = if (n.summary.isNotBlank() && !n.summary.startsWith("("))
                                        n.summary else n.transcript
                                    if (text.isNotBlank()) {
                                        runCatching { tts.setLanguage(Locale("hu", "HU")) }
                                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "note")
                                        speaking = true
                                    }
                                }
                            }) { Text(if (speaking) "Leállítás" else "Felolvasás") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                scope.launch { Db.get(ctx).noteDao().update(n.copy(status = NoteStatus.PROCESSING)) }
                                ProcessingWorker.resummarize(ctx, n.id)
                            }) { Text("Újra-összefoglalás") }
                            val audioOk = n.audioPath?.let { File(it).exists() } == true
                            if (audioOk) {
                                TextButton(onClick = {
                                    val f = File(n.audioPath!!)
                                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                                    val i = Intent(Intent.ACTION_SEND).setType("audio/mp4")
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    ctx.startActivity(Intent.createChooser(i, "Hang mentése / megosztása"))
                                }) { Text("Hang mentése") }
                            }
                        }
                        if (n.audioPath != null && File(n.audioPath!!).exists()) {
                            Text("A nyers hang kb. 30 percig érhető el, utána törlődik.",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (editing) {
                item {
                    OutlinedTextField(value = eSummary, onValueChange = { eSummary = it },
                        label = { Text("Összefoglaló") }, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = eStructured, onValueChange = { eStructured = it },
                        label = { Text("Jegyzet (markdown)") }, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = eTranscript, onValueChange = { eTranscript = it },
                        label = { Text("Leirat") }, modifier = Modifier.fillMaxWidth())
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                Db.get(ctx).noteDao().update(
                                    n.copy(summary = eSummary, structured = eStructured, transcript = eTranscript))
                                editing = false
                            }
                        }) { Text("Mentés") }
                        TextButton(onClick = { editing = false }) { Text("Mégse") }
                    }
                }
            } else {
                if (n.status == NoteStatus.PROCESSING || n.status == NoteStatus.QUEUED) {
                    item { ProcessingIndicator() }
                }
                if (n.summary.isNotBlank()) {
                    item { Text("Összefoglaló", fontWeight = FontWeight.Medium, fontSize = 18.sp) }
                    item { MarkdownText(n.summary) }
                }
                if (n.structured.isNotBlank()) {
                    item { Text("Jegyzet", fontWeight = FontWeight.Medium, fontSize = 18.sp) }
                    item { MarkdownText(n.structured) }
                }
                if (n.keywords.isNotBlank()) {
                    item { Text("Kulcsfogalmak", fontWeight = FontWeight.Medium, fontSize = 18.sp) }
                    item { Text(n.keywords) }
                }
                if (n.transcript.isNotBlank()) {
                    item { Text("Teljes leirat", fontWeight = FontWeight.Medium, fontSize = 18.sp) }
                    item { Text(n.transcript, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (n.error.isNotBlank()) {
                    item { Text("Hiba: ${n.error}", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
