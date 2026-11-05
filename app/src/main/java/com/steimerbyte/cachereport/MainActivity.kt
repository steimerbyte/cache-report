package com.steimerbyte.cachereport

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal val Accent = Color(0xFFD71921)
internal val Dim = Color(0xFF7A7A7A)
internal val Panel = Color(0xFF0D0D0D)
internal val Line = Color(0xFF242424)
internal val Good = Color(0xFF39D98A)
internal val Warn = Color(0xFFE0A32E)

private const val REQ_PERMISSION = 1001

/**
 * Argument ist der Zielwert für freien Speicher, nicht die zu löschende Menge: FreeStorageHelper
 * steigt bei `getUsableSpace() >= bytes` sofort aus. "1" ist deshalb ein Nullop,
 * Long.MAX_VALUE ist unerreichbar und leert alle Caches (Quota und darunter).
 */
private const val TRIM_LIVE = "9223372036854775807"
private const val TRIM_DRY = "1"
private const val BIG_CACHE = 100L * 1024 * 1024

internal enum class ShizukuState { UNKNOWN, DEAD, ALIVE, GRANTED, DENIED }

internal enum class SortOrder { SIZE, NAME }

private fun gib(bytes: Long) = String.format(Locale.GERMANY, "%.2f GiB", bytes / 1073741824.0)

