package hu.orajegyzet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import hu.orajegyzet.rec.RecordingService
import hu.orajegyzet.work.ProcessingWorker
import kotlinx.coroutines.Dispatchers
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

    val lessons by db.lessonDao().byDay(today.dayOfWeek.value).collectAsState(initial = emptyList())
    val projects by db.projectDao().all().collectAsState(initial = emptyList())
    val notes by db.noteDao().all()
        .map { list -> list.filter { it.dateIso == today.toString() } }
        .collectAsState(initial = emptyList())

    val recording = notes.any { it.status == NoteStatus.RECORDING }

    var showStartDialog by remember { mutableStateOf(false) }
    var selectedContextType by remember { mutableStateOf("project") } // "lesson", "project", "custom"
    var selectedLessonId by remember { mutableStateOf<Long?>(null) }
    var selectedProjectId by remember { mutableStateOf("prj-01") }
    var selectedDocType by remember { mutableStateOf("JEG") } // JEG, EML, JZK, VEZ, STA
    var customTopic by remember { mutableStateOf("") }

    if (showStartDialog) {
        val activeProj = projects.firstOrNull { it.id == selectedProjectId }
        val previewCode = NoteCodeGenerator.generate(
            projectCode = if (selectedContextType == "project") activeProj?.code else null,
            subject = when (selectedContextType) {
                "lesson" -> lessons.firstOrNull { it.id == selectedLessonId }?.subject ?: "Óra"
                "project" -> activeProj?.name ?: "Projekt"
                else -> customTopic.ifBlank { "Téma" }
            },
            docType = selectedDocType,
            date = LocalDate.now()
        )

        AlertDialog(
            onDismissRequest = { showStartDialog = false },
            title = { Text("Új Felvétel & Jegyzőkönyv Indítása") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1. Kontextus / Téma választása:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            FilterChip(
                                selected = selectedContextType == "project",
                                onClick = { selectedContextType = "project" },
                                label = { Text("Projekt", maxLines = 1) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedContextType == "lesson",
                                onClick = { selectedContextType = "lesson" },
                                label = { Text("Órarendi óra", maxLines = 1) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedContextType == "custom",
                                onClick = { selectedContextType = "custom" },
                                label = { Text("Egyedi téma", maxLines = 1) }
                            )
                        }
                    }

                    if (selectedContextType == "project" && projects.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(projects) { p ->
                                FilterChip(
                                    selected = selectedProjectId == p.id,
                                    onClick = { selectedProjectId = p.id },
                                    label = { Text(p.code, maxLines = 1) }
                                )
                            }
                        }
                    } else if (selectedContextType == "lesson" && lessons.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(lessons) { l ->
                                FilterChip(
                                    selected = selectedLessonId == l.id,
                                    onClick = { selectedLessonId = l.id },
                                    label = { Text(l.subject, maxLines = 1) }
                                )
                            }
                        }
                    } else if (selectedContextType == "custom") {
                        OutlinedTextField(
                            value = customTopic,
                            onValueChange = { customTopic = it },
                            label = { Text("Téma / Megbeszélés neve") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Text("2. Dokumentum Típusa:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { FilterChip(selected = selectedDocType == "JEG", onClick = { selectedDocType = "JEG" }, label = { Text("📝 JEG", maxLines = 1) }) }
                        item { FilterChip(selected = selectedDocType == "EML", onClick = { selectedDocType = "EML" }, label = { Text("📌 EML", maxLines = 1) }) }
                        item { FilterChip(selected = selectedDocType == "JZK", onClick = { selectedDocType = "JZK" }, label = { Text("📋 JZK", maxLines = 1) }) }
                        item { FilterChip(selected = selectedDocType == "VEZ", onClick = { selectedDocType = "VEZ" }, label = { Text("👑 VEZ", maxLines = 1) }) }
                        item { FilterChip(selected = selectedDocType == "STA", onClick = { selectedDocType = "STA" }, label = { Text("📊 STA", maxLines = 1) }) }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("Generált Dok. Kód:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(previewCode, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showStartDialog = false
                    val title = when (selectedContextType) {
                        "lesson" -> lessons.firstOrNull { it.id == selectedLessonId }?.subject ?: "Óra"
                        "project" -> activeProj?.name ?: "Projekt megbeszélés"
                        else -> customTopic.ifBlank { "Megbeszélés" }
                    }
                    val prjId = if (selectedContextType == "project") selectedProjectId else null
                    val prjCode = if (selectedContextType == "project") activeProj?.code else null

                    RecordingService.start(
                        ctx = ctx,
                        lessonId = if (selectedContextType == "lesson") selectedLessonId else null,
                        title = title,
                        projectId = prjId,
                        docType = selectedDocType,
                        projectCode = prjCode
                    )
                }) { Text("● Felvétel Indítása") }
            },
            dismissButton = { TextButton(onClick = { showStartDialog = false }) { Text("Mégse") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "FÜ",
                        fontWeight = FontWeight.Black,
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
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
                else showStartDialog = true
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
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Mai órák & Projektek", fontWeight = FontWeight.Medium, fontSize = 18.sp)
                        if (projects.isNotEmpty()) {
                            TextButton(onClick = onOpenAssistant) {
                                Text("📋 ${projects.firstOrNull()?.code ?: "Projektek"}")
                            }
                        }
                    }
                    if (lessons.isEmpty() && projects.isEmpty()) {
                        Text(
                            "Nincs felvitt óra vagy projekt mára.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                    Text("Mai jegyzetek & Dok. Kódok", fontWeight = FontWeight.Medium, fontSize = 18.sp)
                }

                val processing = notes.filter {
                    it.status == NoteStatus.PROCESSING || it.status == NoteStatus.QUEUED
                }
                items(processing) { n ->
                    Card {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${n.subject} · ${n.docCode.ifBlank { fmt(n.startMin) }}", fontWeight = FontWeight.Medium)
                                ProcessingIndicator(Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }

                items(notes.filter { it.status == NoteStatus.DONE }) { n ->
                    Card(onClick = { onOpenNote(n.id) }) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(n.subject, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                if (n.docCode.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        n.docCode,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
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

    var alertMessage by remember { mutableStateOf<String?>(null) }

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
        if (n.docCode.isNotBlank()) append("DOK. KÓD: ").append(n.docCode).append("\n")
        append(n.subject).append(" · ").append(n.dateIso).append("\n\n")
        if (n.summary.isNotBlank()) append("ÖSSZEFOGLALÓ\n").append(n.summary).append("\n\n")
        if (n.structured.isNotBlank()) append("JEGYZET\n").append(n.structured).append("\n\n")
        if (n.keywords.isNotBlank()) append("KULCSFOGALMAK\n").append(n.keywords).append("\n\n")
        if (n.transcript.isNotBlank()) append("LEIRAT\n").append(n.transcript)
    }

    fun sendEmail() {
        scope.launch(Dispatchers.IO) {
            val s = Settings.get(ctx)
            if (s.email.isBlank()) {
                alertMessage = "Kérlek add meg a címzett e-mail címet a Beállítások menüpontban!"
            } else {
                try {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:${s.email}")
                        putExtra(Intent.EXTRA_SUBJECT, "[Fülelő] ${n.docCode.ifBlank { n.subject }} - ${n.dateIso}")
                        putExtra(Intent.EXTRA_TEXT, fullText())
                    }
                    ctx.startActivity(Intent.createChooser(intent, "E-mail küldése"))
                } catch (e: Exception) {
                    alertMessage = "Hiba az e-mail küldéskor: ${e.message}"
                }
            }
        }
    }

    fun saveToPhone() {
        scope.launch(Dispatchers.IO) {
            try {
                val fileName = "${n.docCode.ifBlank { "JEGYZET_${n.id}" }}.txt"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetFile = File(downloadsDir, fileName)
                targetFile.writeText(fullText())
                alertMessage = "Sikeresen elmentve a Letöltések mappába:\n${targetFile.absolutePath}"
            } catch (e: Exception) {
                alertMessage = "Hiba a fájl mentésekor: ${e.message}"
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (n.docCode.isNotBlank()) n.docCode else n.subject) }, navigationIcon = { BackButton(onBack) })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("${n.subject} · ${n.dateIso} · ${fmt(n.startMin)}–${fmt(n.endMin)}" +
                    (n.processedOnline?.let { if (it) " · online" else " · offline" } ?: ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }

            if (!editing) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 1. Akció sor (Görgethető gombok, így nincs függőleges szövegtörés)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                OutlinedButton(onClick = {
                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("jegyzet", fullText()))
                                }) { Text("📋 Másolás", maxLines = 1) }
                            }
                            item {
                                OutlinedButton(onClick = {
                                    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, fullText())
                                    ctx.startActivity(Intent.createChooser(i, "Jegyzet megosztása"))
                                }) { Text("🔗 Megosztás", maxLines = 1) }
                            }
                            item {
                                OutlinedButton(onClick = { sendEmail() }) { Text("📧 Email küldése", maxLines = 1) }
                            }
                            item {
                                OutlinedButton(onClick = { saveToPhone() }) { Text("💾 Mentés telefonra", maxLines = 1) }
                            }
                            item {
                                OutlinedButton(onClick = {
                                    eSummary = n.summary; eStructured = n.structured; eTranscript = n.transcript
                                    editing = true
                                }) { Text("✏️ Szerkesztés", maxLines = 1) }
                            }
                            item {
                                OutlinedButton(onClick = {
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
                                }) { Text(if (speaking) "⏹️ Leállítás" else "🔊 Felolvasás", maxLines = 1) }
                            }
                        }

                        // 2. Másodlagos akciók
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                OutlinedButton(onClick = {
                                    scope.launch { Db.get(ctx).noteDao().update(n.copy(status = NoteStatus.PROCESSING)) }
                                    ProcessingWorker.resummarize(ctx, n.id)
                                }) { Text("✨ Újra-összefoglalás", maxLines = 1) }
                            }
                            val audioOk = n.audioPath?.let { File(it).exists() } == true
                            if (audioOk) {
                                item {
                                    OutlinedButton(onClick = {
                                        val f = File(n.audioPath!!)
                                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                                        val i = Intent(Intent.ACTION_SEND).setType("audio/mp4")
                                            .putExtra(Intent.EXTRA_STREAM, uri)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        ctx.startActivity(Intent.createChooser(i, "Hang mentése / megosztása"))
                                    }) { Text("🎧 Hang mentése", maxLines = 1) }
                                }
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

    if (alertMessage != null) {
        AlertDialog(
            onDismissRequest = { alertMessage = null },
            title = { Text("Értesítés") },
            text = { Text(alertMessage!!) },
            confirmButton = {
                TextButton(onClick = { alertMessage = null }) { Text("Rendben") }
            }
        )
    }
}
