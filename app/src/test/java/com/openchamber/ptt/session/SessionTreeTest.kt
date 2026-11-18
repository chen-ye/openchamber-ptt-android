package com.openchamber.ptt.session

import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTreeTest {

    @Test
    fun `filterSessionTree defaults to collapsed with all children preserved`() {
        val roots = buildSessionTree(
            listOf(
                SessionSummary(id = "root_1", title = "Main Session", directory = "/workspace/app", updatedAtMs = 100L),
                SessionSummary(id = "child_1", title = "Subagent A", directory = "/workspace/app", parentId = "root_1", updatedAtMs = 200L),
                SessionSummary(id = "child_2", title = "Subagent B", directory = "/workspace/app", parentId = "root_1", updatedAtMs = 150L),
            ),
        )

        val filtered = filterSessionTree(roots, query = "   ", unreadOnly = false)
        assertEquals(1, filtered.size)
        assertFalse(filtered[0].autoExpand)
        assertEquals(listOf("child_1", "child_2"), filtered[0].visibleChildren.map { it.session.id })
    }

    @Test
    fun `buildSessionTree flattens descendants with visualDepth capped at 2 and preserves DFS sibling order`() {
        val roots = buildSessionTree(
            listOf(
                SessionSummary(id = "r1", title = "Root", directory = "/repo/core", updatedAtMs = 100L),
                SessionSummary(id = "c1", title = "Older Child", directory = "/repo/core", parentId = "r1", updatedAtMs = 150L),
                SessionSummary(id = "c2", title = "Newer Child", directory = "/repo/core", parentId = "r1", updatedAtMs = 300L),
                SessionSummary(id = "g1", title = "Grandchild of Older", directory = "/repo/core", parentId = "c1", updatedAtMs = 500L),
                SessionSummary(id = "gg1", title = "Great-Grandchild", directory = "/repo/core", parentId = "g1", updatedAtMs = 450L),
            ),
        )

        assertEquals(1, roots.size)
        val rootNode = roots[0]
        assertEquals(500L, rootNode.latestActivityAtMs)
        assertEquals(
            listOf(
                "c2" to 1,
                "c1" to 1,
                "g1" to 2,
                "gg1" to 2,
            ),
            rootNode.children.map { it.session.id to it.visualDepth },
        )
    }

    @Test
    fun `buildSessionTree inherits parent directory onto blank descendant directories`() {
        val roots = buildSessionTree(
            listOf(
                SessionSummary(id = "r1", title = "Root", directory = "/workspace/project-a", updatedAtMs = 100L),
                SessionSummary(id = "c_blank", title = "Child Blank", directory = "   ", parentId = "r1", updatedAtMs = 90L),
                SessionSummary(id = "g_blank", title = "Grandchild Blank", directory = "", parentId = "c_blank", updatedAtMs = 80L),
                SessionSummary(id = "c_explicit", title = "Child Explicit", directory = "/workspace/project-b", parentId = "r1", updatedAtMs = 70L),
                SessionSummary(id = "g_from_explicit", title = "Grandchild Explicit Parent", directory = "", parentId = "c_explicit", updatedAtMs = 60L),
            ),
        )

        val byId = roots[0].children.associateBy { it.session.id }
        assertEquals("/workspace/project-a", byId.getValue("c_blank").session.directory)
        assertEquals("/workspace/project-a", byId.getValue("g_blank").session.directory)
        assertEquals("/workspace/project-b", byId.getValue("c_explicit").session.directory)
        assertEquals("/workspace/project-b", byId.getValue("g_from_explicit").session.directory)
    }

    @Test
    fun `buildSessionTree deduplicates by id preserving first occurrence and promotes orphans`() {
        val roots = buildSessionTree(
            listOf(
                SessionSummary(id = "orphan_1", title = "First Orphan", directory = "/repo/x", parentId = "missing_parent", updatedAtMs = 200L),
                SessionSummary(id = "orphan_1", title = "Duplicate Orphan", directory = "/repo/y", parentId = "missing_parent", updatedAtMs = 999L),
                SessionSummary(id = "child_of_orphan", title = "Nested", directory = "", parentId = "orphan_1", updatedAtMs = 250L),
                SessionSummary(id = "blank_parent", title = "Blank Parent", directory = "/repo/z", parentId = "   ", updatedAtMs = 100L),
            ),
        )

        assertEquals(2, roots.size)
        assertEquals("orphan_1", roots[0].root.id)
        assertEquals("First Orphan", roots[0].root.title)
        assertEquals(250L, roots[0].latestActivityAtMs)
        assertEquals(listOf("child_of_orphan"), roots[0].children.map { it.session.id })
        assertEquals("/repo/x", roots[0].children[0].session.directory)

        assertEquals("blank_parent", roots[1].root.id)
        assertNull(roots[1].root.parentId)
    }

    @Test
    fun `buildSessionTree recovers from self-cycles and disconnected cycles without dropping sessions`() {
        val selfCycle = buildSessionTree(
            listOf(
                SessionSummary(id = "self_1", title = "Self Loop", directory = "/repo/self", parentId = "self_1", updatedAtMs = 100L),
                SessionSummary(id = "self_child", title = "Self Child", directory = "", parentId = "self_1", updatedAtMs = 150L),
            ),
        )
        assertEquals(1, selfCycle.size)
        assertNull(selfCycle[0].root.parentId)
        assertEquals(listOf("self_child"), selfCycle[0].children.map { it.session.id })

        val twoCycle = buildSessionTree(
            listOf(
                SessionSummary(id = "a", title = "A", directory = "/repo/cycle", parentId = "b", updatedAtMs = 100L),
                SessionSummary(id = "b", title = "B", directory = "", parentId = "a", updatedAtMs = 200L),
            ),
        )
        assertEquals(1, twoCycle.size)
        assertEquals("a", twoCycle[0].root.id)
        assertEquals(listOf("b"), twoCycle[0].children.map { it.session.id })
        assertEquals("/repo/cycle", twoCycle[0].children[0].session.directory)

        val threeNodeCycleTail = buildSessionTree(
            listOf(
                SessionSummary(id = "a", title = "A", directory = "/repo/c", parentId = "b", updatedAtMs = 100L),
                SessionSummary(id = "b", title = "B", directory = "", parentId = "a", updatedAtMs = 200L),
                SessionSummary(id = "c", title = "C", directory = "", parentId = "b", updatedAtMs = 300L),
            ),
        )
        val emittedFromCycle = threeNodeCycleTail.flatMap { listOf(it.root.id) + it.children.map { ch -> ch.session.id } }
        assertEquals(setOf("a", "b", "c"), emittedFromCycle.toSet())
        assertEquals(3, emittedFromCycle.size)

        val tailFirstCycle = buildSessionTree(
            listOf(
                SessionSummary(id = "c", title = "C", directory = "/repo/tail", parentId = "b", updatedAtMs = 300L),
                SessionSummary(id = "a", title = "A", directory = "/repo/a", parentId = "b", updatedAtMs = 100L),
                SessionSummary(id = "b", title = "B", directory = "", parentId = "a", updatedAtMs = 200L),
            ),
        )
        val emittedTailFirst = tailFirstCycle.flatMap { listOf(it.root.id) + it.children.map { ch -> ch.session.id } }
        assertEquals(setOf("a", "b", "c"), emittedTailFirst.toSet())
        assertEquals(3, emittedTailFirst.size)
    }

    @Test
    fun `buildSessionTree sorts roots by max activity across root and children`() {
        val roots = buildSessionTree(
            listOf(
                SessionSummary(id = "root_older_active_child", title = "Root Old", directory = "/a", updatedAtMs = 100L),
                SessionSummary(id = "child_hot", title = "Hot Child", directory = "/a", parentId = "root_older_active_child", updatedAtMs = 900L),
                SessionSummary(id = "root_newer_alone", title = "Root Mid", directory = "/b", updatedAtMs = 500L),
                SessionSummary(id = "root_tie_1", title = "Tie 1", directory = "/c", updatedAtMs = 300L),
                SessionSummary(id = "root_tie_2", title = "Tie 2", directory = "/d", updatedAtMs = 300L),
            ),
        )

        assertEquals(
            listOf("root_older_active_child", "root_newer_alone", "root_tie_1", "root_tie_2"),
            roots.map { it.root.id },
        )
        assertEquals(900L, roots[0].latestActivityAtMs)
        assertEquals(500L, roots[1].latestActivityAtMs)
    }

    @Test
    fun `filterSessionTree unreadOnly filters at root level and preserves children`() {
        val roots = buildSessionTree(
            listOf(
                SessionSummary(id = "unread_root", title = "Unread Parent", directory = "/repo/u", updatedAtMs = 200L, isUnread = true),
                SessionSummary(id = "unread_child", title = "Subagent", directory = "/repo/u", parentId = "unread_root", updatedAtMs = 210L, isUnread = false),
                SessionSummary(id = "read_root", title = "Read Parent", directory = "/repo/r", updatedAtMs = 300L, isUnread = false),
                SessionSummary(id = "read_child", title = "Subagent 2", directory = "/repo/r", parentId = "read_root", updatedAtMs = 310L, isUnread = false),
            ),
        )

        val unreadOnly = filterSessionTree(roots, query = "", unreadOnly = true)
        assertEquals(1, unreadOnly.size)
        assertEquals("unread_root", unreadOnly[0].node.root.id)
        assertFalse(unreadOnly[0].autoExpand)
        assertEquals(listOf("unread_child"), unreadOnly[0].visibleChildren.map { it.session.id })
    }

    @Test
    fun `filterSessionTree matches title id and directory and sets autoExpand appropriately`() {
        val roots = buildSessionTree(
            listOf(
                SessionSummary(id = "ses_alpha_1", title = "Build Voice Pipeline", directory = "/home/dev/audio-app", updatedAtMs = 400L),
                SessionSummary(id = "ses_alpha_child", title = "Research Codecs", directory = "/home/dev/audio-app", parentId = "ses_alpha_1", updatedAtMs = 390L),
                SessionSummary(id = "ses_beta_1", title = "Refactor Parser", directory = "/home/dev/compiler-tools", updatedAtMs = 300L),
                SessionSummary(id = "ses_beta_child", title = "Benchmark Lexer", directory = "/home/dev/compiler-tools", parentId = "ses_beta_1", updatedAtMs = 290L),
                SessionSummary(id = "ses_beta_grandchild", title = "Profile Tokenizer", directory = "/home/dev/compiler-tools", parentId = "ses_beta_child", updatedAtMs = 280L),
            ),
        )

        // Parent match only (title + directory tokens across parent) -> autoExpand = false, all children kept
        val parentMatch = filterSessionTree(roots, query = "voice audio-app", unreadOnly = false)
        assertEquals(1, parentMatch.size)
        assertEquals("ses_alpha_1", parentMatch[0].node.root.id)
        assertFalse(parentMatch[0].autoExpand)
        assertEquals(listOf("ses_alpha_child"), parentMatch[0].visibleChildren.map { it.session.id })

        // Child match only (title) -> autoExpand = true, only matching child visible
        val childMatch = filterSessionTree(roots, query = "tokenizer", unreadOnly = false)
        assertEquals(1, childMatch.size)
        assertEquals("ses_beta_1", childMatch[0].node.root.id)
        assertTrue(childMatch[0].autoExpand)
        assertEquals(listOf("ses_beta_grandchild"), childMatch[0].visibleChildren.map { it.session.id })

        // Match by session id
        val idMatch = filterSessionTree(roots, query = "SES_ALPHA_CHILD", unreadOnly = false)
        assertEquals(1, idMatch.size)
        assertTrue(idMatch[0].autoExpand)
        assertEquals(listOf("ses_alpha_child"), idMatch[0].visibleChildren.map { it.session.id })

        // Match by shared directory -> parent matches and stays collapsed (autoExpand = false) while children remain expandable
        val dirMatch = filterSessionTree(roots, query = "compiler-tools", unreadOnly = false)
        assertEquals(1, dirMatch.size)
        assertFalse(dirMatch[0].autoExpand)
        assertEquals(listOf("ses_beta_child", "ses_beta_grandchild"), dirMatch[0].visibleChildren.map { it.session.id })

        // Match by directory + child title -> autoExpand = true revealing only matching child
        val dirPlusChildMatch = filterSessionTree(roots, query = "compiler-tools tokenizer", unreadOnly = false)
        assertEquals(1, dirPlusChildMatch.size)
        assertTrue(dirPlusChildMatch[0].autoExpand)
        assertEquals(listOf("ses_beta_grandchild"), dirPlusChildMatch[0].visibleChildren.map { it.session.id })

        // Cross-node query spanning parent title + child title -> expands matching child
        val parentTitlePlusChildTitle = filterSessionTree(roots, query = "voice codecs", unreadOnly = false)
        assertEquals(1, parentTitlePlusChildTitle.size)
        assertEquals("ses_alpha_1", parentTitlePlusChildTitle[0].node.root.id)
        assertTrue(parentTitlePlusChildTitle[0].autoExpand)
        assertEquals(listOf("ses_alpha_child"), parentTitlePlusChildTitle[0].visibleChildren.map { it.session.id })
    }

    @Test
    fun `filterSessionTree narrows visibleChildren when rootMatches and only subset of children match`() {
        val roots = buildSessionTree(
            listOf(
                SessionSummary(id = "root_audio", title = "Build Audio App", directory = "/repo/main", updatedAtMs = 500L),
                SessionSummary(id = "sub_match", title = "Audio decoder subagent", directory = "/repo/main", parentId = "root_audio", updatedAtMs = 450L),
                SessionSummary(id = "sub_other", title = "Network client subagent", directory = "/repo/main", parentId = "root_audio", updatedAtMs = 440L),
            ),
        )

        val filtered = filterSessionTree(roots, query = "audio", unreadOnly = false)
        assertEquals(1, filtered.size)
        assertTrue(filtered[0].autoExpand)
        assertEquals(listOf("sub_match"), filtered[0].visibleChildren.map { it.session.id })
    }

    @Test
    fun `filterSessionTree combines unreadOnly with search query`() {
        val roots = buildSessionTree(
            listOf(
                SessionSummary(id = "u_root", title = "Voice Inbox", directory = "/repo/voice", updatedAtMs = 400L, isUnread = true),
                SessionSummary(id = "u_child", title = "Draft Summarizer", directory = "/repo/voice", parentId = "u_root", updatedAtMs = 410L, isUnread = false),
                SessionSummary(id = "r_root", title = "Voice Read", directory = "/repo/voice", updatedAtMs = 500L, isUnread = false),
                SessionSummary(id = "r_child", title = "Draft Summarizer", directory = "/repo/voice", parentId = "r_root", updatedAtMs = 510L, isUnread = false),
            ),
        )

        val combined = filterSessionTree(roots, query = "summarizer", unreadOnly = true)
        assertEquals(1, combined.size)
        assertEquals("u_root", combined[0].node.root.id)
        assertTrue(combined[0].autoExpand)
        assertEquals(listOf("u_child"), combined[0].visibleChildren.map { it.session.id })
    }

    @Test
    fun `resolvePinnedSession preserves inherited subagent directory and falls back when parent archived`() {
        val roots = buildSessionTree(
            listOf(
                SessionSummary(id = "root_1", title = "Main Session", directory = "/repo/main", updatedAtMs = 200L),
                SessionSummary(id = "child_1", title = "Updated Subagent", directory = "", parentId = "root_1", updatedAtMs = 250L),
            ),
        )
        val currentSubagentPin = PinnedSession(
            sessionId = "child_1",
            directory = "/repo/main",
            title = "Old Subagent",
        )

        val resolved = resolvePinnedSession(roots, currentSubagentPin)
        assertEquals(
            PinnedSession(
                sessionId = "child_1",
                directory = "/repo/main",
                title = "Updated Subagent",
            ),
            resolved,
        )

        val orphanRoots = buildSessionTree(
            listOf(
                SessionSummary(id = "child_1", title = "Orphaned Subagent", directory = "", parentId = "archived_root"),
            ),
        )
        val preservedFallback = resolvePinnedSession(orphanRoots, currentSubagentPin)
        assertEquals(
            PinnedSession(
                sessionId = "child_1",
                directory = "/repo/main",
                title = "Orphaned Subagent",
            ),
            preservedFallback,
        )
    }

    @Test
    fun `findRootIdForSession returns root id for child and grandchild and null for root or missing`() {
        val roots = buildSessionTree(
            listOf(
                SessionSummary(id = "root_1", title = "Root 1", directory = "/repo/1", updatedAtMs = 100L),
                SessionSummary(id = "child_1", title = "Child 1", directory = "/repo/1", parentId = "root_1", updatedAtMs = 90L),
                SessionSummary(id = "grand_1", title = "Grand 1", directory = "/repo/1", parentId = "child_1", updatedAtMs = 80L),
                SessionSummary(id = "root_2", title = "Root 2", directory = "/repo/2", updatedAtMs = 50L),
            ),
        )

        assertEquals("root_1", findRootIdForSession(roots, "child_1"))
        assertEquals("root_1", findRootIdForSession(roots, "grand_1"))
        assertNull(findRootIdForSession(roots, "root_1"))
        assertNull(findRootIdForSession(roots, "root_2"))
        assertNull(findRootIdForSession(roots, "nonexistent"))
        assertNull(findRootIdForSession(roots, "   "))
    }

    @Test
    fun `resolveAutoPinCandidate pins sole root and never auto-pins subagents or ambiguous roots`() {
        val singleRootWithSubagents = buildSessionTree(
            listOf(
                SessionSummary(id = "root_1", title = "Root 1", directory = "/repo/1"),
                SessionSummary(id = "sub_1", title = "Sub 1", directory = "/repo/1", parentId = "root_1"),
                SessionSummary(id = "sub_2", title = "Sub 2", directory = "/repo/1", parentId = "sub_1"),
            ),
        )
        assertEquals("root_1", resolveAutoPinCandidate(singleRootWithSubagents)?.id)

        val orphanSubagentOnly = buildSessionTree(
            listOf(
                SessionSummary(id = "orphan_sub", title = "Orphan Sub", directory = "/repo/1", parentId = "archived_root"),
            ),
        )
        assertNull(resolveAutoPinCandidate(orphanSubagentOnly))

        val rootPlusOrphanSub = buildSessionTree(
            listOf(
                SessionSummary(id = "root_1", title = "Root 1", directory = "/repo/1"),
                SessionSummary(id = "orphan_sub", title = "Orphan Sub", directory = "/repo/1", parentId = "archived_root"),
            ),
        )
        assertNull(resolveAutoPinCandidate(rootPlusOrphanSub))

        val twoRoots = buildSessionTree(
            listOf(
                SessionSummary(id = "root_1", title = "Root 1", directory = "/repo/1"),
                SessionSummary(id = "root_2", title = "Root 2", directory = "/repo/2"),
            ),
        )
        assertNull(resolveAutoPinCandidate(twoRoots))

        assertNull(resolveAutoPinCandidate(emptyList()))
    }

    @Test
    fun `mergeRefreshedServerGroups retains cached sessions on transient non-401 error and clears on 401`() {
        val oldProfileA = ServerProfile(id = "srv_a", label = "Server A", serverUrl = "https://a.example.test", token = "tok_a")
        val oldProfileB = ServerProfile(id = "srv_b", label = "Server B", serverUrl = "https://b.example.test", token = "tok_b")
        val oldProfileC = ServerProfile(id = "srv_c", label = "Server C", serverUrl = "https://c.example.test", token = "tok_c")

        val cachedA = listOf(SessionSummary(id = "ses_a1", title = "Cached A", directory = "/repo/a", updatedAtMs = 100L))
        val cachedB = listOf(SessionSummary(id = "ses_b1", title = "Cached B", directory = "/repo/b", updatedAtMs = 200L))
        val cachedC = listOf(SessionSummary(id = "ses_c1", title = "Cached C", directory = "/repo/c", updatedAtMs = 300L))

        val previousGroups = listOf(
            ServerSessionsResult(server = oldProfileA, sessions = cachedA),
            ServerSessionsResult(server = oldProfileB, sessions = cachedB),
            ServerSessionsResult(server = oldProfileC, sessions = cachedC),
        )

        val updatedProfileA = oldProfileA.copy(
            label = "Server A Renamed",
            lastPinnedSession = PinnedSession("ses_a1", "/repo/a", "Cached A"),
        )
        val updatedProfileB = oldProfileB.copy(token = null)
        val updatedProfileC = oldProfileC.copy(label = "Server C Updated")

        val freshResults = listOf(
            ServerSessionsResult(
                server = oldProfileA,
                sessions = emptyList(),
                error = "Network timeout",
                isUnauthorized = false,
            ),
            ServerSessionsResult(
                server = oldProfileB,
                sessions = emptyList(),
                error = "Session expired — sign in again",
                isUnauthorized = true,
            ),
            ServerSessionsResult(
                server = oldProfileC,
                sessions = emptyList(),
                error = null,
                isUnauthorized = false,
            ),
        )

        val merged = mergeRefreshedServerGroups(
            previousGroups = previousGroups,
            freshResults = freshResults,
            updatedServers = listOf(updatedProfileA, updatedProfileB, updatedProfileC),
        )

        assertEquals(3, merged.size)

        // Server A: transient error retains cached sessions and preserves error banner + updated profile
        assertEquals(updatedProfileA, merged[0].server)
        assertEquals(cachedA, merged[0].sessions)
        assertEquals("Network timeout", merged[0].error)
        assertFalse(merged[0].isUnauthorized)

        // Server B: 401 clears sessions and preserves unauthorized flag + updated profile
        assertEquals(updatedProfileB, merged[1].server)
        assertTrue(merged[1].sessions.isEmpty())
        assertEquals("Session expired — sign in again", merged[1].error)
        assertTrue(merged[1].isUnauthorized)

        // Server C: clean empty refresh does not keep stale cached sessions
        assertEquals(updatedProfileC, merged[2].server)
        assertTrue(merged[2].sessions.isEmpty())
        assertTrue(merged[2].roots.isEmpty())
        assertNull(merged[2].error)

        // Removed server absent from updatedServers is dropped; signed-out server drops cached sessions even on transient error
        val mergedAfterRemovalAndSignOut = mergeRefreshedServerGroups(
            previousGroups = previousGroups,
            freshResults = freshResults,
            updatedServers = listOf(updatedProfileB),
        )
        assertEquals(1, mergedAfterRemovalAndSignOut.size)
        assertEquals("srv_b", mergedAfterRemovalAndSignOut[0].server.id)
        assertTrue(mergedAfterRemovalAndSignOut[0].sessions.isEmpty())
        assertTrue(mergedAfterRemovalAndSignOut[0].roots.isEmpty())
    }
}
