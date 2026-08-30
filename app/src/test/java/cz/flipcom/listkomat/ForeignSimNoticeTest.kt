package cz.flipcom.listkomat

import cz.flipcom.listkomat.model.ForeignSimNotice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignSimNoticeTest {

    @Test
    fun `czech SIM suppresses regardless of language`() {
        assertFalse(ForeignSimNotice.shouldShow("en", "cz", dismissed = false))
        assertFalse(ForeignSimNotice.shouldShow("cs", "CZ", dismissed = false))
    }

    @Test
    fun `foreign SIM shows regardless of language`() {
        assertTrue(ForeignSimNotice.shouldShow("cs", "de", dismissed = false))
        assertTrue(ForeignSimNotice.shouldShow("en", "at", dismissed = false))
    }

    @Test
    fun `no SIM signal falls back to the broad language heuristic`() {
        assertTrue(ForeignSimNotice.shouldShow("en", "", dismissed = false))
        assertTrue(ForeignSimNotice.shouldShow("en-GB", null, dismissed = false))
        assertFalse(ForeignSimNotice.shouldShow("cs", "", dismissed = false))
        assertFalse(ForeignSimNotice.shouldShow("cs-CZ", null, dismissed = false))
        // unknown language + unknown SIM: stay quiet rather than nag
        assertFalse(ForeignSimNotice.shouldShow(null, "", dismissed = false))
    }

    @Test
    fun `dismissed wins over everything`() {
        assertFalse(ForeignSimNotice.shouldShow("en", "de", dismissed = true))
    }
}
