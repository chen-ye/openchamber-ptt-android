package com.openchamber.ptt.storage

import android.content.SharedPreferences
import com.openchamber.ptt.tts.SpokenDetailLevel
import com.openchamber.ptt.tts.SttEngine
import com.openchamber.ptt.tts.TtsEngine
import com.openchamber.ptt.tts.TtsEngineOption
import com.openchamber.ptt.tts.TtsInputMode
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenStoreTest {
    @Test
    fun `empty store returns no servers and null active server`() {
        val store = TokenStore(InMemorySharedPreferences())
        assertTrue(store.getServers().isEmpty())
        assertNull(store.getActiveServer())
        assertNull(store.getServerUrl())
        assertNull(store.getToken())
        assertNull(store.getPinnedSession())
    }

    @Test
    fun `getTokenForUrl and getDirectoryForUrl distinguish subpath servers on same origin`() {
        val store = TokenStore(InMemorySharedPreferences())
        val srvA = store.upsertServer("https://proxy.example.com/team-a", label = "Team A", token = "tok_a")
        val srvB = store.upsertServer("https://proxy.example.com/team-b", label = "Team B", token = "tok_b")
        store.updatePinnedSession(srvA.id, PinnedSession("s1", "/dir/a", "A"))
        store.updatePinnedSession(srvB.id, PinnedSession("s2", "/dir/b", "B"))

        assertEquals("tok_a", store.getTokenForUrl("https://proxy.example.com/team-a/api/session".toHttpUrl()))
        assertEquals("/dir/a", store.getDirectoryForUrl("https://proxy.example.com/team-a/api/session".toHttpUrl()))

        assertEquals("tok_b", store.getTokenForUrl("https://proxy.example.com/team-b/api/dictation/ws".toHttpUrl()))
        assertEquals("/dir/b", store.getDirectoryForUrl("https://proxy.example.com/team-b/api/dictation/ws".toHttpUrl()))

        assertNull(store.getTokenForUrl("https://proxy.example.com/team-c/api/session".toHttpUrl()))
        assertNull(store.getDirectoryForUrl("https://proxy.example.com/team-c/api/session".toHttpUrl()))
    }

    @Test
    fun `multi-server upsert, switching, 401 isolation, and removal preserve other servers`() {
        val store = TokenStore(InMemorySharedPreferences())
        val wks = store.upsertServer(
            serverUrl = "http://100.64.0.10:14096",
            label = "Workstation",
            token = "tok_wks",
            makeActive = true,
        )
        val home = store.upsertServer(
            serverUrl = "https://server-b.example.ts.net:14096",
            label = "Homelab",
            token = "tok_home",
            makeActive = true,
        )

        assertEquals(2, store.getServers().size)
        assertEquals(home.id, store.getActiveServer()?.id)
        assertEquals("tok_wks", store.getTokenForUrl("http://100.64.0.10:14096/api/session".toHttpUrl()))
        assertEquals("tok_home", store.getTokenForUrl("https://server-b.example.ts.net:14096/api/dictation/ws".toHttpUrl()))

        // Clearing auth on Homelab leaves Workstation signed in
        store.clearAuth(home.id)
        assertNull(store.getServer(home.id)?.token)
        assertEquals("tok_wks", store.getServer(wks.id)?.token)

        // Switching back to Workstation restores active state & pinned session
        val pinned = PinnedSession("ses_w1", "/proj", "Build fix")
        store.updatePinnedSession(wks.id, pinned)
        store.setActiveServer(wks.id)
        assertEquals(wks.id, store.getActiveServer()?.id)
        assertEquals(pinned, store.getPinnedSession())

        // Removing Workstation falls back to Homelab
        store.removeServer(wks.id)
        assertEquals(1, store.getServers().size)
        assertEquals(home.id, store.getActiveServer()?.id)
    }

    @Test
    fun `per-server voice override isolates STT and TTS config from global defaults`() {
        val store = TokenStore(InMemorySharedPreferences())
        val srvA = store.upsertServer("http://srv-a:14096", label = "Server A", token = "tok_a")
        val srvB = store.upsertServer("http://srv-b:14096", label = "Server B", token = "tok_b", makeActive = false)

        // Global defaults apply to both initially
        store.saveTtsVoice("Sulafat", customServerId = null)
        store.saveSttEngine(SttEngine.OPENCHAMBER_PARAKEET, customServerId = null)
        assertEquals("Sulafat", store.getEffectiveTtsVoice(srvA.id))
        assertEquals("Sulafat", store.getEffectiveTtsVoice(srvB.id))

        // Customize Server B only
        val customPipeline = listOf(
            TtsEngineOption(TtsEngine.MLKIT_GENAI, enabled = true),
            TtsEngineOption(TtsEngine.ANDROID_SYSTEM, enabled = true),
            TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = false),
        )
        store.setServerVoiceOverride(
            srvB.id,
            ServerVoiceOverride(
                ttsPipeline = customPipeline,
                geminiVoice = "Puck",
                openAiVoice = "onyx",
                sttEngine = SttEngine.MLKIT_GENAI_ADVANCED,
                sttFallbackToParakeet = false,
            ),
        )

        assertEquals("Sulafat", store.getEffectiveTtsVoice(srvA.id))
        assertEquals(SttEngine.OPENCHAMBER_PARAKEET, store.getEffectiveSttEngine(srvA.id))
        assertTrue(store.getEffectiveSttFallbackToParakeet(srvA.id))

        assertEquals("Puck", store.getEffectiveTtsVoice(srvB.id))
        assertEquals("onyx", store.getEffectiveOpenAiTtsVoice(srvB.id))
        assertEquals(SttEngine.MLKIT_GENAI_ADVANCED, store.getEffectiveSttEngine(srvB.id))
        assertFalse(store.getEffectiveSttFallbackToParakeet(srvB.id))
        assertEquals(TtsEngine.MLKIT_GENAI, store.getEffectiveTtsPipeline(srvB.id).first().engine)

        assertEquals(TtsInputMode.SUMMARIZED, store.getEffectiveTtsInputMode(srvA.id))
        assertEquals(TtsInputMode.SUMMARIZED, store.getEffectiveTtsInputMode(srvB.id))

        store.saveTtsInputMode(TtsInputMode.SANITIZED, customServerId = srvB.id)
        assertEquals(TtsInputMode.SUMMARIZED, store.getEffectiveTtsInputMode(srvA.id))
        assertEquals(TtsInputMode.SANITIZED, store.getEffectiveTtsInputMode(srvB.id))

        assertEquals(SpokenDetailLevel.BALANCED, store.getEffectiveSpokenDetailLevel(srvA.id))
        store.saveSpokenDetailLevel(SpokenDetailLevel.DETAILED, customServerId = srvB.id)
        assertEquals(SpokenDetailLevel.BALANCED, store.getEffectiveSpokenDetailLevel(srvA.id))
        assertEquals(SpokenDetailLevel.DETAILED, store.getEffectiveSpokenDetailLevel(srvB.id))

        // Resetting Server B restores global defaults
        store.setServerVoiceOverride(srvB.id, null)
        assertNotNull(store.getServer(srvB.id))
        assertEquals("Sulafat", store.getEffectiveTtsVoice(srvB.id))
        assertEquals(SttEngine.OPENCHAMBER_PARAKEET, store.getEffectiveSttEngine(srvB.id))
        assertEquals(TtsInputMode.SUMMARIZED, store.getEffectiveTtsInputMode(srvB.id))
        assertEquals(SpokenDetailLevel.BALANCED, store.getEffectiveSpokenDetailLevel(srvB.id))
    }

    @Test
    fun `staged drafts persist per session key and clear independently`() {
        val store = TokenStore(InMemorySharedPreferences())
        val keyA = TokenStore.sessionKeyFor("srv1", PinnedSession("ses1", "/repo/a", "Repo A"))
        val keyB = TokenStore.sessionKeyFor("srv1", PinnedSession("ses2", "/repo/b", "Repo B"))

        store.saveStagedDraft(
            StagedDraft(
                sessionKey = keyA,
                rawBursts = listOf("first burst", "second burst"),
                organizedText = "Combined organized prompt.",
                previousOrganizedText = "First draft.",
                updatedAtMs = 12345L,
            ),
        )
        store.saveStagedDraft(
            StagedDraft(
                sessionKey = keyB,
                rawBursts = listOf("other"),
                organizedText = "Other session draft.",
                updatedAtMs = 12346L,
            ),
        )

        val restoredA = store.getStagedDraft(keyA)
        assertNotNull(restoredA)
        assertEquals("Combined organized prompt.", restoredA?.organizedText)
        assertEquals(2, restoredA?.rawBursts?.size)

        store.clearStagedDraft(keyA)
        assertNull(store.getStagedDraft(keyA))
        assertEquals("Other session draft.", store.getStagedDraft(keyB)?.organizedText)
    }

    @Test
    fun `changes flow increments on server, voice, and draft mutations`() {
        val store = TokenStore(InMemorySharedPreferences())
        val initial = store.changes.value
        val srv = store.upsertServer("http://127.0.0.1:4096", token = "tok")
        assertTrue(store.changes.value > initial)

        val afterUpsert = store.changes.value
        store.saveSpokenDetailLevel(SpokenDetailLevel.DETAILED, customServerId = srv.id)
        assertTrue(store.changes.value > afterUpsert)

        val afterVoice = store.changes.value
        store.saveStagedDraft(
            StagedDraft(
                sessionKey = TokenStore.sessionKeyFor(srv.id, PinnedSession("s1", "/tmp", "Demo")),
                rawBursts = listOf("hi"),
                organizedText = "hi",
            ),
        )
        assertTrue(store.changes.value > afterVoice)
    }

    @Test
    fun `changes flow broadcasts across separate TokenStore instances`() {
        val sharedPrefs = InMemorySharedPreferences()
        val storeA = TokenStore(sharedPrefs)
        val storeB = TokenStore(sharedPrefs)

        val before = storeB.changes.value
        val srv = storeA.upsertServer("http://127.0.0.1:4096", token = "tok_shared")
        assertTrue(storeB.changes.value > before)

        val afterUpsert = storeB.changes.value
        storeA.saveSpokenDetailLevel(SpokenDetailLevel.ESSENTIALS, customServerId = srv.id)
        assertTrue(storeB.changes.value > afterUpsert)
        assertEquals(SpokenDetailLevel.ESSENTIALS, storeB.getEffectiveSpokenDetailLevel(srv.id))
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
        override fun getString(key: String?, defValue: String?): String? =
            (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = values
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = REMOVE_SENTINEL
            }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) map.clear()
                for ((k, v) in pending) {
                    if (v === REMOVE_SENTINEL || v == null) {
                        map.remove(k)
                    } else {
                        map[k] = v
                    }
                }
            }
        }

        private object REMOVE_SENTINEL
    }
}
