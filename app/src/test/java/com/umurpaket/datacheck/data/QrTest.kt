package com.umurpaket.datacheck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrTest {
    @Test
    fun resiFromQr() {
        // isi QR asli dari label J&T (foto contoh)
        assertEquals(listOf("JX1000000001"), Importer.resiCandidates("JX1000000001"))
        assertEquals("JX1000000001", Importer.resiCandidates(" jx1000000001 \n").first())
        assertTrue("JX1000000001" in Importer.resiCandidates("https://jet.co.id/track?billcode=JX1000000001&x=1"))
        assertTrue("JX1000000001" in Importer.resiCandidates("{\"billCode\":\"JX1000000001\",\"orderId\":\"500000000000000001\"}"))
        assertTrue(Importer.resiCandidates("1200000000000000001").none { it.startsWith("JX") })
    }
}
