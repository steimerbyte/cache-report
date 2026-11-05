package com.steimerbyte.cachereport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiskStatsTest {

    private val dump = """
        App Size: 300
        App Data Size: 60
        App Cache Size: 30
        Package Names: ["a.b","c.d","e.f"]
        App Sizes: [100,200,0]
        App Data Sizes: [10,20,30]
        Cache Sizes: [5,25,0]
    """.trimIndent()

    @Test
    fun liestDreiGroessenProPaket() {
        val snap = DiskStats.parse(dump).getOrThrow()
        assertEquals(listOf(5L, 25L, 0L), snap.pkgs.map { it.cache })
        assertEquals(listOf(10L, 20L, 30L), snap.pkgs.map { it.data })
        assertEquals(listOf(100L, 200L, 0L), snap.pkgs.map { it.app })
    }

    @Test
    fun summenStimmenMitKopfzeilen() {
        val snap = DiskStats.parse(dump).getOrThrow()
        assertEquals(30L, snap.total)
        assertEquals(30L, snap.headerCache)
        assertEquals(60L, snap.totalData)
        assertEquals(60L, snap.headerData)
        assertEquals(300L, snap.totalApp)
    }

    @Test
    fun ungleicheLaengenNennenUrsache() {
        val kaputt = dump.replace("Cache Sizes: [5,25,0]", "Cache Sizes: [5,25]")
        val err = DiskStats.parse(kaputt).exceptionOrNull()
        assertTrue(err?.message, err?.message!!.contains("Arrays unterschiedlich lang"))
        assertTrue(err.message!!.contains("2 vs Pakete 3"))
    }

    @Test
    fun fehlendesArrayWirdBenannt() {
        val ohneDaten = dump.lines().filterNot { it.startsWith("App Data Sizes") }.joinToString("\n")
        val err = DiskStats.parse(ohneDaten).exceptionOrNull()
        assertTrue(err?.message, err?.message!!.contains("App Data Sizes"))
    }
}
