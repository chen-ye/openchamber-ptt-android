package com.openchamber.ptt.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MlKitSttClientTest {
    @Test
    fun `resolveSupportedLocale normalizes English variants to US`() {
        assertEquals(Locale.US, MlKitSttClient.resolveSupportedLocale(Locale.UK))
        assertEquals(Locale.US, MlKitSttClient.resolveSupportedLocale(Locale.CANADA))
        assertEquals(Locale.US, MlKitSttClient.resolveSupportedLocale(Locale.ENGLISH))
        assertEquals(Locale.US, MlKitSttClient.resolveSupportedLocale(Locale.US))
    }

    @Test
    fun `resolveSupportedLocale preserves non-English locales`() {
        assertEquals(Locale.FRANCE, MlKitSttClient.resolveSupportedLocale(Locale.FRANCE))
        assertEquals(Locale.JAPAN, MlKitSttClient.resolveSupportedLocale(Locale.JAPAN))
    }
}
