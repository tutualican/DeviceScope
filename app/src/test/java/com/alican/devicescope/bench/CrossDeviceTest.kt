package com.alican.devicescope.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossDeviceTest {

    @Test
    fun parsesCommaSeparatedCsvWithQuotedDevice() {
        val csv = """
            device,geekbench_single,mikyas_single
            "Pixel 8",1500,1200
            "Galaxy S24",1800,1400
        """.trimIndent()
        val table = CrossDevice.parse(csv)
        assertEquals(listOf("device", "geekbench_single", "mikyas_single"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals("Pixel 8", table.rows[0][0])
    }

    @Test
    fun parsesSemicolonCsvWithTurkishDecimalComma() {
        val csv = """
            cihaz;geekbench_single;mikyas_single
            A;1.500,5;1.200,0
            B;1.600,0;1.300,0
            C;1.700,0;1.350,0
            D;1.800,0;1.400,0
            E;1.900,0;1.500,0
        """.trimIndent()
        val table = CrossDevice.parse(csv)
        assertEquals(';', table.delimiter)
        val results = CrossDevice.correlate(table)
        val single = results.first { it.tag == "single" }
        assertEquals(5, single.n)
        // both devices are monotonic in both columns
        assertEquals(1.0, single.spearman!!, 1e-12)
        assertTrue(
            "1.200,0 Türkçe ondalık ayracıyla 1200 olarak okunmalı",
            single.deviations.any { it.device == "A" && kotlin.math.abs(it.ours - 1200.0) < 1e-9 }
        )
    }

    @Test
    fun perfectRankAgreementGivesRhoOne() {
        val csv = buildString {
            appendLine("device,geekbench_single,mikyas_single,geekbench_multi,mikyas_multi")
            val rows = listOf(
                Triple("A", 1000, 900),
                Triple("B", 1200, 1000),
                Triple("C", 1400, 1100),
                Triple("D", 1600, 1300),
                Triple("E", 1800, 1500)
            )
            for ((name, gb, mk) in rows) {
                appendLine("$name,$gb,$mk,${gb * 5},${mk * 5}")
            }
        }
        val results = CrossDevice.correlate(CrossDevice.parse(csv))
        for (tag in listOf("single", "multi")) {
            val r = results.first { it.tag == tag }
            assertEquals("$tag n", 5, r.n)
            assertEquals("$tag rho", 1.0, r.spearman!!, 1e-12)
            assertEquals(true, r.pass)
        }
    }

    @Test
    fun swappedPairDeviationIsReported() {
        val csv = """
            device,geekbench_single,mikyas_single
            A,1000,900
            B,1200,1000
            C,1400,1200
            D,1600,1100
            E,1800,1500
        """.trimIndent()
        val results = CrossDevice.correlate(CrossDevice.parse(csv))
        val r = results.first { it.tag == "single" }
        assertNotNull(r.spearman)
        assertTrue("sıralama bozulunca rho 1 olmamalı", r.spearman!! < 1.0)
        // Device D is 4th by Geekbench but 3rd/lower by ours, so it must appear.
        assertTrue(r.deviations.any { it.device == "D" })
    }

    @Test
    fun fewerThanFiveDevicesMeansNoCorrelation() {
        val csv = """
            device,geekbench_single,mikyas_single
            A,1000,900
            B,1200,1000
        """.trimIndent()
        val r = CrossDevice.correlate(CrossDevice.parse(csv)).first { it.tag == "single" }
        assertNull(r.spearman)
        assertNull(r.pass)
        assertTrue(r.note.contains("en az 5"))
    }

    @Test
    fun singleColumnIsReportedAsMissingInsteadOfZero() {
        val csv = """
            device,geekbench_single
            A,1000
            B,1200
        """.trimIndent()
        val r = CrossDevice.correlate(CrossDevice.parse(csv)).first { it.tag == "single" }
        assertNull(r.spearman)
        assertTrue(r.note.contains("Eksik sütun"))
    }

    @Test
    fun antutuCategoryTargetsAreEightyPercent() {
        val csv = buildString {
            appendLine("device,antutu_total,kinetik_total,antutu_gpu,kinetik_gpu")
            val rows = listOf(
                Triple("A", 500000, 900),
                Triple("B", 600000, 1000),
                Triple("C", 700000, 1100),
                Triple("D", 800000, 1200),
                Triple("E", 900000, 1300)
            )
            for ((name, antutu, kinetik) in rows) {
                appendLine("$name,$antutu,$kinetik,${antutu / 2},${kinetik / 2}")
            }
        }
        val results = CrossDevice.correlate(CrossDevice.parse(csv))
        val total = results.first { it.tag == "total" }
        val gpu = results.first { it.tag == "gpu" }
        assertEquals(0.9, total.target, 1e-12)
        assertEquals(0.8, gpu.target, 1e-12)
        assertEquals(1.0, total.spearman!!, 1e-12)
    }
}
