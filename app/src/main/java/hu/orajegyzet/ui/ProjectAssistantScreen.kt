package hu.orajegyzet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.orajegyzet.ai.GeminiPipeline
import hu.orajegyzet.ai.Extractive
import hu.orajegyzet.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class Granularity { EXECUTIVE, COORDINATOR, OPERATIONAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectAssistantScreen(onBack: () -> Unit, onOpenNote: (Long) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val scope = rememberCoroutineScope()

    val projects by db.projectDao().all().collectAsState(initial = emptyList())
    var selectedProjectId by remember { mutableStateOf("prj-01") }

    val activeProject = projects.firstOrNull { it.id == selectedProjectId } ?: projects.firstOrNull()

    val events by db.projectEventDao().byProject(activeProject?.id ?: "prj-01")
        .collectAsState(initial = emptyList())
    val tasks by db.ganttTaskDao().byProject(activeProject?.id ?: "prj-01")
        .collectAsState(initial = emptyList())
    val meetings by db.noteDao().byProject(activeProject?.id ?: "prj-01")
        .collectAsState(initial = emptyList())

    var granularity by remember { mutableStateOf(Granularity.EXECUTIVE) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Döntési napló, 1 = Gantt / WBS, 2 = Megbeszélések

    var showNewProjectDialog by remember { mutableStateOf(false) }
    var showDeleteProjectConfirm by remember { mutableStateOf(false) }
    var showNewEventDialog by remember { mutableStateOf(false) }
    var showNewTaskDialog by remember { mutableStateOf(false) }

    var aiReportTitle by remember { mutableStateOf<String?>(null) }
    var aiReportText by remember { mutableStateOf<String?>(null) }

    // --- AI Jelentés Generáló ---
    fun runAiAnalysis(type: String) {
        val prj = activeProject ?: return
        aiReportTitle = type
        aiReportText = "AI elemzés és jelentés készítése folyamatban..."

        scope.launch(Dispatchers.IO) {
            try {
                val s = Settings.get(ctx)
                val prompt = when (type) {
                    "Vezetői Összefoglaló" -> """
                        Készíts tömör, kategóriákba szedett Vezetői Összefoglalót az alábbi projektről!
                        Projekt: ${prj.code} - ${prj.name} (${prj.ragStatus.uppercase()} RAG státusz)
                        Döntések és Kockázatok: ${events.joinToString { "${it.type}: ${it.title} (${it.impact})" }}
                        Mérföldkövek és Feladatok: ${tasks.joinToString { "${it.title}: ${it.progress}% kész" }}
                    """.trimIndent()
                    "Státuszjelentés" -> """
                        Készíts heti stílusú Projekt Státuszjelentést (következő lépések, haladás, nyitott kérdések)!
                        Projekt: ${prj.code} - ${prj.name}
                        Budget: ${prj.spent ?: "0"} / ${prj.budget ?: "ismeretlen"}
                        Feladatok haladása: ${tasks.map { "${it.title}: ${it.status}" }}
                    """.trimIndent()
                    else -> """
                        Végezz QA Minőségi Auditor felülvizsgálatot a projekt kockázataira és lemaradásaira!
                        Projekt: ${prj.code} - ${prj.name}
                        Nyitott kockázatok: ${events.filter { it.type == "risk" }.map { it.title }}
                        Megakadt/Késésben lévő feladatok: ${tasks.filter { it.progress < 100 }.map { it.title }}
                    """.trimIndent()
                }

                val resultText = if (s.geminiApiKey.isNotBlank()) {
                    val pipeline = GeminiPipeline(s.geminiApiKey, s.geminiModel)
                    pipeline.resummarize(prompt, prj.name, LocalDate.now().toString(), false).summary
                } else {
                    Extractive.summarize(prompt, 6)
                }

                aiReportText = resultText
            } catch (e: Exception) {
                aiReportText = "Hiba történt az AI jelentés generálása során: ${e.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projekt Asszisztens") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(onClick = { showNewProjectDialog = true }) {
                        Text("+ Új Projekt")
                    }
                    if (activeProject != null) {
                        TextButton(
                            onClick = { showDeleteProjectConfirm = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("🗑️ Törlés")
                        }
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Projekt Kiválasztó & Fejléc Kártya
                item {
                    if (activeProject != null) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "${activeProject.code} · ${activeProject.name}",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        )
                                        Text(
                                            "Felelős: ${activeProject.manager} · Céldátum: ${activeProject.targetDate}",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    RagStatusBadge(activeProject.ragStatus)
                                }

                                var showDescription by remember { mutableStateOf(false) }

                                if (activeProject.description.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable { showDescription = !showDescription }
                                    ) {
                                        Text(
                                            if (showDescription) "▾ Leírás elrejtése" else "▸ Leírás megtekintése",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (showDescription) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            activeProject.description,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (activeProject.budget != null) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text("Költségkeret: ${activeProject.budget}", fontSize = 12.sp)
                                        Text("Felhasznált: ${activeProject.spent ?: "0 Ft"}", fontSize = 12.sp)
                                    }
                                }

                                // Projekt választó vízszintes görgethető sor
                                if (projects.size > 1) {
                                    Spacer(Modifier.height(8.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(projects) { p ->
                                            FilterChip(
                                                selected = p.id == activeProject.id,
                                                onClick = { selectedProjectId = p.id },
                                                label = { Text(p.code, maxLines = 1) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Granularitási Szűrő (Vezetői / Koordinátori / Operatív)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Granularitás / Nézet", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = granularity == Granularity.EXECUTIVE,
                                    onClick = { granularity = Granularity.EXECUTIVE },
                                    label = { Text("👑 Vezetői", maxLines = 1) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = granularity == Granularity.COORDINATOR,
                                    onClick = { granularity = Granularity.COORDINATOR },
                                    label = { Text("👥 Koordinátori", maxLines = 1) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = granularity == Granularity.OPERATIONAL,
                                    onClick = { granularity = Granularity.OPERATIONAL },
                                    label = { Text("💻 Operatív", maxLines = 1) }
                                )
                            }
                        }
                    }
                }

                // 3. AI Akciógombok (Vezetői Összefoglaló, Státuszjelentés, QA Audit)
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            OutlinedButton(onClick = { runAiAnalysis("Vezetői Összefoglaló") }) {
                                Text("✨ Vezetői Összefoglaló", fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        item {
                            OutlinedButton(onClick = { runAiAnalysis("Státuszjelentés") }) {
                                Text("📊 Státuszjelentés", fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        item {
                            OutlinedButton(onClick = { runAiAnalysis("QA Minőségi Audit") }) {
                                Text("🛡️ QA Audit", fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }

                // 4. Tabok: Döntési Napló (0) vs Gantt WBS (1) vs Megbeszélések (2)
                item {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Döntések (${events.size})", maxLines = 1) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Gantt WBS (${tasks.size})", maxLines = 1) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("Fülelő (${meetings.size})", maxLines = 1) }
                        )
                    }
                }

                // Tab 0: Döntési Napló
                if (selectedTab == 0) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Döntések & Kockázatok", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showNewEventDialog = true }) {
                                Text("+ Új Döntés", maxLines = 1)
                            }
                        }
                    }

                    val filteredEvents = when (granularity) {
                        Granularity.EXECUTIVE -> events.filter { it.impact == "critical" || it.impact == "high" || it.type == "milestone" }
                        Granularity.COORDINATOR -> events.filter { it.type == "decision" || it.type == "risk" || it.type == "milestone" }
                        Granularity.OPERATIONAL -> events
                    }

                    if (filteredEvents.isEmpty()) {
                        item {
                            Text(
                                "Nincs rögzített döntés vagy kockázat ebben a nézetben.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }

                    items(filteredEvents) { e ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        when (e.type) {
                                            "decision" -> "⚖️ Döntés"
                                            "milestone" -> "🚩 Mérföldkő"
                                            "risk" -> "⚠️ Kockázat"
                                            else -> "📌 Akció"
                                        },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.weight(1f))
                                    ImpactBadge(e.impact)
                                }
                                Text(e.title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                                if (e.description.isNotBlank()) {
                                    Text(
                                        e.description,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (!e.owner.isNullOrBlank()) {
                                    Text("Felelős: ${e.owner} · Dátum: ${e.date}", fontSize = 12.sp)
                                }
                                if (!e.decisionRationale.isNullOrBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Indoklás: ${e.decisionRationale}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                                if (!e.mitigation.isNullOrBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Kockázatenyhítés: ${e.mitigation}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }

                // Tab 1: Gantt WBS Feladatok
                if (selectedTab == 1) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Gantt Feladathálózat (WBS)", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showNewTaskDialog = true }) {
                                Text("+ Új Feladat", maxLines = 1)
                            }
                        }
                    }

                    val filteredTasks = when (granularity) {
                        Granularity.EXECUTIVE -> tasks.filter { it.isMilestone || it.priority == "critical" || it.priority == "high" }
                        Granularity.COORDINATOR -> tasks.filter { it.status == "in_progress" || it.status == "blocked" || it.priority == "high" || it.priority == "critical" }
                        Granularity.OPERATIONAL -> tasks
                    }

                    if (filteredTasks.isEmpty()) {
                        item {
                            Text(
                                "Nincs megjeleníthető feladat ebben a nézetben.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }

                    items(filteredTasks) { t ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (t.isMilestone) "🚩 MÉRFFÖLDKŐ" else t.title,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    PriorityBadge(t.priority)
                                }
                                if (t.isMilestone) {
                                    Text(t.title, fontSize = 14.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { t.progress / 100f },
                                    modifier = Modifier.fillMaxWidth().height(6.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Haladás: ${t.progress}% · Felelős: ${t.assignee}", fontSize = 12.sp)
                                    Text("${t.startDate} ➔ ${t.dueDate}", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Tab 2: Fülelő Megbeszélések
                if (selectedTab == 2) {
                    item {
                        Text("Projekt Megbeszélések & Átiratok", fontWeight = FontWeight.Bold)
                    }

                    if (meetings.isEmpty()) {
                        item {
                            Text(
                                "Még nincs ehhez a projekthez csatolt megbeszélés. A felvételeket a főképernyőn indíthatod el.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }

                    items(meetings) { m ->
                        Card(onClick = { onOpenNote(m.id) }) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("${m.subject} · ${m.dateIso}", fontWeight = FontWeight.Bold)
                                Text(
                                    m.summary.take(120) + if (m.summary.length > 120) "…" else "",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- AI Jelentés Kijelző Dialog ---
    if (aiReportText != null) {
        AlertDialog(
            onDismissRequest = { aiReportText = null; aiReportTitle = null },
            title = { Text(aiReportTitle ?: "AI Elemzés") },
            text = {
                Box(Modifier.heightIn(max = 360.dp)) {
                    LazyColumn {
                        item {
                            MarkdownText(aiReportText!!)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { aiReportText = null; aiReportTitle = null }) {
                    Text("Bezárás")
                }
            }
        )
    }

    // --- Projekt Törlése Dialog ---
    if (showDeleteProjectConfirm && activeProject != null) {
        AlertDialog(
            onDismissRequest = { showDeleteProjectConfirm = false },
            title = { Text("Projekt Törlése") },
            text = { Text("Biztosan törölni szeretnéd a(z) '${activeProject.name}' (${activeProject.code}) projektet és a hozzá tartozó döntéseket?") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            db.projectEventDao().deleteByProject(activeProject.id)
                            db.ganttTaskDao().deleteByProject(activeProject.id)
                            db.projectDao().delete(activeProject)
                        }
                        showDeleteProjectConfirm = false
                    }
                ) { Text("Igen, Törlés") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteProjectConfirm = false }) { Text("Mégse") }
            }
        )
    }

    // --- Új Projekt Létrehozása Dialog ---
    if (showNewProjectDialog) {
        var pCode by remember { mutableStateOf("PRJ-02") }
        var pName by remember { mutableStateOf("") }
        var pDesc by remember { mutableStateOf("") }
        var pManager by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showNewProjectDialog = false },
            title = { Text("Új Projekt Létrehozása") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pCode,
                        onValueChange = { pCode = it },
                        label = { Text("Projekt kód (pl. PRJ-02)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pName,
                        onValueChange = { pName = it },
                        label = { Text("Projekt neve") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pDesc,
                        onValueChange = { pDesc = it },
                        label = { Text("Leírás") }
                    )
                    OutlinedTextField(
                        value = pManager,
                        onValueChange = { pManager = it },
                        label = { Text("Projektvezető") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pName.isNotBlank()) {
                            val newPrj = ProjectEntity(
                                id = "prj-${System.currentTimeMillis()}",
                                code = pCode.ifBlank { "PRJ-00" },
                                name = pName.trim(),
                                description = pDesc.trim(),
                                manager = pManager.ifBlank { "Projektvezető" },
                                startDate = LocalDate.now().toString(),
                                targetDate = LocalDate.now().plusMonths(3).toString()
                            )
                            scope.launch {
                                db.projectDao().insert(newPrj)
                                selectedProjectId = newPrj.id
                            }
                            showNewProjectDialog = false
                        }
                    }
                ) { Text("Mentés") }
            },
            dismissButton = {
                TextButton(onClick = { showNewProjectDialog = false }) { Text("Mégse") }
            }
        )
    }

    // --- Új Döntés / Kockázat Dialog ---
    if (showNewEventDialog && activeProject != null) {
        var title by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var type by remember { mutableStateOf("decision") }
        var impact by remember { mutableStateOf("high") }
        var owner by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showNewEventDialog = false },
            title = { Text("Új Döntés / Kockázat / Mérföldkő") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item { FilterChip(selected = type == "decision", onClick = { type = "decision" }, label = { Text("Döntés", maxLines = 1) }) }
                        item { FilterChip(selected = type == "risk", onClick = { type = "risk" }, label = { Text("Kockázat", maxLines = 1) }) }
                        item { FilterChip(selected = type == "milestone", onClick = { type = "milestone" }, label = { Text("Mérföldkő", maxLines = 1) }) }
                    }
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Cím") }, singleLine = true)
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Részletes leírás") })
                    OutlinedTextField(value = owner, onValueChange = { owner = it }, label = { Text("Felelős") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (title.isNotBlank()) {
                        val evt = ProjectEventEntity(
                            projectId = activeProject.id,
                            date = LocalDate.now().toString(),
                            type = type,
                            title = title.trim(),
                            description = desc.trim(),
                            impact = impact,
                            owner = owner.ifBlank { activeProject.manager }
                        )
                        scope.launch { db.projectEventDao().insert(evt) }
                        showNewEventDialog = false
                    }
                }) { Text("Hozzáadás") }
            },
            dismissButton = { TextButton(onClick = { showNewEventDialog = false }) { Text("Mégse") } }
        )
    }

    // --- Új Gantt WBS Feladat Dialog ---
    if (showNewTaskDialog && activeProject != null) {
        var title by remember { mutableStateOf("") }
        var assignee by remember { mutableStateOf("") }
        var priority by remember { mutableStateOf("medium") }

        AlertDialog(
            onDismissRequest = { showNewTaskDialog = false },
            title = { Text("Új Gantt WBS Feladat") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Feladat címe") }, singleLine = true)
                    OutlinedTextField(value = assignee, onValueChange = { assignee = it }, label = { Text("Felelős neve") }, singleLine = true)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item { FilterChip(selected = priority == "low", onClick = { priority = "low" }, label = { Text("Alacsony", maxLines = 1) }) }
                        item { FilterChip(selected = priority == "medium", onClick = { priority = "medium" }, label = { Text("Közepes", maxLines = 1) }) }
                        item { FilterChip(selected = priority == "high", onClick = { priority = "high" }, label = { Text("Magas", maxLines = 1) }) }
                        item { FilterChip(selected = priority == "critical", onClick = { priority = "critical" }, label = { Text("Kritikus", maxLines = 1) }) }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (title.isNotBlank()) {
                        val t = GanttTaskEntity(
                            projectId = activeProject.id,
                            title = title.trim(),
                            startDate = LocalDate.now().toString(),
                            dueDate = LocalDate.now().plusDays(14).toString(),
                            assignee = assignee.ifBlank { activeProject.manager },
                            priority = priority
                        )
                        scope.launch { db.ganttTaskDao().insert(t) }
                        showNewTaskDialog = false
                    }
                }) { Text("Hozzáadás") }
            },
            dismissButton = { TextButton(onClick = { showNewTaskDialog = false }) { Text("Mégse") } }
        )
    }
}

@Composable
private fun RagStatusBadge(rag: String) {
    val (color, text) = when (rag.lowercase()) {
        "red" -> Color(0xFFD32F2F) to "🔴 KRITIKUS"
        "amber", "yellow" -> Color(0xFFF57C00) to "🟡 FIGYELEM"
        else -> Color(0xFF388E3C) to "🟢 RENDBEN"
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun ImpactBadge(impact: String) {
    val color = when (impact.lowercase()) {
        "critical" -> Color(0xFFD32F2F)
        "high" -> Color(0xFFF57C00)
        "medium" -> Color(0xFF1976D2)
        else -> Color(0xFF388E3C)
    }
    Text(impact.uppercase(), color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp)
}

@Composable
private fun PriorityBadge(priority: String) {
    val color = when (priority.lowercase()) {
        "critical" -> Color(0xFFD32F2F)
        "high" -> Color(0xFFF57C00)
        "medium" -> Color(0xFF1976D2)
        else -> Color(0xFF388E3C)
    }
    Text(priority.uppercase(), color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp)
}
