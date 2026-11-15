package com.openchamber.ptt.network

import android.content.Context
import android.content.Intent
import android.net.Uri

object OpenChamberLauncher {
    const val OFFICIAL_APP_PACKAGE = "com.openchamber.app"
    const val OFFICIAL_APP_ACTIVITY = "com.openchamber.app.MainActivity"

    fun buildSessionDeepLinkString(
        sessionId: String,
        directory: String? = null,
    ): String {
        val cleanId = sessionId.trim()
        val encodedId = OpenChamberHttp.encodeUriComponent(cleanId)
        val cleanDir = directory?.trim()?.takeIf { it.isNotEmpty() }
        return if (cleanDir != null) {
            "openchamber://session/$encodedId?dir=${OpenChamberHttp.encodeUriComponent(cleanDir)}"
        } else {
            "openchamber://session/$encodedId"
        }
    }

    fun buildSessionDeepLink(
        sessionId: String,
        directory: String? = null,
    ): Uri = Uri.parse(buildSessionDeepLinkString(sessionId, directory))

    fun openInOfficialApp(
        context: Context,
        sessionId: String?,
        directory: String?,
        fallbackServerUrl: String?,
    ): Boolean {
        val cleanSessionId = sessionId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanSessionId != null) {
            val deepLinkIntent = Intent(Intent.ACTION_VIEW, buildSessionDeepLink(cleanSessionId, directory)).apply {
                setClassName(OFFICIAL_APP_PACKAGE, OFFICIAL_APP_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(deepLinkIntent) }.isSuccess) {
                return true
            }
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(OFFICIAL_APP_PACKAGE)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launchIntent != null && runCatching { context.startActivity(launchIntent) }.isSuccess) {
            return true
        }

        val normalizedUrl = fallbackServerUrl?.let { OpenChamberHttp.normalizeBaseUrl(it) }
            ?.takeIf { it.isNotBlank() } ?: return false
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(browserIntent) }.isSuccess
    }
}
