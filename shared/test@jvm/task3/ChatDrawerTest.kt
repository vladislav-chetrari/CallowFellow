package task3

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import task2.llama.FakeLlamaBridge
import task3.data.RagRepository
import task3.db.RagDatabase
import task3.llama.FakeEmbedBridge
import task3.presentation.Tab
import task3.presentation.Task3Intent
import task3.presentation.Task3ViewModel

/**
 * Drawer state machine: what the user can do from the chat list. The VM needs a
 * real repository (concrete class, not an interface), so a temp SQLite DB is used.
 */
class ChatDrawerTest {
    private fun vm(): Pair<Task3ViewModel, RagDatabase> {
        val dir = File(System.getProperty("java.io.tmpdir"), "chats-vm-${System.nanoTime()}").apply { mkdirs() }
        val db = Room.databaseBuilder<RagDatabase>(
            name = File(dir, "rag.db").absolutePath,
        ).setDriver(BundledSQLiteDriver()).build()
        val modelsDir = File(dir, "models").apply { mkdirs() }
        val repo = RagRepository(
            genBridge = FakeLlamaBridge(),
            embBridge = FakeEmbedBridge(),
            dao = db.ragDao(),
            modelsDir = modelsDir.absolutePath,
            scope = CoroutineScope(Dispatchers.Default),
        )
        return Task3ViewModel(repo, CoroutineScope(Dispatchers.Default)) to db
    }

    @Test
    fun startsWithOneUntitledChat() = runBlocking {
        val (vm, db) = vm()
        try {
            val ui = withTimeout(5_000) { vm.ui.first { it.chats.isNotEmpty() } }
            assertEquals(1, ui.chats.size, "при первом запуске должен быть один чат")
            assertEquals(ui.chats[0].id, ui.activeChatId)
            assertEquals("Новый чат", ui.chatTitle)
        } finally {
            db.close()
        }
    }

    @Test
    fun drawerTogglesAndClosesOnTabChange() = runBlocking {
        val (vm, db) = vm()
        try {
            withTimeout(5_000) { vm.ui.first { it.chats.isNotEmpty() } }
            vm.sendIntent(Task3Intent.DrawerToggled)
            assertTrue(vm.ui.value.drawerOpen)
            vm.sendIntent(Task3Intent.DrawerToggled)
            assertFalse(vm.ui.value.drawerOpen)

            vm.sendIntent(Task3Intent.DrawerOpen)
            vm.sendIntent(Task3Intent.TabSelected(Tab.DOCS))
            assertFalse(vm.ui.value.drawerOpen, "при уходе с чата drawer закрывается")
        } finally {
            db.close()
        }
    }

    @Test
    fun createChatSelectsItAndClosesDrawer() = runBlocking {
        val (vm, db) = vm()
        try {
            val first = withTimeout(5_000) { vm.ui.first { it.chats.isNotEmpty() } }.chats[0].id
            vm.sendIntent(Task3Intent.DrawerOpen)
            vm.sendIntent(Task3Intent.ChatCreateRequested)

            val ui = withTimeout(5_000) { vm.ui.first { it.chats.size == 2 } }
            assertEquals(2, ui.chats.size)
            assertFalse(ui.drawerOpen, "после создания чата список закрывается")
            assertTrue(ui.activeChatId != first)
            assertTrue(ui.messages.isEmpty(), "новый чат пуст")
        } finally {
            db.close()
        }
    }

    @Test
    fun selectSwitchesMessagesAndRenameGoesThroughTheDialog() = runBlocking {
        val (vm, db) = vm()
        try {
            val first = withTimeout(5_000) { vm.ui.first { it.chats.isNotEmpty() } }.chats[0].id
            vm.sendIntent(Task3Intent.ChatCreateRequested)
            val second = withTimeout(5_000) { vm.ui.first { it.chats.size == 2 } }.activeChatId

            vm.sendIntent(Task3Intent.ChatRenameRequested(second))
            assertEquals(second, vm.ui.value.renamingChatId)
            vm.sendIntent(Task3Intent.ChatRenameInputChanged("Вороны"))
            vm.sendIntent(Task3Intent.ChatRenameConfirmed)

            val renamed = withTimeout(5_000) { vm.ui.first { it.chats.any { c -> c.title == "Вороны" } } }
            assertEquals("Вороны", renamed.chats.first { it.id == second }.title)
            assertEquals(null, renamed.renamingChatId)

            vm.sendIntent(Task3Intent.ChatSelected(first))
            val switched = withTimeout(5_000) { vm.ui.first { it.activeChatId == first } }
            assertEquals(first, switched.activeChatId)
        } finally {
            db.close()
        }
    }

    @Test
    fun deleteChatGoesThroughConfirmation() = runBlocking {
        val (vm, db) = vm()
        try {
            withTimeout(5_000) { vm.ui.first { it.chats.isNotEmpty() } }
            vm.sendIntent(Task3Intent.ChatCreateRequested)
            val second = withTimeout(5_000) { vm.ui.first { it.chats.size == 2 } }.activeChatId

            vm.sendIntent(Task3Intent.ChatDeleteRequested(second))
            assertEquals(second, vm.ui.value.deletingChatId)
            vm.sendIntent(Task3Intent.ChatDeleteConfirmed)

            val ui = withTimeout(5_000) { vm.ui.first { it.chats.size == 1 } }
            assertEquals(1, ui.chats.size)
            assertEquals(null, ui.deletingChatId)
        } finally {
            db.close()
        }
    }
}