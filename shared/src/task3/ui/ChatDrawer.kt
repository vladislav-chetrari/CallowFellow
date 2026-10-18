package task3.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import task2.domain.EngineStatus
import task3.domain.ChatSummary
import task3.presentation.Task3Intent
import task3.presentation.Task3UiState

/**
 * Chat list drawer (Material 3 modal drawer). The whole UI state comes from
 * Task3UiState, the intents go back through [send] — no local chat logic here.
 */
@Composable
fun ChatDrawerContent(
    ui: Task3UiState,
    send: (Task3Intent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Switching chats mid-answer would put the reply into the wrong conversation.
    val busy = ui.llmStatus is EngineStatus.Generating

    ModalDrawerSheet(modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Чаты", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${ui.chats.size} ${chatWord(ui.chats.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { send(Task3Intent.ChatCreateRequested) },
                enabled = !busy,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Новый чат")
            }
        }
        HorizontalDivider()

        if (ui.chats.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Чатов пока нет — нажми + сверху",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(ui.chats, key = { it.id }) { chat ->
                    ChatDrawerRow(
                        chat = chat,
                        selected = chat.id == ui.activeChatId,
                        busy = busy,
                        send = send,
                    )
                }
            }
        }
    }

    if (ui.renamingChatId != null) {
        AlertDialog(
            onDismissRequest = { send(Task3Intent.ChatRenameDismissed) },
            title = { Text("Переименовать чат") },
            text = {
                OutlinedTextField(
                    value = ui.renameInput,
                    onValueChange = { send(Task3Intent.ChatRenameInputChanged(it)) },
                    placeholder = { Text("Название") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { send(Task3Intent.ChatRenameConfirmed) }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { send(Task3Intent.ChatRenameDismissed) }) { Text("Отмена") }
            },
        )
    }

    if (ui.deletingChatId != null) {
        val chat = ui.chats.firstOrNull { it.id == ui.deletingChatId }
        AlertDialog(
            onDismissRequest = { send(Task3Intent.ChatDeleteDismissed) },
            title = { Text("Удалить чат?") },
            text = {
                Text(
                    "Чат «${chat?.title?.ifBlank { "Без названия" } ?: "Без названия"}» " +
                        "и его переписка будут удалены без возможности восстановления.",
                )
            },
            confirmButton = {
                TextButton(onClick = { send(Task3Intent.ChatDeleteConfirmed) }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { send(Task3Intent.ChatDeleteDismissed) }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun ChatDrawerRow(
    chat: ChatSummary,
    selected: Boolean,
    busy: Boolean,
    send: (Task3Intent) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Selecting another chat mid-answer would put the reply into the wrong
    // conversation; renaming is always safe, deleting is not.
    val selectable = !busy || selected

    NavigationDrawerItem(
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        chat.title.ifBlank { "Без названия" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (chat.preview.isBlank()) chat.subtitle else "${chat.subtitle} · ${chat.preview}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Действия с чатом «${chat.title.ifBlank { "Без названия" }}»",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Переименовать") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                send(Task3Intent.ChatRenameRequested(chat.id))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            enabled = !busy,
                            onClick = {
                                menuOpen = false
                                send(Task3Intent.ChatDeleteRequested(chat.id))
                            },
                        )
                    }
                }
            }
        },
        selected = selected,
        onClick = { if (selectable) send(Task3Intent.ChatSelected(chat.id)) },
        icon = {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        modifier = Modifier
            .alpha(if (selectable) 1f else 0.38f)
            .padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

private fun chatWord(count: Int): String {
    val mod100 = count % 100
    if (mod100 in 11..14) return "чатов"
    return when (count % 10) {
        1 -> "чат"
        2, 3, 4 -> "чата"
        else -> "чатов"
    }
}