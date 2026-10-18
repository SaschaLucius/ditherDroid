package com.ditherprint.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExtractMacFromUrlTest {

    @Test
    fun extractsValidMac() {
        val mac = extractMacFromUrl("http://api.qu-in.com/wx/v?mac=DD1807567BA3")
        assertEquals("DD:18:07:56:7B:A3", mac)
    }

    @Test
    fun acceptsAlreadyColonSeparated() {
        // After stripping colons length must be 12
        val mac = extractMacFromUrl("http://example.com/?mac=DD:18:07:56:7B:A3")
        assertEquals("DD:18:07:56:7B:A3", mac)
    }

    @Test
    fun rejectsShortMac() {
        assertNull(extractMacFromUrl("http://api.qu-in.com/wx/v?mac=DD1807"))
    }

    @Test
    fun rejectsMissingMac() {
        assertNull(extractMacFromUrl("http://api.qu-in.com/wx/v?foo=bar"))
    }

    @Test
    fun rejectsNonHex() {
        assertNull(extractMacFromUrl("http://api.qu-in.com/wx/v?mac=GG1807567BA3"))
    }
}
