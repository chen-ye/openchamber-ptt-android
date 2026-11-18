package com.openchamber.ptt.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.openchamber.ptt.R
import com.openchamber.ptt.session.ServerSessionsResult
import com.openchamber.ptt.session.SessionSummary
import com.openchamber.ptt.session.filterSessionTree
import com.openchamber.ptt.session.findRootIdForSession
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.ServerProfile
import java.util.Locale

private val booleanMapSaver = listSaver<Map<String, Boolean>, String>(
    save = { map ->
        map.entries.map { (k, v) -> "${if (v) "1" else "0"}:$k" }
    },
    restore = { entries ->
        entries.mapNotNull { entry ->
            val colonIdx = entry.indexOf(':')
            if (colonIdx <= 0) {
                null
            } else {
                val flag = entry.substring(0, colonIdx) == "1"
                val key = entry.substring(colonIdx + 1)
                key to flag
            }
        }.toMap()
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionPickerSheet(
    serverGroups: List<ServerSessionsResult>,
    activeServerId: String?,
    pinnedSession: PinnedSession?,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onSelect: (ServerProfile, SessionSummary) -> Unit,
    onSignInServer: ((ServerProfile) -> Unit)? = null,
    onAddServer: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var selectedServerFilterId by rememberSaveable { mutableStateOf<String?>(null) }
    val normalizedSearchQuery = remember(searchQuery) {
        searchQuery.trim().lowercase(Locale.ROOT)
    }
    var manualExpanded by rememberSaveable(stateSaver = booleanMapSaver) {
        mutableStateOf(emptyMap())
    }
    var searchToggleOverrides by rememberSaveable(normalizedSearchQuery, stateSaver = booleanMapSaver) {
        mutableStateOf(emptyMap())
    }

    val activeServerFilterId = remember(serverGroups, selectedServerFilterId) {
        selectedServerFilterId?.takeIf { id ->
            serverGroups.any { it.server.id == id }
        }
    }
    val visibleGroups = remember(serverGroups, activeServerFilterId) {
        if (activeServerFilterId == null) {
            serverGroups
        } else {
            serverGroups.filter { it.server.id == activeServerFilterId }
        }
    }
    val treeByServerId = remember(serverGroups) {
        serverGroups.associate { it.server.id to it.roots }
    }
    val totalRootCount = remember(treeByServerId) {
        treeByServerId.values.sumOf { it.size }
    }
    val totalUnreadCount = remember(treeByServerId) {
        treeByServerId.values.sumOf { roots -> roots.count { it.root.isUnread } }
    }
    val scopedUnreadCount = remember(visibleGroups, treeByServerId) {
        visibleGroups.sumOf { group ->
            treeByServerId[group.server.id].orEmpty().count { it.root.isUnread }
        }
    }
    val filteredTreeByServerId = remember(visibleGroups, treeByServerId, normalizedSearchQuery, unreadOnly) {
        visibleGroups.associate { group ->
            val roots = treeByServerId[group.server.id].orEmpty()
            group.server.id to filterSessionTree(roots, normalizedSearchQuery, unreadOnly)
        }
    }
    val activeAncestorRootKey = remember(treeByServerId, activeServerId, pinnedSession?.sessionId) {
        val srvId = activeServerId ?: return@remember null
        val pinId = pinnedSession?.sessionId ?: return@remember null
        val ancestorRootId = findRootIdForSession(treeByServerId[srvId].orEmpty(), pinId)
            ?: return@remember null
        "$srvId:$ancestorRootId"
    }

    val toggleExpand: (String, Boolean) -> Unit = { rootKey, currentlyExpanded ->
        val nextExpanded = !currentlyExpanded
        if (normalizedSearchQuery.isNotEmpty()) {
            searchToggleOverrides = searchToggleOverrides + (rootKey to nextExpanded)
        } else {
            manualExpanded = manualExpanded + (rootKey to nextExpanded)
        }
    }

    val hasAnyMatchingRoots = visibleGroups.any { group ->
        filteredTreeByServerId[group.server.id].orEmpty().isNotEmpty()
    }
    val hasAnyVisibleContent = visibleGroups.any { group ->
        filteredTreeByServerId[group.server.id].orEmpty().isNotEmpty() || !group.error.isNullOrBlank()
    }
    val hasActiveFilters = normalizedSearchQuery.isNotEmpty() || unreadOnly

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
    ) {
        val focusManager = LocalFocusManager.current

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isCompactHeight = maxHeight < 420.dp
            val isCompactWidth = maxWidth < 300.dp

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = if (isCompactHeight) 12.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 8.dp else 12.dp),
            ) {
                if (!isCompactHeight) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        BottomSheetDefaults.DragHandle()
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Select Session",
                        style = if (isCompactHeight || isCompactWidth) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        maxLines = if (isCompactHeight || isCompactWidth) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (onAddServer != null) {
                        TextButton(
                            onClick = {
                                onDismiss()
                                onAddServer()
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("+ Server")
                        }
                    }
                    IconButton(onClick = onRefresh, enabled = !isLoading) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = "Refresh sessions",
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = "Search name or path",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null,
                        )
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "Clear search",
                                )
                            }
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                )

                if (serverGroups.size > 1 || totalUnreadCount > 0 || unreadOnly) {
                    val tonalChipColors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary
                            .copy(alpha = 0.14f)
                            .compositeOver(MaterialTheme.colorScheme.surfaceContainerLow),
                        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                    )
                    val selectedChipBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (serverGroups.size > 1) {
                            val allSelected = activeServerFilterId == null
                            FilterChip(
                                selected = allSelected,
                                onClick = { selectedServerFilterId = null },
                                label = { Text("All ($totalRootCount)") },
                                colors = tonalChipColors,
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = allSelected,
                                    selectedBorderColor = selectedChipBorderColor,
                                    selectedBorderWidth = 1.dp,
                                ),
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                            serverGroups.forEach { group ->
                                val srvSelected = activeServerFilterId == group.server.id
                                FilterChip(
                                    selected = srvSelected,
                                    onClick = {
                                        selectedServerFilterId = if (srvSelected) {
                                            null
                                        } else {
                                            group.server.id
                                        }
                                    },
                                    label = { Text(group.server.displayLabel) },
                                    colors = tonalChipColors,
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = srvSelected,
                                        selectedBorderColor = selectedChipBorderColor,
                                        selectedBorderWidth = 1.dp,
                                    ),
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                        if (scopedUnreadCount > 0 || unreadOnly) {
                            FilterChip(
                                selected = unreadOnly,
                                onClick = { unreadOnly = !unreadOnly },
                                label = { Text("Unread ($scopedUnreadCount)") },
                                colors = tonalChipColors,
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = unreadOnly,
                                    selectedBorderColor = selectedChipBorderColor,
                                    selectedBorderWidth = 1.dp,
                                ),
                                leadingIcon = if (unreadOnly) {
                                    {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_check),
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                                        )
                                    }
                                } else {
                                    {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                        )
                                    }
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }

                if (!error.isNullOrBlank()) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (!hasAnyVisibleContent && isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp)
                            .padding(bottom = if (isCompactHeight) 8.dp else 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (!hasAnyVisibleContent) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                top = 20.dp,
                                bottom = if (isCompactHeight) 16.dp else 28.dp,
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = when {
                                hasActiveFilters -> "No matching sessions"
                                activeServerFilterId != null -> "No sessions on this server"
                                else -> "No OpenCode sessions found"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (hasActiveFilters) {
                            TextButton(
                                onClick = {
                                    searchQuery = ""
                                    unreadOnly = false
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("Clear filters")
                            }
                        } else {
                            TextButton(
                                onClick = onRefresh,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("Reload sessions")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .selectableGroup(),
                        contentPadding = PaddingValues(bottom = if (isCompactHeight) 8.dp else 24.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        visibleGroups.forEach { group ->
                            val isGroupActiveServer = group.server.id == activeServerId
                            val filteredRoots = filteredTreeByServerId[group.server.id].orEmpty()
                            if (activeServerFilterId == null && hasActiveFilters && group.error.isNullOrBlank() && filteredRoots.isEmpty()) {
                                return@forEach
                            }

                            if (serverGroups.size > 1 || !group.error.isNullOrBlank()) {
                                item(key = "hdr_${group.server.id}") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp, bottom = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = group.server.displayLabel,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (isGroupActiveServer) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = group.server.serverUrl,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (isGroupActiveServer) {
                                            Text(
                                                text = "Active",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            }

                            if (!group.error.isNullOrBlank()) {
                                item(key = "err_${group.server.id}") {
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = group.error,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.weight(1f),
                                            )
                                            if (group.isUnauthorized && onSignInServer != null) {
                                                TextButton(
                                                    onClick = {
                                                        onDismiss()
                                                        onSignInServer(group.server)
                                                    },
                                                    colors = ButtonDefaults.textButtonColors(
                                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                                    ),
                                                    modifier = Modifier.heightIn(min = 48.dp),
                                                ) {
                                                    Text("Sign in")
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (!hasActiveFilters && filteredRoots.isEmpty() && !isLoading && group.error.isNullOrBlank()) {
                                item(key = "empty_${group.server.id}") {
                                    Text(
                                        text = "No sessions on this server",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                }
                            } else if (filteredRoots.isNotEmpty()) {
                                filteredRoots.forEach { filteredRoot ->
                                    val root = filteredRoot.node.root
                                    val rootKey = "${group.server.id}:${root.id}"
                                    val selected = isGroupActiveServer && pinnedSession?.sessionId == root.id
                                    val hasChildren = filteredRoot.visibleChildren.isNotEmpty()
                                    val isExpanded = if (normalizedSearchQuery.isNotEmpty()) {
                                        searchToggleOverrides[rootKey] ?: filteredRoot.autoExpand
                                    } else {
                                        manualExpanded[rootKey] ?: (rootKey == activeAncestorRootKey)
                                    }

                                    item(key = "root_${group.server.id}_${root.id}") {
                                        SessionRootRow(
                                            root = root,
                                            selected = selected,
                                            hasChildren = hasChildren,
                                            childCount = filteredRoot.visibleChildren.size,
                                            isExpanded = isExpanded,
                                            compactTitle = isCompactHeight,
                                            onSelect = { onSelect(group.server, root) },
                                            onToggleExpand = { toggleExpand(rootKey, isExpanded) },
                                        )
                                    }

                                    if (hasChildren && isExpanded) {
                                        items(
                                            items = filteredRoot.visibleChildren,
                                            key = { childNode ->
                                                "child_${group.server.id}_${root.id}_${childNode.session.id}"
                                            },
                                        ) { childNode ->
                                            val child = childNode.session
                                            val childSelected = isGroupActiveServer && pinnedSession?.sessionId == child.id
                                            SessionChildRow(
                                                child = child,
                                                rootDirectory = root.directory,
                                                visualDepth = childNode.visualDepth,
                                                selected = childSelected,
                                                compactTitle = isCompactHeight,
                                                onSelect = { onSelect(group.server, child) },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (hasActiveFilters && !hasAnyMatchingRoots && !isLoading) {
                            item(key = "no_matching_sessions") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "No matching sessions",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = {
                                            searchQuery = ""
                                            unreadOnly = false
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text("Clear filters")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRootRow(
    root: SessionSummary,
    selected: Boolean,
    hasChildren: Boolean,
    childCount: Int,
    isExpanded: Boolean,
    compactTitle: Boolean,
    onSelect: () -> Unit,
    onToggleExpand: () -> Unit,
) {
    val baseColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f).compositeOver(baseColor)
    } else {
        baseColor
    }
    val selectionBorder = if (selected) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.38f))
    } else {
        null
    }

    Surface(
        color = containerColor,
        border = selectionBorder,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .heightIn(min = 56.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = onSelect,
                    )
                    .semantics {
                        if (root.isUnread) {
                            stateDescription = when {
                                selected && root.isUnreadWithError -> "Selected, unread, failed"
                                selected -> "Selected, unread"
                                root.isUnreadWithError -> "Not selected, unread, failed"
                                else -> "Not selected, unread"
                            }
                        }
                    }
                    .padding(
                        start = 14.dp,
                        end = if (hasChildren) 6.dp else 14.dp,
                        top = 10.dp,
                        bottom = 10.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (root.isUnread) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = root.title.ifBlank { "Untitled session" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected || root.isUnread) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (compactTitle) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (root.isUnreadWithError) {
                            Text(
                                text = "Failed · ",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = root.directory.ifBlank { root.id },
                            style = if (root.directory.isNotBlank()) {
                                MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr)
                            } else {
                                MaterialTheme.typography.bodySmall
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = if (root.directory.isNotBlank()) {
                                TextOverflow.StartEllipsis
                            } else {
                                TextOverflow.Ellipsis
                            },
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
                if (selected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (hasChildren) {
                val noun = if (childCount == 1) "subagent" else "subagents"
                val toggleAction = if (isExpanded) "collapse" else "expand"
                Box(
                    modifier = Modifier
                        .size(width = 1.dp, height = 24.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Row(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .heightIn(min = 56.dp)
                        .widthIn(min = 48.dp)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = toggleAction,
                            onClick = onToggleExpand,
                        )
                        .semantics(mergeDescendants = true) {
                            contentDescription = "$childCount $noun"
                            stateDescription = if (isExpanded) "Expanded" else "Collapsed"
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = childCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                    Icon(
                        painter = painterResource(
                            if (isExpanded) R.drawable.ic_arrow_down else R.drawable.ic_chevron_right,
                        ),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionChildRow(
    child: SessionSummary,
    rootDirectory: String,
    visualDepth: Int,
    selected: Boolean,
    compactTitle: Boolean,
    onSelect: () -> Unit,
) {
    val startIndent = if (visualDepth >= 2) 28.dp else 16.dp
    val railColor = MaterialTheme.colorScheme.outlineVariant
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val baseColor = MaterialTheme.colorScheme.surfaceContainer
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f).compositeOver(baseColor)
    } else {
        baseColor
    }
    val selectionBorder = if (selected) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.38f))
    } else {
        null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val strokeWidthPx = 1.dp.toPx()
                val x = if (isRtl) {
                    size.width - startIndent.toPx() - strokeWidthPx / 2f
                } else {
                    startIndent.toPx() + strokeWidthPx / 2f
                }
                drawLine(
                    color = railColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = strokeWidthPx,
                )
            }
            .padding(start = startIndent + 1.dp + 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = containerColor,
            border = selectionBorder,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .heightIn(min = 52.dp)
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onSelect,
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = child.title.ifBlank { "Untitled session" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (compactTitle) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val hasDistinctChildDir = child.directory.isNotBlank() && child.directory != rootDirectory
                    Text(
                        text = if (hasDistinctChildDir) {
                            child.directory
                        } else {
                            "Subagent · ${child.id.takeLast(8)}"
                        },
                        style = if (hasDistinctChildDir) {
                            MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr)
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = if (hasDistinctChildDir) {
                            TextOverflow.StartEllipsis
                        } else {
                            TextOverflow.Ellipsis
                        },
                    )
                }
                if (selected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
