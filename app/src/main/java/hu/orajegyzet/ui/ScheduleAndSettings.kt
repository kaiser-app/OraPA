package hu.orajegyzet.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
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
import hu.orajegyzet.bell.BellScheduler
import hu.orajegyzet.data.*
import hu.orajegyzet.work.DigestWorker
import kotlinx.coroutines.launch

private val DAYS = listOf("H", "K", "Sze", "Cs", "P", "Szo", "V")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val scope = rememberCoroutineScope()
    val lessons by db.lessonDao().all().collectAsState(initial = emptyList())

    // --- Órarend befényképezése (Gemini képfelismerés) ---
    var photoStatus by remember { mutableStateOf<String?>(null) }
    val cameraUri = remember {
        val dir = java.io.File(ctx.cacheDir, "images").apply { mkdirs() }
        val f = java.io.File(dir, "schedule.jpg")
        androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    }

    fun importFromImage(uri: android.net.Uri) {
        photoStatus = "Órarend felismerése a képből…"
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val s = Settings.get(ctx)
                if (s.geminiApiKey.isBlank()) {
                    photoStatus = "Ehhez Gemini API-kulcs kell (Beállítások)."
                    return@launch
                }
                val parsed = hu.orajegyzet.ai.ScheduleParser.parse(ctx, uri, s.geminiApiKey, s.geminiModel)
                if (parsed.isEmpty()) {
                    photoStatus = "Nem sikerült órát kiolvasni a képből."
                } else {
                    parsed.forEach { db.lessonDao().insert(it) }
                    BellScheduler.rescheduleAll(ctx, db.lessonDao())
                    photoStatus = "${parsed.size} óra hozzáadva a képből."
                }
            } catch (e: Exception) {
                photoStatus = "Hiba: ${e.message}"
            }
        }
    }

    val galleryPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importFromImage(it) } }

    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { success -> if (success) importFromImage(cameraUri) }

    var subject by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var day by remember { mutableIntStateOf(1) }
    var start by remember { mutableStateOf("8:00") }
    var end by remember { mutableStateOf("8:45") }

    fun parseMin(t: String): Int? {
        val p = t.split(":"); if (p.size != 2) return null
        val h = p[0].trim().toIntOrNull() ?: return null
        val m = p[1].trim().toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Órarend") },
            navigationIcon = { BackButton(onBack) })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("Órarend befényképezése", fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Fotózd le a papír/digitális órarended, és az app kitölti.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { cameraLauncher.launch(cameraUri) },
                                modifier = Modifier.weight(1f)) { Text("Fényképezés") }
                            OutlinedButton(onClick = { galleryPicker.launch("image/*") },
                                modifier = Modifier.weight(1f)) { Text("Galériából") }
                        }
                        photoStatus?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Új óra", fontWeight = FontWeight.Medium)
                OutlinedTextField(subject, { subject = it }, label = { Text("Tantárgy") },
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(teacher, { teacher = it }, label = { Text("Tanár") },
                        modifier = Modifier.weight(1f))
                    OutlinedTextField(room, { room = it }, label = { Text("Terem") },
                        modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAYS.forEachIndexed { i, d ->
                        FilterChip(selected = day == i + 1, onClick = { day = i + 1 },
                            label = { Text(d) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it }, label = { Text("Kezdés (Ó:PP)") },
                        modifier = Modifier.weight(1f))
                    OutlinedTextField(end, { end = it }, label = { Text("Vége (Ó:PP)") },
                        modifier = Modifier.weight(1f))
                }
                Button(
                    onClick = {
                        val s = parseMin(start); val e = parseMin(end)
                        if (subject.isNotBlank() && s != null && e != null && e > s) {
                            scope.launch {
                                db.lessonDao().insert(Lesson(
                                    subject = subject.trim(), teacher = teacher.trim(),
                                    room = room.trim(), dayOfWeek = day, startMin = s, endMin = e))
                                BellScheduler.rescheduleAll(ctx, db.lessonDao())
                                subject = ""; teacher = ""; room = ""
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Hozzáadás") }
                Spacer(Modifier.height(8.dp))
                Text("Felvitt órák", fontWeight = FontWeight.Medium)
            }
            items(lessons) { l ->
                Card {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${DAYS[l.dayOfWeek - 1]} · ${l.subject}",
                                fontWeight = FontWeight.Medium)
                            Text("${fmt(l.startMin)}–${fmt(l.endMin)}" +
                                (if (l.room.isNotBlank()) " · ${l.room}" else ""))
                        }
                        TextButton(onClick = {
                            scope.launch {
                                db.lessonDao().delete(l)
                                BellScheduler.rescheduleAll(ctx, db.lessonDao())
                            }
                        }) { Text("Törlés") }
                    }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by Settings.flow(ctx).collectAsState(initial = AppSettings())
    var s by remember(saved) { mutableStateOf(saved) }

    var expProc by remember { mutableStateOf(true) }
    var expPerf by remember { mutableStateOf(false) }
    var expOnline by remember { mutableStateOf(false) }
    var expEmail by remember { mutableStateOf(false) }
    var expModels by remember { mutableStateOf(false) }

    // --- Offline modellek importálása fájlválasztóval (adb nem szükséges) ---
    var modelStatus by remember { mutableStateOf(ModelImporter.status(ctx)) }
    var importing by remember { mutableStateOf<String?>(null) }

    val gemmaPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            importing = "Gemma importálása…"
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ModelImporter.importGemma(ctx, it) }
                modelStatus = ModelImporter.status(ctx)
                importing = null
            }
        }
    }
    val voskPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            importing = "Vosk kicsomagolása…"
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ModelImporter.importVoskZip(ctx, it) }
                modelStatus = ModelImporter.status(ctx)
                importing = null
            }
        }
    }
    val whisperPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            importing = "Whisper modell másolása… (nagy fájl, eltarthat)"
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ModelImporter.importWhisper(ctx, it) }
                modelStatus = ModelImporter.status(ctx)
                importing = null
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Beállítások") },
            navigationIcon = { BackButton(onBack) })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SettingSection("Feldolgozás", expProc, { expProc = !expProc }) {
                    Text("Mód", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Mode.entries.forEach { m ->
                            FilterChip(selected = s.mode == m, onClick = { s = s.copy(mode = m) },
                                label = {
                                    Text(when (m) {
                                        Mode.OFFLINE -> "Offline"; Mode.ONLINE -> "Online"; Mode.AUTO -> "Automatikus"
                                    })
                                })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = s.privacyLock, onCheckedChange = { s = s.copy(privacyLock = it) })
                        Spacer(Modifier.width(8.dp))
                        Text("Adatvédelmi zár — a hang soha nem hagyja el a készüléket")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = s.wifiOnly, onCheckedChange = { s = s.copy(wifiOnly = it) })
                        Spacer(Modifier.width(8.dp))
                        Text("Online feldolgozás csak Wi-Fi-n")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = s.useGemma, onCheckedChange = { s = s.copy(useGemma = it) })
                        Spacer(Modifier.width(8.dp))
                        Text("Gemma összefoglaló offline módban (ki: csak leirat)")
                    }
                }
            }
            item {
                SettingSection("Teljesítmény / haladó", expPerf, { expPerf = !expPerf }) {
                    IntFieldRow("Whisper szálszám (2–8)", s.whisperThreads, 2..8) { s = s.copy(whisperThreads = it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = s.keepModelWarm, onCheckedChange = { s = s.copy(keepModelWarm = it) })
                        Spacer(Modifier.width(8.dp))
                        Text("Modell melegen tartása (ne töltse újra minden felvételnél)")
                    }
                    IntFieldRow("Melegen tartás (perc)", s.warmMinutes, 1..60) { s = s.copy(warmMinutes = it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = s.stagedDisplay, onCheckedChange = { s = s.copy(stagedDisplay = it) })
                        Spacer(Modifier.width(8.dp))
                        Text("Szakaszos megjelenítés (előbb a leirat, utána az összefoglaló)")
                    }
                    IntFieldRow("Felvétel-darab (mp)", s.whisperChunkSec, 10..120) { s = s.copy(whisperChunkSec = it) }
                    IntFieldRow("Darab-átfedés (mp)", s.whisperOverlapSec, 0..10) { s = s.copy(whisperOverlapSec = it) }
                    IntFieldRow("Összefoglaló-blokk (karakter)", s.summaryBlockChars, 1500..6000) { s = s.copy(summaryBlockChars = it) }
                    IntFieldRow("Hang megtartása (perc)", s.audioRetentionMin, 1..1440) { s = s.copy(audioRetentionMin = it) }
                    Text("A darabolás a hosszú felvételeknél számít; a default a mostani működés. " +
                        "Több szál gyorsabb lehet, de melegedésnél kevesebb (4–5) stabilabb.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            item {
                SettingSection("Online (Gemini)", expOnline, { expOnline = !expOnline }) {
                    OutlinedTextField(
                        value = s.geminiApiKey,
                        onValueChange = { s = s.copy(geminiApiKey = it.trim()) },
                        label = { Text("Gemini API-kulcs") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://aistudio.google.com/apikey")
                                )
                                ctx.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🔑 API-kulcs generálása")
                        }
                        OutlinedButton(
                            onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                                if (!clip.isNullOrBlank()) {
                                    s = s.copy(geminiApiKey = clip)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📋 Beillesztés")
                        }
                    }
                    Text(
                        "Az API-kulcs ingyenes. Kattints a gombra, jelentkezz be a Google-fiókodba, kattints a 'Create API key' gombra, másold ki, majd nyomj a 'Beillesztés' gombra.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    if (s.geminiApiKey.isNotBlank() && !s.geminiApiKey.startsWith("AIza")) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "⚠️ A beírt kulcs nem tűnik érvényes Gemini API-kulcsnak (a kulcsnak 'AIza...'-vel kell kezdődnie). A 'Create API key' gomb ablakában kattints a 'Copy' gombra!",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = s.geminiModel,
                        onValueChange = { s = s.copy(geminiModel = it.trim()) },
                        label = { Text("Modell") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
            item {
                SettingSection("Napi e-mail kivonat", expEmail, { expEmail = !expEmail }) {
                    OutlinedTextField(s.email, { s = s.copy(email = it) },
                        label = { Text("Címzett email") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(s.smtpHost, { s = s.copy(smtpHost = it) },
                            label = { Text("SMTP host") }, modifier = Modifier.weight(2f))
                        OutlinedTextField(s.smtpPort.toString(),
                            { v -> v.toIntOrNull()?.let { s = s.copy(smtpPort = it) } },
                            label = { Text("Port") }, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(s.smtpUser, { s = s.copy(smtpUser = it) },
                        label = { Text("SMTP felhasználó (email)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(s.smtpPass, { s = s.copy(smtpPass = it) },
                        label = { Text("SMTP jelszó (Gmail: alkalmazásjelszó)") },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(s.digestHour.toString(),
                        { v -> v.toIntOrNull()?.takeIf { it in 0..23 }?.let { s = s.copy(digestHour = it) } },
                        label = { Text("Kivonat kiküldése (óra, 0–23)") }, modifier = Modifier.fillMaxWidth())
                }
            }
            item {
                SettingSection("Modellek", expModels, { expModels = !expModels }) {
                    Text(
                        "Whisper (magyar beszédfelismerés): " +
                            (if (modelStatus.whisperInstalled) "telepítve ✓" else "nincs telepítve") +
                            "\nVosk (tartalék): " +
                            (if (modelStatus.voskInstalled) "telepítve ✓" else "nincs telepítve") +
                            "\nGemma (összefoglaló): " +
                            (if (modelStatus.gemmaInstalled) "telepítve ✓" else "nincs telepítve"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    importing?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp)); Text(it)
                        }
                    }
                    Button(onClick = { whisperPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(), enabled = importing == null) {
                        Text("Whisper modell (.bin) importálása")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { voskPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                            modifier = Modifier.weight(1f), enabled = importing == null) {
                            Text("Vosk .zip")
                        }
                        OutlinedButton(onClick = { gemmaPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f), enabled = importing == null) {
                            Text("Gemma .task")
                        }
                    }
                    Text("Magyarra a Whisper large-v3-turbo q5_0 modellt ajánljuk (README-link). " +
                        "Töltsd le a telefon böngészőjével, majd importáld itt. adb nem szükséges.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            item {
                Button(onClick = {
                    scope.launch {
                        Settings.save(ctx, s)
                        DigestWorker.schedule(ctx)
                    }
                    onBack()
                }, modifier = Modifier.fillMaxWidth()) { Text("Mentés") }
            }
        }
        }
    }
}

@Composable
private fun SettingSection(
    title: String, expanded: Boolean, onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { onToggle() }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(if (expanded) "▾" else "▸")
            }
            if (expanded) Column(
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content
            )
        }
    }
}

@Composable
private fun IntFieldRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v
            v.toIntOrNull()?.let { onChange(it.coerceIn(range.first, range.last)) }
        },
        label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true
    )
}
