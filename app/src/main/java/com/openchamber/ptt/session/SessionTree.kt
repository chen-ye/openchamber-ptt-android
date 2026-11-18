package com.openchamber.ptt.session

import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.ServerProfile
import java.util.Locale

internal data class SessionChildNode(
    val session: SessionSummary,
    val visualDepth: Int,
)

internal data class SessionRootNode(
    val root: SessionSummary,
    val children: List<SessionChildNode>,
    val latestActivityAtMs: Long,
)

internal data class FilteredSessionRootNode(
    val node: SessionRootNode,
    val visibleChildren: List<SessionChildNode>,
    val autoExpand: Boolean,
)

private val WHITESPACE_REGEX = Regex("\\s+")

internal fun buildSessionTree(sessions: List<SessionSummary>): List<SessionRootNode> {
    if (sessions.isEmpty()) return emptyList()

    val deduped = ArrayList<SessionSummary>(sessions.size)
    val byId = LinkedHashMap<String, SessionSummary>(sessions.size)
    for (raw in sessions) {
        if (byId.containsKey(raw.id)) continue
        val normalizedParentId = raw.parentId?.trim()?.takeIf { it.isNotEmpty() && it != raw.id }
        val normalized = if (normalizedParentId == raw.parentId) {
            raw
        } else {
            raw.copy(parentId = normalizedParentId)
        }
        byId[normalized.id] = normalized
        deduped.add(normalized)
    }

    val childrenByParentId = LinkedHashMap<String, MutableList<SessionSummary>>()
    for (session in deduped) {
        val parentId = session.parentId ?: continue
        if (parentId in byId) {
            childrenByParentId.getOrPut(parentId) { mutableListOf() }.add(session)
        }
    }
    val sortedChildrenByParentId = childrenByParentId.mapValues { (_, list) ->
        list.sortedByDescending { it.updatedAtMs }
    }

    val emittedIds = HashSet<String>(deduped.size)
    val rootNodes = ArrayList<SessionRootNode>()

    fun buildRootNode(rootSession: SessionSummary): SessionRootNode {
        emittedIds.add(rootSession.id)
        val visited = HashSet<String>()
        visited.add(rootSession.id)
        val flattenedChildren = ArrayList<SessionChildNode>()

        fun dfs(parentId: String, parentDirectory: String, depth: Int) {
            val siblings = sortedChildrenByParentId[parentId].orEmpty()
            for (child in siblings) {
                if (child.id in visited || child.id in emittedIds) continue
                visited.add(child.id)
                emittedIds.add(child.id)
                val resolvedDirectory = child.directory.ifBlank { parentDirectory }
                val resolvedChild = if (resolvedDirectory == child.directory) {
                    child
                } else {
                    child.copy(directory = resolvedDirectory)
                }
                flattenedChildren.add(
                    SessionChildNode(
                        session = resolvedChild,
                        visualDepth = depth.coerceAtMost(2),
                    ),
                )
                dfs(
                    parentId = resolvedChild.id,
                    parentDirectory = resolvedDirectory,
                    depth = depth + 1,
                )
            }
        }

        dfs(
            parentId = rootSession.id,
            parentDirectory = rootSession.directory,
            depth = 1,
        )

        val latestChildActivity = flattenedChildren.maxOfOrNull { it.session.updatedAtMs }
        val latestActivityAtMs = if (latestChildActivity != null) {
            maxOf(rootSession.updatedAtMs, latestChildActivity)
        } else {
            rootSession.updatedAtMs
        }

        return SessionRootNode(
            root = rootSession,
            children = flattenedChildren,
            latestActivityAtMs = latestActivityAtMs,
        )
    }

    for (session in deduped) {
        val parentId = session.parentId
        if (parentId == null || parentId !in byId) {
            if (session.id !in emittedIds) {
                rootNodes.add(buildRootNode(session))
            }
        }
    }

    for (session in deduped) {
        if (session.id !in emittedIds) {
            rootNodes.add(buildRootNode(session))
        }
    }

    return rootNodes.sortedByDescending { it.latestActivityAtMs }
}

