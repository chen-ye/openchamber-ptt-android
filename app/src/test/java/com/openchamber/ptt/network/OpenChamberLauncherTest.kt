package com.openchamber.ptt.network

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenChamberLauncherTest {
    @Test
    fun `buildSessionDeepLinkString builds openchamber session URI with optional encoded dir`() {
        assertEquals(
            "openchamber://session/ses_abc123",
            OpenChamberLauncher.buildSessionDeepLinkString("ses_abc123"),
        )
        assertEquals(
            "openchamber://session/ses_abc123?dir=%2Fworkspace%2Fapp",
            OpenChamberLauncher.buildSessionDeepLinkString("ses_abc123", "/workspace/app"),
        )
    }
}
