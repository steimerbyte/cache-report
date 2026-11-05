package com.steimerbyte.cachereport

/**
 * Ein Paket aus `dumpsys diskstats`. Alle drei Größen in Bytes, aus den drei parallelen
 * Arrays desselben Dumps: Cache, Daten und die App-Größe (`App Sizes` = Code/APK, nicht
 * Cache+Daten — das Array kann je Paket kleiner als der Cache sein).
 */
internal data class PkgStat(val pkg: String, val cache: Long, val data: Long, val app: Long)

internal data class DiskSnapshot(
    val headerCache: Long, val headerData: Long, val pkgs: List<PkgStat>
) {
    val total: Long get() = pkgs.sumOf { it.cache }
    val totalData: Long get() = pkgs.sumOf { it.data }
    val totalApp: Long get() = pkgs.sumOf { it.app }
    val withCache: Int get() = pkgs.count { it.cache > 0 }
    val sorted: List<PkgStat> get() = pkgs.sortedByDescending { it.cache }
    val max: Long get() = pkgs.maxOfOrNull { it.cache } ?: 0L

    /** Anteil der zehn größten Pakete am Gesamt-Cache, in Prozent. */
    val top10Share: Double
        get() = if (total <= 0) 0.0 else sorted.take(10).sumOf { it.cache } * 100.0 / total
}

/**
 * Liest die parallelen Arrays aus `dumpsys diskstats`. Die Arrays können über
 * Zeilenumbrüche verteilt sein, deshalb wird zwischen `Key: [` und `]` gelesen
 * statt zeilenweise.
 */
internal object DiskStats {

    /** Failure trägt die Ursache als deutsche Meldung, die die UI unverändert anzeigt. */
    fun parse(raw: String): Result<DiskSnapshot> = runCatching {
        val names = strings(raw, "Package Names")
        if (names.isEmpty()) error("Keine Pakete im Dump")
        val caches = numbers(raw, "Cache Sizes", names.size)
        val data = numbers(raw, "App Data Sizes", names.size)
        val apps = numbers(raw, "App Sizes", names.size)
        DiskSnapshot(header(raw, "App Cache Size"), header(raw, "App Data Size"), names.indices.map {
            PkgStat(names[it], caches[it], data[it], apps[it])
        })
    }

    /** Inhalt hinter `Key: [` bis zur schließenden Klammer — auch über Zeilenumbrüche. */
    private fun body(raw: String, key: String): String {
        val start = raw.indexOf("$key: [")
        if (start < 0) error("Feld '$key' fehlt im Dump")
        val from = start + key.length + 3
        val end = raw.indexOf(']', from)
        if (end < 0) error("Feld '$key' ist nicht geschlossen")
        return raw.substring(from, end)
    }

    private fun strings(raw: String, key: String): List<String> =
        body(raw, key).split(',').map { it.trim().trim('"') }

    // Kein Filter/Nichtnumerische überspringen: sonst verschieben sich die Indizes
    // und jede Größe landet am falschen Paket. Längen ungleich => Dump unbrauchbar.
    private fun numbers(raw: String, key: String, count: Int): List<Long> {
        val xs = body(raw, key).split(',').map {
            it.trim().toLongOrNull() ?: error("Feld '$key' enthält '$it'")
        }
        if (xs.size != count) error("Arrays unterschiedlich lang: $key ${xs.size} vs Pakete $count")
        return xs
    }

    /** Fehlt die Kopfzeile, bleibt -1 als „nicht im Dump vorhanden". */
    private fun header(raw: String, key: String): Long =
        Regex("(?m)^$key:\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
}
