package cz.flipcom.listkomat

import cz.flipcom.listkomat.ui.formatTimeLeft
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTimeLeftTest {
    @Test
    fun `formats with and without hours, clamping negatives`() {
        assertEquals("1:29:59", formatTimeLeft((1 * 3600 + 29 * 60 + 59) * 1000L))
        assertEquals("59:59", formatTimeLeft((59 * 60 + 59) * 1000L))
        assertEquals("0:05", formatTimeLeft(5_000))
        assertEquals("0:00", formatTimeLeft(-1_000))
    }
}

class CatalogDateFormatTest {
    @Test
    fun `iso date renders czech-style, garbage passes through`() {
        org.junit.Assert.assertEquals("30. 8. 2026", cz.flipcom.listkomat.ui.formatCatalogDate("2026-08-30"))
        org.junit.Assert.assertEquals("nonsense", cz.flipcom.listkomat.ui.formatCatalogDate("nonsense"))
    }
}
