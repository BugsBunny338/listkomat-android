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
    // The locale formatting itself is android.icu (device only): checked on the
    // A14 in cs and en. Here: what reaches it, and what is shown verbatim.
    @Test
    fun `iso date parses, anything else passes through verbatim`() {
        assertEquals(java.time.LocalDate.of(2026, 8, 30), cz.flipcom.listkomat.ui.parseCatalogDate("2026-08-30"))
        assertEquals(null, cz.flipcom.listkomat.ui.parseCatalogDate("srpen 2026"))
        assertEquals(null, cz.flipcom.listkomat.ui.parseCatalogDate("2026-8-30"))
        assertEquals("srpen 2026", cz.flipcom.listkomat.ui.formatCatalogDate("srpen 2026", java.util.Locale("cs")))
    }
}
