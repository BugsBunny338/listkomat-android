package cz.flipcom.listkomat

import cz.flipcom.listkomat.model.DurationFormat
import cz.flipcom.listkomat.model.DurationFormat.Parts
import org.junit.Assert.assertEquals
import org.junit.Test

/** The cross-client unit-threshold rule (listkomat-catalog README, Durations). */
class DurationFormatTest {
    @Test
    fun `hours iff at least 120 and divisible by 60`() {
        assertEquals(Parts(30, false), DurationFormat.parts(30))
        assertEquals(Parts(60, false), DurationFormat.parts(60))    // sixty minutes, not one hour
        assertEquals(Parts(90, false), DurationFormat.parts(90))
        assertEquals(Parts(2, true), DurationFormat.parts(120))
        assertEquals(Parts(150, false), DurationFormat.parts(150))  // not divisible
        assertEquals(Parts(24, true), DurationFormat.parts(1440))
        assertEquals(Parts(72, true), DurationFormat.parts(4320))
    }
}
