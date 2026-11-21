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

        assertFalse(store.getQuickTurnDirectAnswer())
        assertFalse(store.getEffectiveQuickTurnDirectAnswer(srvA.id))
        assertFalse(store.getEffectiveQuickTurnDirectAnswer(srvB.id))
        store.saveQuickTurnDirectAnswer(true, customServerId = srvB.id)
        assertFalse(store.getEffectiveQuickTurnDirectAnswer(srvA.id))
        assertTrue(store.getEffectiveQuickTurnDirectAnswer(srvB.id))
        store.saveQuickTurnDirectAnswer(true, customServerId = null)
        assertTrue(store.getEffectiveQuickTurnDirectAnswer(srvA.id))
        store.saveQuickTurnDirectAnswer(false, customServerId = srvB.id)
        assertFalse(store.getEffectiveQuickTurnDirectAnswer(srvB.id))
        assertTrue(store.getEffectiveQuickTurnDirectAnswer(srvA.id))

        // Resetting Server B restores global defaults
        store.setServerVoiceOverride(srvB.id, null)
        assertNotNull(store.getServer(srvB.id))
        assertEquals("Sulafat", store.getEffectiveTtsVoice(srvB.id))
        assertEquals(SttEngine.OPENCHAMBER_PARAKEET, store.getEffectiveSttEngine(srvB.id))
        assertEquals(TtsInputMode.SUMMARIZED, store.getEffectiveTtsInputMode(srvB.id))
        assertEquals(SpokenDetailLevel.BALANCED, store.getEffectiveSpokenDetailLevel(srvB.id))
        assertTrue(store.getEffectiveQuickTurnDirectAnswer(srvB.id))
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

    @Test
    fun `migrateStagedDraft moves draft across keys and preserves newer destination draft`() {
        val store = TokenStore(InMemorySharedPreferences())
        val oldKey = TokenStore.sessionKeyFor("srv1", PinnedSession("child_1", "", "Child"))
        val newKey = TokenStore.sessionKeyFor("srv1", PinnedSession("child_1", "/repo/main", "Child"))

        store.saveStagedDraft(
            StagedDraft(
                sessionKey = oldKey,
                rawBursts = listOf("first draft"),
                organizedText = "First draft.",
                updatedAtMs = 100L,
            ),
        )
        assertTrue(store.migrateStagedDraft(oldKey, newKey))
        assertNull(store.getStagedDraft(oldKey))
        val moved = store.getStagedDraft(newKey)
        assertEquals(newKey, moved?.sessionKey)
        assertEquals("First draft.", moved?.organizedText)

        // When destination already holds a newer draft, migration removes oldKey without clobbering newer draft
        store.saveStagedDraft(
            StagedDraft(
                sessionKey = newKey,
                rawBursts = listOf("newer"),
                organizedText = "Newer destination.",
                updatedAtMs = 300L,
            ),
        )
        store.saveStagedDraft(
            StagedDraft(
                sessionKey = oldKey,
                rawBursts = listOf("stale"),
                organizedText = "Stale source.",
                updatedAtMs = 200L,
            ),
        )
        assertTrue(store.migrateStagedDraft(oldKey, newKey))
        assertNull(store.getStagedDraft(oldKey))
        assertEquals("Newer destination.", store.getStagedDraft(newKey)?.organizedText)
    }

    @Test
    fun `reply cursors normalize keys and IDs, persist across TokenStore instances, and clear independently`() {
        val sharedPrefs = InMemorySharedPreferences()
        val storeA = TokenStore(sharedPrefs)
        val storeB = TokenStore(sharedPrefs)
        val keyA = TokenStore.sessionKeyFor("srv1", PinnedSession("ses1", "/repo/a", "Repo A"))
        val keyB = TokenStore.sessionKeyFor("srv1", PinnedSession("ses2", "/repo/b", "Repo B"))

        val beforeChange = storeB.changes.value
        storeA.saveReplyCursor("   ", SessionReplyCursor(lastHeardMessageId = "msg_ignored", updatedAtMs = 10L))
        assertEquals(beforeChange, storeB.changes.value)
        assertNull(storeB.getReplyCursor("   "))

        storeA.saveReplyCursor(
            "  $keyA  ",
            SessionReplyCursor(
                lastHeardMessageId = "  msg_1  ",
                lastHeardCreatedAtMs = 1000L,
                armedBaselineMessageId = "   ",
                armedBaselineCreatedAtMs = 900L,
                updatedAtMs = 1500L,
            ),
        )
        assertTrue(storeB.changes.value > beforeChange)

        val restoredA = storeB.getReplyCursor(" $keyA ")
        assertNotNull(restoredA)
        assertEquals("msg_1", restoredA?.lastHeardMessageId)
        assertEquals(1000L, restoredA?.lastHeardCreatedAtMs)
        assertNull(restoredA?.armedBaselineMessageId)
        assertEquals(900L, restoredA?.armedBaselineCreatedAtMs)
        assertEquals(1500L, restoredA?.updatedAtMs)

        storeA.saveReplyCursor(
            keyB,
            SessionReplyCursor(
                lastHeardMessageId = "msg_2",
                lastHeardCreatedAtMs = 2000L,
                armedBaselineMessageId = "base_2",
                armedBaselineCreatedAtMs = 1800L,
                updatedAtMs = 2500L,
            ),
        )

        val afterSaveB = storeB.changes.value
        storeA.clearReplyCursor(keyA)
        assertTrue(storeB.changes.value > afterSaveB)
        assertNull(storeB.getReplyCursor(keyA))
        assertEquals("msg_2", storeB.getReplyCursor(keyB)?.lastHeardMessageId)

        // Clearing an absent key does not emit change
        val afterClearA = storeB.changes.value
        storeA.clearReplyCursor(keyA)
        assertEquals(afterClearA, storeB.changes.value)
    }

    @Test
    fun `migrateReplyCursor moves cursor across keys and preserves newer destination cursor`() {
        val sharedPrefs = InMemorySharedPreferences()
        val storeA = TokenStore(sharedPrefs)
        val storeB = TokenStore(sharedPrefs)
        val oldKey = TokenStore.sessionKeyFor("srv1", PinnedSession("child_1", "", "Child"))
        val newKey = TokenStore.sessionKeyFor("srv1", PinnedSession("child_1", "/repo/main", "Child"))

        assertFalse(storeA.migrateReplyCursor(oldKey, newKey))
        assertFalse(storeA.migrateReplyCursor(oldKey, oldKey))
        assertFalse(storeA.migrateReplyCursor("  ", newKey))

        storeA.saveReplyCursor(
            oldKey,
            SessionReplyCursor(
                lastHeardMessageId = "msg_old",
                lastHeardCreatedAtMs = 100L,
                armedBaselineMessageId = "base_old",
                armedBaselineCreatedAtMs = 50L,
                updatedAtMs = 150L,
            ),
        )
        assertTrue(storeA.migrateReplyCursor(" $oldKey ", " $newKey "))
        assertNull(storeB.getReplyCursor(oldKey))
        assertEquals("msg_old", storeB.getReplyCursor(newKey)?.lastHeardMessageId)

        // Older source replaces nothing when destination cursor is newer
        storeA.saveReplyCursor(
            newKey,
            SessionReplyCursor(
                lastHeardMessageId = "msg_newer",
                lastHeardCreatedAtMs = 300L,
                updatedAtMs = 350L,
            ),
        )
        storeA.saveReplyCursor(
            oldKey,
            SessionReplyCursor(
                lastHeardMessageId = "msg_stale",
                lastHeardCreatedAtMs = 200L,
                updatedAtMs = 250L,
            ),
        )
        assertTrue(storeA.migrateReplyCursor(oldKey, newKey))
        assertNull(storeB.getReplyCursor(oldKey))
        assertEquals("msg_newer", storeB.getReplyCursor(newKey)?.lastHeardMessageId)

        // Newer source overwrites older destination cursor
        storeA.saveReplyCursor(
            oldKey,
            SessionReplyCursor(
                lastHeardMessageId = "msg_newest",
                lastHeardCreatedAtMs = 400L,
                updatedAtMs = 450L,
            ),
        )
        assertTrue(storeA.migrateReplyCursor(oldKey, newKey))
        assertNull(storeB.getReplyCursor(oldKey))
        assertEquals("msg_newest", storeB.getReplyCursor(newKey)?.lastHeardMessageId)
    }

    @Test
    fun `saveReplyCursor caps stored cursors at 200 by evicting oldest updatedAtMs entries`() {
        val sharedPrefs = InMemorySharedPreferences()
        val storeA = TokenStore(sharedPrefs)
        val storeB = TokenStore(sharedPrefs)

        for (i in 1..205) {
            storeA.saveReplyCursor(
                "srv1|ses_$i|/repo",
                SessionReplyCursor(
                    lastHeardMessageId = "msg_$i",
                    lastHeardCreatedAtMs = i.toLong() * 10L,
                    updatedAtMs = i.toLong() * 100L,
                ),
            )
        }

        for (i in 1..5) {
            assertNull("Expected oldest entry $i to be evicted", storeB.getReplyCursor("srv1|ses_$i|/repo"))
        }
        for (i in 6..205) {
            assertEquals("msg_$i", storeB.getReplyCursor("srv1|ses_$i|/repo")?.lastHeardMessageId)
        }

        // Updating an existing entry at capacity keeps all 200 entries
        storeA.saveReplyCursor(
            "srv1|ses_6|/repo",
            SessionReplyCursor(
                lastHeardMessageId = "msg_6_updated",
                lastHeardCreatedAtMs = 60L,
                updatedAtMs = 99999L,
            ),
        )
        assertEquals("msg_6_updated", storeB.getReplyCursor("srv1|ses_6|/repo")?.lastHeardMessageId)
        assertEquals("msg_7", storeB.getReplyCursor("srv1|ses_7|/repo")?.lastHeardMessageId)
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
