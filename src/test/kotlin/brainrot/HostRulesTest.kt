package brainrot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRulesTest {
    private val rules = HostRules.parse(
        """
        ! Title: sample
        ||doubleclick.net^
        ||ads.example.com^
        @@||good.ads.example.com^|
        ||wild*.example.org^
        ||modifier.example.org^${'$'}important
        """.trimIndent()
    )

    @Test
    fun parsesOnlyPlainRules() {
        assertEquals(setOf("doubleclick.net", "ads.example.com"), rules.blocked)
        assertEquals(setOf("good.ads.example.com"), rules.allowed)
    }

    @Test
    fun blocksListedHostAndSubdomains() {
        assertTrue(rules.blocks("doubleclick.net"))
        assertTrue(rules.blocks("Stats.G.DoubleClick.net"))
        assertTrue(rules.blocks("x.ads.example.com"))
    }

    @Test
    fun allowsUnlistedAndExceptedHosts() {
        assertFalse(rules.blocks("www.youtube.com"))
        assertFalse(rules.blocks("notdoubleclick.net"))
        assertFalse(rules.blocks("good.ads.example.com"))
        assertFalse(rules.blocks("cdn.good.ads.example.com"))
        assertFalse(rules.blocks("localhost"))
    }
}