private fun sizeText(bytes: Long) = when {
    bytes <= 0 -> "0"
    bytes >= 1024 * 1024 -> String.format(Locale.GERMANY, "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.GERMANY, "%.0f KB", bytes / 1024.0)
}

/** Suche (Label ODER Package) und Filter zuerst, dann Sortierung — Trefferzahl bleibt so stimmig. */
private fun DiskSnapshot.view(
    query: String, order: SortOrder, onlyBig: Boolean, labels: Map<String, String>
): List<PkgStat> {
    val q = query.trim()
    val hits = pkgs.filter { s ->
        (!onlyBig || s.cache > BIG_CACHE) &&
            (q.isEmpty() || s.pkg.contains(q, ignoreCase = true) ||
                (labels[s.pkg] ?: "").contains(q, ignoreCase = true))
    }
    return when (order) {
        SortOrder.SIZE -> hits.sortedByDescending { it.cache }
        SortOrder.NAME -> hits.sortedWith(
            compareBy(Collator.getInstance(Locale.GERMANY)) { labels[it.pkg] ?: it.pkg }
        )
    }
}

class MainActivity : ComponentActivity() {

    internal var shizukuState by mutableStateOf(ShizukuState.UNKNOWN)
    internal var statusLine by mutableStateOf("")
    internal var snapshot by mutableStateOf<DiskSnapshot?>(null)
    internal var labels by mutableStateOf<Map<String, String>>(emptyMap())
    internal var confirming by mutableStateOf(false)
    internal var busy by mutableStateOf(false)
    internal var freed by mutableStateOf<Long?>(null)
    /** Wie viele Zeilen der Warteschlange schon in der Liste stehen (Ladefortschritt). */
    internal var feedCount by mutableStateOf(Int.MAX_VALUE)
    internal var loadTotal by mutableStateOf(0)
    /** Uhrzeit des Schnappschusses für den Kopf — es gibt keinen Auto-Reload. */
    internal var stamp by mutableStateOf("")

    internal var cpuHist by mutableStateOf<List<Float>>(emptyList())
    internal var ramHist by mutableStateOf<List<Float>>(emptyList())
    internal var cpuValue by mutableStateOf("")
    internal var ramValue by mutableStateOf("")

    private val permListener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
        if (code == REQ_PERMISSION) {
            shizukuState = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ShizukuState.GRANTED
            } else {
                ShizukuState.DENIED
            }
            onShizukuReady()
        }
    }

    private val deadListener = Shizuku.OnBinderDeadListener { shizukuState = ShizukuState.DEAD }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderDeadListener(deadListener)
        setContent { AppTheme { Screen() } }
        refreshShizuku()
        onShizukuReady()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        Shizuku.removeBinderDeadListener(deadListener)
        super.onDestroy()
    }

    // ---- Shizuku ----

    internal fun refreshShizuku() {
        shizukuState = try {
            when {
                !Shizuku.pingBinder() -> ShizukuState.DEAD
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.GRANTED
                else -> ShizukuState.ALIVE
            }
        } catch (t: Throwable) {
            ShizukuState.DEAD
        }
        onShizukuReady()
    }

    internal fun requestShizuku() {
        if (shizukuState == ShizukuState.DEAD || shizukuState == ShizukuState.UNKNOWN) {
            statusLine = "Shizuku läuft nicht. Starte es über die Shizuku-App."
            return
        }
        runCatching { Shizuku.requestPermission(REQ_PERMISSION) }
            .onFailure { statusLine = "Anfrage fehlgeschlagen: ${it.message}" }
    }

    /** Startet ein Kommando mit UID 2000 (shell) über Shizuku und liefert stdout (Fallback: stderr). */
    internal fun runShell(vararg cmd: String): String {
        val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
        val proc = service.newProcess(arrayOf(*cmd), null, null)
        val err = StringBuilder()
        val reader = Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(proc.errorStream)
                    .bufferedReader().use { r -> r.forEachLine { err.appendLine(it) } }
            }
        }
        reader.start()
        val out = ParcelFileDescriptor.AutoCloseInputStream(proc.inputStream)
            .bufferedReader().use { it.readText() }
        proc.waitFor()
        reader.join(2000)
        return out.ifBlank { err.toString() }
    }

    /** Wird aufgerufen, sobald sich der Shizuku-Zustand ändert — hier die Daten laden. */
    internal fun onShizukuReady() {
        if (shizukuState != ShizukuState.GRANTED) return
        if (snapshot == null && !busy) load()
    }

    /** Liest `dumpsys diskstats` und die Labels neu. `before` gesetzt: Differenz nach Trim anzeigen. */
    internal fun load(before: Long? = null) {
        if (busy) return
        lifecycleScope.launch {
            busy = true
            if (before == null) freed = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = runShell("dumpsys", "diskstats")
                    val snap = DiskStats.parse(raw).getOrElse { throw it }
                    val pm = packageManager
                    val map = pm.getInstalledPackages(0).associate { p ->
                        val label = p.applicationInfo?.let {
                            runCatching { it.loadLabel(pm).toString() }.getOrNull()
                        }
                        p.packageName to (label ?: p.packageName)
                    }
                    snap to map
                }
            }
            // busy bleibt bis zum Ende der Warteschlange gesetzt: sonst käme ein zweiter Lauf
            // (Knopf erneut) mitten in die laufende Schleife.
            result.onSuccess { (snap, map) ->
                snapshot = snap
                labels = map
                stamp = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
                // Trim-Ausgabe steht in statusLine und wird mit before != null übergeben — stehen lassen.
                if (before == null) statusLine = ""
                if (before != null) freed = (before - snap.total).coerceAtLeast(0L)
                // Warteschlange: ein Eintrag pro Schritt, die Liste wächst sichtbar mit und der
                // Hauptthread bleibt zwischen den Schritten ansprechbar (yield statt einem Schub).
                loadTotal = snap.pkgs.size
                feedCount = 0
                for (i in 1..loadTotal) {
                    feedCount = i
                    yield()      // Hauptthread zwischen zwei Einträgen ansprechbar
                    delay(4)     // ~1,7 s für 438 Einträge: Fortschritt bleibt sichtbar
                }
            }.onFailure {
                statusLine = it.message ?: it.toString()
            }
            loadTotal = 0
            feedCount = Int.MAX_VALUE
            busy = false
        }
    }

    /** CPU-Last aus /proc/stat: Δidle gegen Δgesamt, wie im Profile Optimizer. */
    private fun readCpuSample(): LongArray? {
        val line = runShell("cat", "/proc/stat")
            .lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val nums = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 5) return null
        return longArrayOf(nums.sum(), nums[3] + nums[4])
    }

    /** MemTotal/MemAvailable in kB; null, wenn das Feld fehlt (dann bleibt das Histogramm leer). */
    private fun readMem(): Pair<Long, Long>? {
        val raw = runShell("cat", "/proc/meminfo")
        val total = Regex("(?m)^MemTotal:\\s+(\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull()
        val avail = Regex("(?m)^MemAvailable:\\s+(\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull()
        if (total == null || avail == null || total <= 0) return null
        return total to avail
    }

    /**
     * Globaler Cache-Trim über UID 2000. Wirkt auf ALLE Apps.
     * [target] ist der Zielwert für freien Speicher; unerreichbar = alles löschen.
     */
    internal fun trimCaches(target: String) {
        val before = snapshot?.total ?: return
        confirming = false
        if (busy) return
        lifecycleScope.launch {
            busy = true
            val out = withContext(Dispatchers.IO) {
                runCatching { runShell("pm", "trim-caches", target).trim() }
            }
            busy = false
            statusLine = out.getOrElse { "Trim fehlgeschlagen: ${it.message}" }
                .ifBlank { "pm trim-caches $target · keine Ausgabe" }
            load(before)
        }
    }

    // ---- UI ----

    @Composable
    private fun Screen() {
        var query by rememberSaveable { mutableStateOf("") }
        var order by rememberSaveable { mutableStateOf(SortOrder.SIZE) }
        var onlyBig by rememberSaveable { mutableStateOf(false) }
        var about by rememberSaveable { mutableStateOf(false) }

        // Sampler liegt nur im Screen: Verlassen der Composition bricht ihn ab.
        LaunchedEffect(shizukuState) {
            if (shizukuState != ShizukuState.GRANTED) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                var previous = runCatching { readCpuSample() }.getOrNull()
                while (isActive) {
                    delay(700)
                    // Ein toter Shizuku-Binder wirft hier; das darf nur das Histogramm leer lassen.
                    val cpu = runCatching { readCpuSample() }.getOrNull()
                    val mem = runCatching { readMem() }.getOrNull()
                    withContext(Dispatchers.Main.immediate) {
                        val before = previous
                        if (before != null && cpu != null && cpu[0] > before[0]) {
                            val load = (1.0 - (cpu[1] - before[1]).toDouble() / (cpu[0] - before[0]))
                                .toFloat().coerceIn(0f, 1f)
                            cpuHist = (cpuHist + load).takeLast(48)
                            cpuValue = "${(load * 100).roundToInt()} %"
                        }
                        if (mem != null) {
                            val totalGiB = mem.first / 1048576.0
                            val usedGiB = (mem.first - mem.second) / 1048576.0
                            ramHist = (ramHist + (usedGiB / totalGiB).toFloat()).takeLast(48)
                            ramValue = String.format(Locale.GERMANY, "%.1f/%.1f GiB", usedGiB, totalGiB)
                        }
                    }
                    previous = cpu ?: previous
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Osw(
                kicker = if (stamp.isBlank()) {
                    "APP-CACHE PRO PAKET · dumpsys diskstats"
                } else {
                    "SCHNAPPSCHUSS $stamp · NICHT AUTOMATISCH"
                },
                title = "Cache Report",
                action = { MiniButton(if (busy) "LÄDT …" else "AKTUALISIEREN") { load() } },
                onKickerClick = { about = true }
            )
            // Fortschritt der Warteschlange im Kopf (Skill, Abschnitt 6).
            if (loadTotal > 0) {
                Spacer(Modifier.height(6.dp))
                Micro("LADE $feedCount / $loadTotal", Warn)
            }
            Spacer(Modifier.height(12.dp))

            val snap = snapshot
            if (shizukuState != ShizukuState.GRANTED) {
                NotReadyPanel()
            } else if (snap == null) {
                LoadPanel()
            } else {
                val rows = snap.view(query, order, onlyBig, labels).take(feedCount)
                // Panels, Suche und Pills liegen mit in der Liste: im Querformat bleibt sonst
                // für die Liste nichts übrig und der Aktionsblock unten wird abgeschnitten.
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        LivePanel(cpuHist, ramHist, cpuValue, ramValue)
                        Spacer(Modifier.height(12.dp))
                        SummaryPanel(
                            listOf(
                                "GESAMT-CACHE" to gib(snap.total),
                                "DATEN" to gib(snap.totalData),
                                "APP (App Size)" to gib(snap.totalApp)
                            )
                        )
                        Spacer(Modifier.height(6.dp))
                        Micro(
                            "${snap.pkgs.size} PAKETE · ${snap.withCache} MIT CACHE · TOP 10 " +
                                String.format(Locale.GERMANY, "%.1f %%", snap.top10Share)
                        )
                        if (snap.headerCache >= 0 && snap.headerCache != snap.total) {
                            Spacer(Modifier.height(10.dp))
                            Micro("CACHE-KOPFZEILE ABWEICHEND: ${gib(snap.headerCache)}", Warn)
                        }
                        if (snap.headerData >= 0 && snap.headerData != snap.totalData) {
                            Spacer(Modifier.height(10.dp))
                            Micro("DATEN-KOPFZEILE ABWEICHEND: ${gib(snap.headerData)}", Warn)
                        }
                        Spacer(Modifier.height(12.dp))
                        SearchField(query, { query = it }, rows.size)
                        Spacer(Modifier.height(8.dp))
                        FilterRow(order, { order = it }, onlyBig) { onlyBig = !onlyBig }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (rows.isNotEmpty()) {
                        items(rows) { EntryRow(it, snap.total, labels[it.pkg] ?: it.pkg) }
                    } else if (loadTotal == 0) {
                        // Während der Warteschlange ist "leer" nur ein Zwischenstand, keine Aussage.
                        item { EmptyState(query, onlyBig) }
                    }
                }
                BottomPanel(snap)
            }
        }

        val snap = snapshot
        if (confirming && snap != null) TrimDialog(snap)
        if (about) AboutDialog { about = false }
    }

    // ---- Komponenten (NothingOS-Layoutsystem, Skill nothingos-app, Abschnitt 6) ----

    @Composable
    private fun Osw(
        kicker: String, title: String, action: (@Composable () -> Unit)? = null,
        onKickerClick: (() -> Unit)? = null
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    kicker, color = Dim, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (onKickerClick == null) Modifier
                    else Modifier.clickable { onKickerClick() }.padding(vertical = 4.dp)
                )
                Text(
                    title.uppercase(Locale.GERMANY), color = Color.White, fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                action()
            }
        }
    }

    @Composable
    private fun MiniButton(text: String, onClick: () -> Unit) {
        Box(
            Modifier
                .height(36.dp)
                .border(1.dp, Line, RoundedCornerShape(2.dp))
                .clickable { onClick() }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text.uppercase(Locale.GERMANY), color = Color.White, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp, maxLines = 1
            )
        }
    }

    @Composable
    private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
        // 32 dp Optik, 44 dp Touch-Ziel (Skill 4.2).
        Box(
            Modifier.height(44.dp).clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .height(32.dp)
                    .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(2.dp))
                    .background(
                        if (selected) Accent.copy(alpha = 0.14f) else Color.Transparent,
                        RoundedCornerShape(2.dp)
                    )
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text.uppercase(Locale.GERMANY), color = if (selected) Accent else Dim,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp,
                    maxLines = 1
                )
            }
        }
    }

    @Composable
    private fun SearchField(text: String, onChange: (String) -> Unit, hits: Int) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .border(1.dp, Line, RoundedCornerShape(2.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Micro("SUCHE")
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) Micro("LABEL ODER PACKAGE")
                BasicTextField(
                    value = text,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (text.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "×", color = Dim, fontSize = 17.sp,
                    modifier = Modifier.clickable { onChange("") }
                )
            }
            Spacer(Modifier.width(8.dp))
            Micro("$hits TREFFER", if (hits == 0) Warn else Dim)
        }
    }

    @Composable
    private fun OutlineBox(content: @Composable ColumnScope.() -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Line, RoundedCornerShape(2.dp))
                .padding(10.dp),
            content = content
        )
    }

    @Composable
    private fun SummaryPanel(items: List<Pair<String, String>>) {
        // Zwei Spalten pro Zeile: 22-sp-Werte wie "15,21 GiB" passen nicht in vier Spalten.
        OutlineBox {
            items.chunked(2).forEachIndexed { index, row ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    row.forEachIndexed { i, (label, value) ->
                        if (i > 0) Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Micro(label)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                value, color = Color.White, fontSize = 22.sp,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun LivePanel(
        cpu: List<Float>, ram: List<Float>, cpuText: String = "", ramText: String = ""
    ) {
        OutlineBox {
            HistRow("CPU", cpu, cpuText)
            Spacer(Modifier.height(6.dp))
            HistRow("RAM", ram, ramText)
        }
    }

    @Composable
    private fun HistRow(label: String, history: List<Float>, value: String) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Micro(label)
            Spacer(Modifier.width(10.dp))
            Histogram(history, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            if (value.isEmpty()) {
                Micro("KEINE DATEN")
            } else {
                Text(
                    value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, maxLines = 1
                )
            }
        }
    }

    @Composable
    private fun Histogram(history: List<Float>, modifier: Modifier = Modifier) {
        val slots = 48
        Canvas(modifier.fillMaxWidth().height(46.dp)) {
            val slotWidth = size.width / slots
            val barWidth = slotWidth * 0.62f
            val shift = slots - history.size
            for (i in 0 until slots) {
                val index = i - shift
                val value = if (index >= 0) history[index].coerceIn(0f, 1f) else 0f
                val barHeight = if (value > 0f) (size.height * value).coerceAtLeast(2f) else 1f
                val x = i * slotWidth + (slotWidth - barWidth) / 2f
                drawRect(
                    color = if (value > 0f) Accent else Line,
                    topLeft = Offset(x, size.height - barHeight),
                    size = Size(barWidth, barHeight)
                )
            }
        }
    }

    /** Anteil als 2-dp-Balken: Rest in `Line`, ab 100 % in `Good`. */
    @Composable
    private fun Bar(ratio: Float) {
        val share = ratio.coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(2.dp).background(Line)) {
            Box(
                Modifier
                    .fillMaxWidth(share)
                    .fillMaxHeight()
                    .background(if (share >= 1f) Good else Accent)
            )
        }
    }

    @Composable
    private fun FilterRow(
        order: SortOrder, onOrder: (SortOrder) -> Unit, onlyBig: Boolean, onBig: () -> Unit
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Pill("GRÖSSE", order == SortOrder.SIZE) { onOrder(SortOrder.SIZE) }
            Spacer(Modifier.width(6.dp))
            Pill("NAME", order == SortOrder.NAME) { onOrder(SortOrder.NAME) }
            Spacer(Modifier.weight(1f))
            Pill("NUR > 100 MB", onlyBig) { onBig() }
        }
    }

    @Composable
    private fun EntryRow(stat: PkgStat, total: Long, label: String) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            label, color = Color.White, fontSize = 12.sp, maxLines = 1,
                            letterSpacing = 0.3.sp, fontFamily = FontFamily.Monospace,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            stat.pkg, color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        sizeText(stat.cache), color = Color.White, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "DATEN ${sizeText(stat.data)} · GESAMT ${sizeText(stat.app)}",
                    color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.2.sp, maxLines = 1
                )
                Spacer(Modifier.height(6.dp))
                Bar(if (total <= 0) 0f else stat.cache.toFloat() / total)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        }
    }

    @Composable
    private fun EmptyState(query: String, onlyBig: Boolean) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Micro(
                when {
                    query.isNotBlank() -> "KEIN TREFFER FÜR \"$query\""
                    onlyBig -> "KEIN PAKET ÜBER 100 MB"
                    else -> "KEINE PAKETE"
                }
            )
        }
    }

    /** Kein Dump vorhanden: Ladezustand, Ursache eines Fehlers oder leere Antwort. */
    @Composable
    private fun LoadPanel() {
        when {
            busy -> Micro("MESSE …", Warn)
            statusLine.isNotBlank() -> Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Accent, RoundedCornerShape(2.dp))
                    .padding(12.dp)
            ) {
                Micro("PARSE-FEHLER", Accent)
                Spacer(Modifier.height(6.dp))
                Text(
                    statusLine, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            else -> Micro("KEINE DATEN")
        }
        Spacer(Modifier.height(12.dp))
        WideButton("ERNEUT MESSEN") { load() }
    }

    @Composable
    private fun NotReadyPanel() {
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Accent, RoundedCornerShape(2.dp))
                .padding(12.dp)
        ) {
            Column {
                Micro(
                    if (shizukuState == ShizukuState.DEAD) "SHIZUKU · NICHT ERREICHBAR"
                    else "SHIZUKU · NICHT BEREIT",
                    Accent
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ohne laufenden Shizuku-Dienst und dessen Berechtigung kann diese App nichts tun.",
                    color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                if (shizukuState == ShizukuState.DEAD || shizukuState == ShizukuState.UNKNOWN) {
                    WideButton("ERNEUT PRÜFEN") { refreshShizuku() }
                } else {
                    WideButton("ERLAUBEN") { requestShizuku() }
                }
                if (statusLine.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        statusLine, color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    @Composable
    private fun BottomPanel(snap: DiskSnapshot) {
        Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp)) {
            freed?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Micro("FREIGEGEBEN", Good)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        sizeText(it), color = Good, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.width(8.dp))
                    Micro("JETZT ${gib(snap.total)}")
                }
                Spacer(Modifier.height(6.dp))
            }
            if (statusLine.isNotBlank()) {
                Text(
                    statusLine, color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.2.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
            }
            WideButton(if (busy) "LÄUFT …" else "CACHE LEEREN", enabled = !busy) {
                confirming = true
            }
            Spacer(Modifier.height(4.dp))
            // Hint trägt hier die Trockenlauf-Aktion (README, Abschnitt Layoutsystem, Abweichung 2).
            Hint("TROCKENLAUF · pm trim-caches $TRIM_DRY · LÖSCHT NICHTS") { trimCaches(TRIM_DRY) }
        }
    }

    @Composable
    private fun Hint(text: String, onClick: () -> Unit) {
        Box(
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text, color = Dim, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                letterSpacing = 1.4.sp, maxLines = 2, textAlign = TextAlign.Center
            )
        }
    }

    /** About: Name, Version + Gerät, Leitspruch, Zweck, echte Kernkommandos (Skill, Abschnitt 4). */
    @Composable
    private fun AboutDialog(onClose: () -> Unit) {
        AlertDialog(
            onDismissRequest = onClose,
            containerColor = Panel,
            modifier = Modifier.border(1.dp, Line, RoundedCornerShape(2.dp)),
            text = {
                AboutPanel(
                    name = "Cache Report",
                    version = "${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    purpose = "Zeigt alle drei Größen je Paket und leert den Cache global.",
                    commands = listOf(
                        "dumpsys diskstats",
                        "cat /proc/stat",
                        "cat /proc/meminfo",
                        "pm trim-caches <zielwert>"
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = onClose) {
                    Text(
                        "SCHLIESSEN", color = Dim, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp
                    )
                }
            }
        )
    }

    @Composable
    private fun AboutPanel(
        name: String, version: String, purpose: String, commands: List<String>
    ) {
        Column {
            Text(
                name.uppercase(Locale.GERMANY), color = Color.White, fontSize = 28.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(6.dp))
            Micro("Version $version")
            Spacer(Modifier.height(14.dp))
            Text(
                "Function over form.", color = Accent, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(12.dp))
            Text(
                purpose, color = Color.White, fontSize = 12.sp, letterSpacing = 0.3.sp,
                fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(14.dp))
            Micro("KERNKOMMANDOS")
            Spacer(Modifier.height(4.dp))
            commands.forEach { Mono(it) }
        }
    }

    @Composable
    private fun TrimDialog(snap: DiskSnapshot) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = Panel,
            titleContentColor = Color.White,
            textContentColor = Dim,
            modifier = Modifier.border(1.dp, Line, RoundedCornerShape(2.dp)),
            title = {
                Text(
                    "CACHE GLOBAL LEEREN", fontSize = 14.sp, letterSpacing = 1.2.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        "Gemessen: ${gib(snap.total)} über ${snap.pkgs.size} Pakete.",
                        color = Color.White, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Der Trim wirkt GLOBAL: Android leert die Caches ALLER Apps, nicht nur " +
                            "der hier gelisteten. Apps müssen Daten danach neu laden.",
                        color = Dim, fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { trimCaches(TRIM_LIVE) }) {
                    Text(
                        "LEEREN", color = Accent, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(
                        "ABBRECHEN", color = Dim, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp
                    )
                }
            }
        )
    }

    @Composable
    private fun Micro(text: String, color: Color = Dim) {
        Text(
            text, color = color, fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp
        )
    }

    @Composable
    private fun Mono(text: String, color: Color = Dim) {
        Text(
            text, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            letterSpacing = 0.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }

    @Composable
    private fun WideButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, if (enabled) Accent else Line, RoundedCornerShape(2.dp))
                .background(
                    if (pressed && enabled) Accent.copy(alpha = 0.18f) else Color.Transparent,
                    RoundedCornerShape(2.dp)
                )
                .clickable(
                    enabled = enabled, interactionSource = interaction, indication = null
                ) { onClick() }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text.uppercase(Locale.GERMANY), color = if (enabled) Color.White else Dim,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Bold, maxLines = 1
            )
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Color.Black,
            surface = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}