internal fun filterSessionTree(
    roots: List<SessionRootNode>,
    query: String,
    unreadOnly: Boolean,
): List<FilteredSessionRootNode> {
    val unreadFiltered = if (unreadOnly) {
        roots.filter { it.root.isUnread }
    } else {
        roots
    }

    val trimmedQuery = query.trim()
    val tokens = if (trimmedQuery.isEmpty()) {
        emptyList()
    } else {
        trimmedQuery.lowercase(Locale.ROOT).split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
    }

    if (tokens.isEmpty()) {
        return unreadFiltered.map { root ->
            FilteredSessionRootNode(
                node = root,
                visibleChildren = root.children,
                autoExpand = false,
            )
        }
    }

    return unreadFiltered.mapNotNull { rootNode ->
        val unmatchedRootTokens = tokens.filterNot { matchesSingleToken(rootNode.root, it) }
        val rootMatches = unmatchedRootTokens.isEmpty()
        val matchingChildren = rootNode.children.filter { childNode ->
            val child = childNode.session
            if (!rootMatches) {
                unmatchedRootTokens.all { matchesSingleToken(child, it) }
            } else {
                matchesTokens(child, tokens) &&
                    childHasDistinctTokenMatch(child, rootNode.root.directory, tokens)
            }
        }
        if (!rootMatches && matchingChildren.isEmpty()) {
            null
        } else {
            FilteredSessionRootNode(
                node = rootNode,
                visibleChildren = matchingChildren.ifEmpty { rootNode.children },
                autoExpand = matchingChildren.isNotEmpty(),
            )
        }
    }
}

private fun childHasDistinctTokenMatch(
    child: SessionSummary,
    rootDirectory: String,
    tokens: List<String>,
): Boolean {
    val titleLower = child.title.lowercase(Locale.ROOT)
    val idLower = child.id.lowercase(Locale.ROOT)
    val childDirLower = child.directory.lowercase(Locale.ROOT)
    val rootDirLower = rootDirectory.lowercase(Locale.ROOT)
    val hasDistinctDir = childDirLower.isNotEmpty() && childDirLower != rootDirLower
    return tokens.any { token ->
        titleLower.contains(token) ||
            idLower.contains(token) ||
            (hasDistinctDir && childDirLower.contains(token))
    }
}

private fun matchesSingleToken(
    session: SessionSummary,
    token: String,
): Boolean {
    return session.title.lowercase(Locale.ROOT).contains(token) ||
        session.directory.lowercase(Locale.ROOT).contains(token) ||
        session.id.lowercase(Locale.ROOT).contains(token)
}

private fun matchesTokens(
    session: SessionSummary,
    tokens: List<String>,
): Boolean = tokens.all { matchesSingleToken(session, it) }

internal fun resolvePinnedSession(
    roots: List<SessionRootNode>,
    currentPinned: PinnedSession,
): PinnedSession? {
    for (rootNode in roots) {
        if (rootNode.root.id == currentPinned.sessionId) {
            return PinnedSession(
                sessionId = rootNode.root.id,
                directory = rootNode.root.directory.ifBlank { currentPinned.directory },
                title = rootNode.root.title,
            )
        }
        val childMatch = rootNode.children.firstOrNull { it.session.id == currentPinned.sessionId }
        if (childMatch != null) {
            return PinnedSession(
                sessionId = childMatch.session.id,
                directory = childMatch.session.directory.ifBlank { currentPinned.directory },
                title = childMatch.session.title,
            )
        }
    }
    return null
}

internal fun findRootIdForSession(
    roots: List<SessionRootNode>,
    sessionId: String,
): String? {
    if (sessionId.isBlank()) return null
    if (roots.any { it.root.id == sessionId }) return null
    return roots.firstOrNull { rootNode ->
        rootNode.children.any { it.session.id == sessionId }
    }?.root?.id
}

internal fun resolveAutoPinCandidate(roots: List<SessionRootNode>): SessionSummary? =
    roots.singleOrNull()?.root?.takeIf { it.parentId.isNullOrBlank() }

internal fun mergeRefreshedServerGroups(
    previousGroups: List<ServerSessionsResult>,
    freshResults: List<ServerSessionsResult>,
    updatedServers: List<ServerProfile>,
): List<ServerSessionsResult> {
    val previousByServerId = previousGroups.associateBy { it.server.id }
    val updatedServerById = updatedServers.associateBy { it.id }

    return freshResults.mapNotNull { res ->
        val freshServer = updatedServerById[res.server.id] ?: return@mapNotNull null
        if (!freshServer.isSignedIn) {
            return@mapNotNull res.copy(
                server = freshServer,
                sessions = emptyList(),
                roots = emptyList(),
            )
        }
        val shouldRetainCached = !res.error.isNullOrBlank() &&
            !res.isUnauthorized &&
            res.sessions.isEmpty()
        if (shouldRetainCached) {
            val cachedGroup = previousByServerId[res.server.id]
            res.copy(
                server = freshServer,
                sessions = cachedGroup?.sessions.orEmpty(),
                roots = cachedGroup?.roots.orEmpty(),
            )
        } else {
            res.copy(server = freshServer)
        }
    }
}
