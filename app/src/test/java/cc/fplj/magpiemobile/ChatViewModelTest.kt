package cc.fplj.magpiemobile

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.fplj.magpiemobile.core.ChatClient
import cc.fplj.magpiemobile.core.ChatProfile
import cc.fplj.magpiemobile.core.ChatRequestOptions
import cc.fplj.magpiemobile.core.ChatTurnStore
import cc.fplj.magpiemobile.core.GatewayConfig
import cc.fplj.magpiemobile.core.SessionRecord
import cc.fplj.magpiemobile.data.ChatDatabase
import cc.fplj.magpiemobile.data.ChatRepository
import cc.fplj.magpiemobile.data.ConversationEntity
import cc.fplj.magpiemobile.data.StoredChatMessage
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ChatViewModelTest {
    private lateinit var app: Application
    private lateinit var db: ChatDatabase
    private lateinit var repo: ChatRepository
    private val stores = mutableListOf<ViewModelStore>()
    private val extraDatabases = mutableListOf<ChatDatabase>()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, ChatDatabase::class.java).build()
        repo = ChatRepository(db)
    }

    @After fun teardown() {
        stores.forEach { it.clear() }
        extraDatabases.forEach { it.close() }
        db.close()
        Dispatchers.resetMain()
    }

    private fun settled(vm: ChatViewModel) = runBlocking {
        withTimeout(10000) { vm.state.first { !it.busy && !it.sending } }
    }

    private fun vm(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        turnStore: (ChatRepository, String) -> ChatTurnStore = { repository, id -> repository.turnStore(id) },
        repository: ChatRepository = repo,
        acquire: (ProviderSessionKey) -> GatewayRequestLease = { throw CancellationException("Offline fixture") },
    ): ChatViewModel {
        val store = ViewModelStore().also(stores::add)
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(app, { repository }, acquire, dispatcher, turnStore) as T
            }
        }
        return ViewModelProvider(store, factory)[ChatViewModel::class.java].also { settled(it) }
    }

    private fun lease(config: GatewayConfig): GatewayRequestLease = GatewayRequestLeases().acquire(config) { true }

    private fun seedConversation(
        id: String,
        title: String = id,
        updatedAt: Long,
        successfulTurns: Int = 0,
        failedTurns: Int = 0,
        repository: ChatRepository = repo,
        database: ChatDatabase = db,
    ): List<StoredChatMessage> = runBlocking(Dispatchers.IO) {
        repository.initialize(File(app.cacheDir, "absent-chat-view-model-${UUID.randomUUID()}"))
        database.chatDao().insertConversation(ConversationEntity(id, title, updatedAt))
        val turns = repository.turnStore(id)
        var timestamp = 1L
        repeat(successfulTurns) { index ->
            val operation = turns.begin(SessionRecord(timestamp++, "user", "chat:test/model", "question-$index"))
            turns.complete(operation, SessionRecord(timestamp++, "assistant", "stream:test/model",
                "answer-$index", reasoning = "reasoning-$index"))
        }
        repeat(failedTurns) { index ->
            val operation = turns.begin(SessionRecord(timestamp++, "user", "chat:test/model",
                "failed-question-$index"))
            turns.fail(operation, SessionRecord(timestamp++, "error", "stream:test/model", "HTTP 429"))
        }
        repository.messages(id, limit = successfulTurns * 2 + failedTurns * 2)
    }

    private fun seedConversationHistory(
        count: Int,
        prefix: String,
        repository: ChatRepository = repo,
        database: ChatDatabase = db,
    ) = runBlocking(Dispatchers.IO) {
        repository.initialize(File(app.cacheDir, "absent-chat-view-model-${UUID.randomUUID()}"))
        repeat(count) { index ->
            val id = "$prefix-${index.toString().padStart(3, '0')}"
            database.chatDao().insertConversation(ConversationEntity(id, id, index.toLong() + 1))
        }
    }

    private fun isolatedRepository(): Pair<ChatDatabase, ChatRepository> {
        val database = Room.inMemoryDatabaseBuilder(app, ChatDatabase::class.java).build()
            .also(extraDatabases::add)
        return database to ChatRepository(database)
    }

    private fun messageIds(messages: List<StoredChatMessage>) = messages.map { it.id }

    @Test fun renameConversationUpdatesTitleAndLegacyStaysReadOnly() {
        val vm = vm()
        assertTrue(vm.state.value.ready)
        val id = vm.state.value.selected!!.id
        vm.renameConversation(id, "  自定义标题  ")
        val renamed = settled(vm)
        assertEquals("自定义标题", checkNotNull(renamed.conversations.firstOrNull { it.id == id }).title)
        // Blank titles never rename.
        vm.renameConversation(id, "   ")
        val unchanged = settled(vm)
        assertEquals("自定义标题", checkNotNull(unchanged.conversations.firstOrNull { it.id == id }).title)
    }

    @Test fun deleteConversationRemovesAllAndFallsBackToAnother() {
        val vm = vm()
        val first = vm.state.value.selected!!.id
        val second = runBlocking(Dispatchers.IO) { repo.createConversation() }
        vm.selectConversation(second); settled(vm)
        assertEquals(second, vm.state.value.selected!!.id)
        vm.deleteConversation(second)
        val after = settled(vm)
        assertNull(after.conversations.firstOrNull { it.id == second })
        // Deleted selection falls back to another live conversation (never the deleted one).
        checkNotNull(after.selected?.id)
        assertEquals(first, after.selected?.id)
        // Missing conversations fail loudly instead of partial deletes.
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            runBlocking(Dispatchers.IO) { repo.deleteConversation("missing-conversation") }
        }
    }

    @Test fun deletingOtherConversationPreservesOlderMessagePageAndDraft() {
        val first = seedConversation("delete-page-current", updatedAt = 100, successfulTurns = 26)
        seedConversation("delete-page-other", updatedAt = 1)
        val vm = vm()
        assertEquals("delete-page-current", vm.state.value.selected?.id)
        assertEquals(50, vm.state.value.messages.size)
        assertTrue(vm.state.value.olderMessages)
        vm.olderMessages(); val older = settled(vm)
        assertEquals(first.take(2), older.messages)
        assertTrue(older.viewingOlderMessages)
        assertFalse(older.olderMessages)
        vm.editDraft("草稿随旧分页保留")
        vm.profileUnavailable()
        val before = vm.state.value
        assertTrue(before.notice.isNotBlank())

        vm.deleteConversation("delete-page-other")
        val after = settled(vm)

        assertEquals(before.copy(
            conversations = after.conversations,
            moreConversations = after.moreConversations,
        ), after)
        assertEquals(listOf("question-0", "answer-0"), after.messages.map { it.text })
        assertEquals("草稿随旧分页保留", after.draft)
        assertTrue(after.viewingOlderMessages)
        assertFalse(after.olderMessages)
        assertNull(runBlocking(Dispatchers.IO) { repo.conversation("delete-page-other") })
        assertEquals(first, runBlocking(Dispatchers.IO) { repo.messages("delete-page-current", limit = 100) })
    }

    @Test fun deletingOtherConversationKeepsThe104MessageCursorUntilExplicitRefresh() {
        val all = seedConversation("delete-cursor-current", updatedAt = 100,
            successfulTurns = 26, failedTurns = 26)
        seedConversation("delete-cursor-other", updatedAt = 1)
        val vm = vm()
        val currentId = checkNotNull(vm.state.value.selected).id
        assertEquals("delete-cursor-current", currentId)
        assertEquals(104, all.size)
        assertEquals(50, vm.state.value.messages.size)
        val latest = runBlocking(Dispatchers.IO) { repo.messages(currentId, limit = 51).takeLast(50) }
        assertEquals(messageIds(latest), messageIds(vm.state.value.messages))
        val middle = runBlocking(Dispatchers.IO) {
            repo.messages(currentId, beforeId = latest.first().id, limit = 51).takeLast(50)
        }
        vm.editDraft("middle page draft")
        vm.olderMessages(); val loadedMiddle = settled(vm)
        assertEquals(messageIds(middle), messageIds(loadedMiddle.messages))
        assertTrue(loadedMiddle.olderMessages)
        assertTrue(loadedMiddle.viewingOlderMessages)

        vm.deleteConversation("delete-cursor-other")
        val afterDelete = settled(vm)
        assertEquals(messageIds(middle), messageIds(afterDelete.messages))
        assertTrue(afterDelete.olderMessages)
        assertTrue(afterDelete.viewingOlderMessages)
        assertEquals("middle page draft", afterDelete.draft)

        val earliest = runBlocking(Dispatchers.IO) {
            repo.messages(currentId, beforeId = middle.first().id, limit = 51)
        }
        assertEquals(4, earliest.size)
        vm.olderMessages(); val loadedEarliest = settled(vm)
        assertEquals(messageIds(earliest), messageIds(loadedEarliest.messages))
        assertFalse(loadedEarliest.olderMessages)
        assertTrue(loadedEarliest.viewingOlderMessages)
        assertEquals("middle page draft", loadedEarliest.draft)

        vm.refresh(); val refreshed = settled(vm)
        assertEquals(messageIds(latest), messageIds(refreshed.messages))
        assertTrue(refreshed.olderMessages)
        assertFalse(refreshed.viewingOlderMessages)
        assertEquals("middle page draft", refreshed.draft)
    }

    @Test fun deletingHistoryRowsPreservesLoadedRangeAcross31_61And65Conversations() {
        for (total in listOf(65, 31, 61)) {
            val (historyDb, historyRepo) = isolatedRepository()
            val prefix = "history-$total"
            seedConversationHistory(total, prefix, historyRepo, historyDb)
            val vm = vm(repository = historyRepo)
            val currentId = checkNotNull(vm.state.value.selected).id
            val targetLoadedSize = if (total == 31) 30 else 60
            while (vm.state.value.conversations.size < targetLoadedSize) {
                vm.moreConversations(); settled(vm)
            }
            val loadedBefore = vm.state.value.conversations.map { it.id }
            assertEquals(targetLoadedSize, loadedBefore.size)
            val target = loadedBefore[if (total > 30) 35 else 10]
            assertNotEquals(currentId, target)

            vm.deleteConversation(target)
            val afterDelete = settled(vm)
            val expected = runBlocking(Dispatchers.IO) { historyRepo.conversations(limit = 100) }
            assertEquals(expected.take(targetLoadedSize).map { it.id },
                afterDelete.conversations.map { it.id })
            assertEquals(expected.size > targetLoadedSize, afterDelete.moreConversations)
            assertEquals(currentId, afterDelete.selected?.id)

            if (total == 65) {
                assertTrue(afterDelete.moreConversations)
                vm.moreConversations(); val fullyLoaded = settled(vm)
                assertEquals(expected.map { it.id }, fullyLoaded.conversations.map { it.id })
                assertEquals(expected.size, fullyLoaded.conversations.map { it.id }.distinct().size)
                assertFalse(fullyLoaded.moreConversations)
            }
            if (total == 31) {
                assertEquals(30, afterDelete.conversations.size)
                assertFalse(afterDelete.moreConversations)
            }
            if (total == 61) {
                assertEquals(60, afterDelete.conversations.size)
                assertFalse(afterDelete.moreConversations)
            }
            assertNull(runBlocking(Dispatchers.IO) { historyRepo.conversation(target) })
        }
    }

    @Test fun rejectingDeletionOfRunningOtherConversationPreservesCurrentPageAndDraft() {
        val first = seedConversation("delete-running-current", updatedAt = 100, successfulTurns = 26)
        seedConversation("delete-running-other", updatedAt = 1)
        val running = runBlocking(Dispatchers.IO) {
            repo.turnStore("delete-running-other").begin(
                SessionRecord(2, "user", "chat:test/model", "still running"),
            )
        }
        val captures = AtomicInteger()
        val vm = vm(acquire = { captures.incrementAndGet(); throw CancellationException("Offline fixture") })
        assertEquals("delete-running-current", vm.state.value.selected?.id)
        vm.olderMessages(); val older = settled(vm)
        assertEquals(first.take(2), older.messages)
        vm.editDraft("delete error leaves draft")
        val before = vm.state.value

        vm.deleteConversation("delete-running-other")
        val after = settled(vm)
        assertEquals(before.copy(notice = "会话删除失败；若该会话正在发送，请稍后重试。"), after)
        runBlocking(Dispatchers.IO) {
            assertNotNull(repo.conversation("delete-running-other"))
            assertEquals("RUNNING", db.chatDao().operation(running)?.state)
            assertEquals(listOf("still running"), repo.messages("delete-running-other").map { it.text })
            assertEquals(first, repo.messages("delete-running-current", limit = 100))
        }
        assertEquals(0, captures.get())
    }

    @Test fun initializesOfflineAndKeepsSeparateConversationDrafts() {
        val vm = vm()
        assertTrue(vm.state.value.ready)
        val first = vm.state.value.selected!!.id
        vm.editDraft("draft one")
        vm.newConversation(); settled(vm)
        assertNotEquals(first, vm.state.value.selected!!.id)
        assertEquals("", vm.state.value.draft)
        vm.editDraft("draft two")
        vm.selectConversation(first); settled(vm)
        assertEquals("draft one", vm.state.value.draft)
        vm.send("provider/model", SESSION_A)
        assertFalse(vm.state.value.sending)
        assertEquals("draft one", vm.state.value.draft)
    }

    @Test fun reopeningRestoresOnlySuccessfulContextAndSendsNothing() {
        val first = vm()
        val id = first.state.value.selected!!.id
        runBlocking(Dispatchers.IO) {
            val turns = repo.turnStore(id)
            val success = turns.begin(SessionRecord(1, "user", "chat:p/m", "completed user"))
            turns.complete(success, SessionRecord(2, "assistant", "stream:p/m", "completed answer"))
            val failed = turns.begin(SessionRecord(3, "user", "chat:p/m", "failed user"))
            turns.fail(failed, SessionRecord(4, "error", "stream:p/m", "HTTP 429"))
        }
        val reopened = vm()
        assertEquals(id, reopened.state.value.selected!!.id)
        assertEquals(4, reopened.state.value.messages.size)
        assertFalse(reopened.state.value.sending)
        assertEquals("", reopened.state.value.draft)
        val context = runBlocking(Dispatchers.IO) { repo.context(id) }
        assertEquals(listOf("completed user", "completed answer"), context.map { it.content })
    }

    @Test fun retainedViewModelDoesNotDuplicateSendAndRefreshIsReadOnly() {
        Probe().use { probe ->
            val vm = vm { lease(probe.config) }
            val store = stores.last()
            vm.editDraft("one paid turn")
            vm.send("provider/model", SESSION_A)
            assertTrue(probe.started.await(10, TimeUnit.SECONDS))
            assertTrue(vm.state.value.sending)
            val retained = ViewModelProvider(store, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = error("Must retain ViewModel")
            })[ChatViewModel::class.java]
            assertSame(vm, retained)
            retained.send("provider/model", SESSION_A)
            retained.refresh()
            retained.newConversation()
            assertEquals(1, probe.requests.get())
            probe.release.countDown()
            settled(vm)
            assertEquals("", vm.state.value.draft)
            assertEquals(listOf("user", "assistant"), vm.state.value.messages.map { it.role })
            assertFalse(vm.state.value.toString().contains("synthetic-gateway-secret"))
            vm.refresh(); settled(vm)
            assertEquals(1, probe.requests.get())
        }
    }

    @Test fun failedStreamKeepsDraftAndDoesNotReplayOnRefreshOrRecreation() {
        Probe(success = false, reasoning = "尚未完成的思考").use { probe ->
            val vm = vm { lease(probe.config) }
            vm.editDraft("retry only by user")
            vm.send("provider/model", SESSION_A)
            assertTrue(probe.started.await(10, TimeUnit.SECONDS))
            probe.release.countDown(); settled(vm)
            assertEquals("retry only by user", vm.state.value.draft)
            assertTrue(vm.state.value.notice.contains("未自动重发"))
            assertEquals("尚未完成的思考", vm.state.value.partialReasoning)
            assertEquals("answer", vm.state.value.partial)
            assertEquals(1, probe.requests.get())
            vm.refresh(); settled(vm)
            val reopened = vm { lease(probe.config) }
            assertFalse(reopened.state.value.sending)
            assertEquals(1, probe.requests.get())
            assertTrue(runBlocking(Dispatchers.IO) { repo.context(vm.state.value.selected!!.id) }.isEmpty())
        }
    }

    @Test fun deletingSelectedConversationRestoresFallbackDraftAndClearsOnlyItsFailedPreview() {
        Probe(success = false, reasoning = "unfinished reasoning").use { probe ->
            val vm = vm { lease(probe.config) }
            val first = checkNotNull(vm.state.value.selected).id
            runBlocking(Dispatchers.IO) {
                val turns = repo.turnStore(first)
                repeat(26) { index ->
                    val timestamp = index.toLong() * 2 + 2
                    val turn = turns.begin(SessionRecord(timestamp, "user", "chat:test/model", "fallback-question-$index"))
                    turns.complete(turn, SessionRecord(timestamp + 1, "assistant", "stream:test/model",
                        "fallback-answer-$index"))
                }
            }
            vm.refresh(); settled(vm)
            vm.olderMessages(); val oldFallbackPage = settled(vm)
            assertEquals(2, oldFallbackPage.messages.size)
            assertTrue(oldFallbackPage.viewingOlderMessages)
            vm.editDraft("first conversation draft")
            vm.newConversation(); settled(vm)
            val second = checkNotNull(vm.state.value.selected).id
            vm.editDraft("second conversation draft")
            vm.send("provider/model", SESSION_A)
            assertTrue(probe.started.await(10, TimeUnit.SECONDS))
            probe.release.countDown()
            val failed = settled(vm)
            assertEquals("answer", failed.partial)
            assertEquals("unfinished reasoning", failed.partialReasoning)

            // Deleting an unrelated row must preserve the selected conversation's draft and preview.
            val unrelated = runBlocking(Dispatchers.IO) { repo.createConversation() }
            vm.deleteConversation(unrelated)
            val retained = settled(vm)
            assertEquals(second, retained.selected?.id)
            assertEquals("second conversation draft", retained.draft)
            assertEquals("answer", retained.partial)
            assertEquals("unfinished reasoning", retained.partialReasoning)

            vm.deleteConversation(second)
            val restored = settled(vm)
            assertEquals(first, restored.selected?.id)
            val firstLatest = runBlocking(Dispatchers.IO) { repo.messages(first, limit = 51).takeLast(50) }
            assertEquals(messageIds(firstLatest), messageIds(restored.messages))
            assertEquals(50, restored.messages.size)
            assertTrue(restored.olderMessages)
            assertFalse(restored.viewingOlderMessages)
            assertEquals("first conversation draft", restored.draft)
            assertEquals("", restored.partial)
            assertEquals("", restored.partialReasoning)
            assertEquals("", restored.notice)
            assertNull(runBlocking(Dispatchers.IO) { repo.conversation(second) })

            // Removing the final conversation creates an empty fallback without inheriting its draft.
            vm.deleteConversation(first)
            val fresh = settled(vm)
            assertNotEquals(first, fresh.selected?.id)
            assertEquals("", fresh.draft)
            assertTrue(fresh.messages.isEmpty())
            assertEquals(1, probe.requests.get())
        }
    }

    @Test fun successfulReasoningIsShownInHistoryButExcludedFromNextContext() {
        Probe(reasoning = "先理解问题，再组织答案。").use { probe ->
            val vm = vm { lease(probe.config) }
            vm.editDraft("哈哈哈")
            vm.send("provider/model", SESSION_A)
            assertTrue(probe.started.await(10, TimeUnit.SECONDS))
            probe.release.countDown()
            val completed = settled(vm)
            val assistant = completed.messages.single { it.role == "assistant" }
            assertEquals("answer", assistant.text)
            assertEquals("先理解问题，再组织答案。", assistant.reasoning)
            assertEquals("", completed.partial)
            assertEquals("", completed.partialReasoning)
            val context = runBlocking(Dispatchers.IO) { repo.context(vm.state.value.selected!!.id) }
            assertEquals(listOf("哈哈哈", "answer"), context.map { it.content })
            assertFalse(context.any { it.content.contains("先理解问题") })
        }
    }

    @Test fun unavailableProfilePreservesDraftWithoutSideEffectsAndCannotOverwriteBusyOrSendingState() {
        Probe().use { probe ->
            val source = LeaseSource(probe.config)
            val io = GatedDispatcher()
            val begins = AtomicInteger()
            val vm = vm(dispatcher = io, turnStore = { repository, id ->
                val real = repository.turnStore(id)
                object : ChatTurnStore by real {
                    override fun begin(user: SessionRecord): String {
                        begins.incrementAndGet()
                        return real.begin(user)
                    }
                }
            }, acquire = source::acquire)
            val id = checkNotNull(vm.state.value.selected).id
            vm.editDraft("keep draft when profile storage cannot be read")
            try {
                // Refresh is held before its I/O: the empty existing notice must stay untouched.
                io.hold = true
                vm.refresh()
                val busy = vm.state.value
                assertTrue(busy.busy)
                vm.profileUnavailable()
                assertEquals(busy, vm.state.value)
                io.release(); settled(vm)

                val idle = vm.state.value
                vm.profileUnavailable()
                val unavailable = vm.state.value
                assertTrue(unavailable.notice.contains("预设暂不可读取"))
                assertEquals(idle.copy(notice = unavailable.notice), unavailable)
                assertEquals(0, source.captures.get())
                assertEquals(0, begins.get())
                assertEquals(0, probe.requests.get())
                assertTrue(runBlocking(Dispatchers.IO) { repo.messages(id) }.isEmpty())

                // An authorized queued send has one lease, but this warning cannot mutate it.
                io.hold = true
                vm.send("provider/model", SESSION_A)
                val sending = vm.state.value
                assertTrue(sending.sending)
                assertEquals(1, source.captures.get())
                vm.profileUnavailable()
                assertEquals(sending, vm.state.value)
                assertEquals(1, source.captures.get())
                assertEquals(0, begins.get())
                assertEquals(0, probe.requests.get())
                stores.last().clear()
                io.release(); settled(vm)
                assertEquals(1, source.closes.get())
                assertEquals(idle.draft, vm.state.value.draft)
                assertEquals(0, begins.get())
                assertEquals(0, probe.requests.get())
                assertTrue(probe.bodies.isEmpty())
                runBlocking(Dispatchers.IO) {
                    assertTrue(repo.messages(id).isEmpty())
                    assertNull(db.chatDao().runningOperation(id))
                }
            } finally { io.release() }
        }
    }

    @Test fun queuedSendKeepsClickedProfileSnapshotAndNextExplicitTurnUsesNewProfile() {
        for (holdMain in listOf(false, true)) {
            Probe().use { probe ->
                val source = LeaseSource(probe.config)
                val io = GatedDispatcher()
                val vm = vm(dispatcher = io, acquire = source::acquire)
                vm.newConversation(); settled(vm)
                var selectedProfile = ChatProfile("queued-profile", "Queued profile",
                    systemPrompt = "First clicked system prompt", temperature = 0.0, topP = 0.0,
                    maxTokens = 23, modelAlias = "must-not-override-confirmed-model")
                val clickedOptions = ChatRequestOptions.fromProfile(selectedProfile)
                vm.editDraft("first queued draft")
                val main = if (holdMain) StandardTestDispatcher().also { Dispatchers.setMain(it) } else null
                io.hold = true
                try {
                    vm.send("provider/confirmed-model", SESSION_A, ChatRequestOptions.fromProfile(selectedProfile))
                    assertEquals(1, source.captures.get())
                    assertTrue(vm.state.value.sending)
                    assertEquals(if (holdMain) 0 else 1, io.queued)
                    assertEquals(0, probe.requests.get())
                    selectedProfile = selectedProfile.copy(systemPrompt = "Next selected system prompt",
                        temperature = 0.9, topP = 0.75, maxTokens = 41)
                    main?.scheduler?.runCurrent()
                    if (main != null) Dispatchers.setMain(UnconfinedTestDispatcher())
                    io.release()
                    assertTrue(probe.started.await(5, TimeUnit.SECONDS))
                    probe.release.countDown()
                    assertEquals("", settled(vm).draft)

                    val first = requestBody(probe, 0)
                    assertRequestOptions(first, clickedOptions)
                    assertEquals(listOf(ChatClient.Message("system", clickedOptions.systemPrompt),
                        ChatClient.Message("user", "first queued draft")), requestMessages(first))
                    assertEquals("provider/confirmed-model", first.getValue("model").jsonPrimitive.content)

                    val nextOptions = ChatRequestOptions.fromProfile(selectedProfile)
                    vm.editDraft("next explicit draft")
                    vm.send("provider/confirmed-model", SESSION_A, nextOptions)
                    assertEquals("", settled(vm).draft)
                    val second = requestBody(probe, 1)
                    assertRequestOptions(second, nextOptions)
                    assertEquals(listOf(ChatClient.Message("system", nextOptions.systemPrompt),
                        ChatClient.Message("user", "first queued draft"), ChatClient.Message("assistant", "answer"),
                        ChatClient.Message("user", "next explicit draft")), requestMessages(second))
                    assertEquals("provider/confirmed-model", second.getValue("model").jsonPrimitive.content)
                    assertFalse(probe.bodies.any { it.contains(selectedProfile.modelAlias) })
                    assertEquals(2, probe.requests.get())
                    assertEquals(2, source.captures.get())
                    assertEquals(2, source.closes.get())
                } finally {
                    Dispatchers.setMain(UnconfinedTestDispatcher())
                    io.release()
                    main?.scheduler?.runCurrent()
                }
            }
        }
    }

    @Test fun profileOptionsReachActualHttpWhileRoomKeepsOnlyVisibleConversationContext() {
        Probe(reasoning = "separate saved profile reasoning").use { probe ->
            val vm = vm { lease(probe.config) }
            val id = checkNotNull(vm.state.value.selected).id
            val options = ChatRequestOptions.fromProfile(ChatProfile("profile-room", "Profile Room",
                systemPrompt = "Request-only system prompt", temperature = 0.3, topP = 0.85, maxTokens = 512))
            vm.editDraft("first profile user")
            vm.send("provider/model", SESSION_A, options)
            assertTrue(probe.started.await(5, TimeUnit.SECONDS))
            probe.release.countDown()
            val first = settled(vm)
            assertEquals("separate saved profile reasoning", first.messages.last().reasoning)
            vm.editDraft("second profile user")
            vm.send("provider/model", SESSION_A, options)
            val completed = settled(vm)
            assertEquals("", completed.draft)
            assertEquals(2, probe.requests.get())
            assertEquals(listOf(ChatClient.Message("system", options.systemPrompt),
                ChatClient.Message("user", "first profile user")), requestMessages(requestBody(probe, 0)))
            assertEquals(listOf(ChatClient.Message("system", options.systemPrompt),
                ChatClient.Message("user", "first profile user"), ChatClient.Message("assistant", "answer"),
                ChatClient.Message("user", "second profile user")), requestMessages(requestBody(probe, 1)))
            probe.bodies.indices.forEach { index ->
                val body = requestBody(probe, index)
                assertRequestOptions(body, options)
                assertEquals(1, requestMessages(body).count { it.role == "system" })
                assertFalse(probe.bodies[index].contains("separate saved profile reasoning"))
            }
            runBlocking(Dispatchers.IO) {
                val messages = repo.messages(id)
                assertEquals(listOf("user", "assistant", "user", "assistant"), messages.map { it.role })
                assertTrue(messages.all { it.state == "SUCCEEDED" })
                assertTrue(messages.none { it.text.contains(options.systemPrompt) || it.text.contains("profile reasoning") })
                assertEquals(listOf("separate saved profile reasoning", "separate saved profile reasoning"),
                    messages.filter { it.role == "assistant" }.map { it.reasoning })
                assertEquals(listOf(ChatClient.Message("user", "first profile user"), ChatClient.Message("assistant", "answer"),
                    ChatClient.Message("user", "second profile user"), ChatClient.Message("assistant", "answer")), repo.context(id))
                assertNull(db.chatDao().runningOperation(id))
            }
        }
    }

    @Test fun systemPromptContextAndDraftOverLimitRejectBeforeRoomBeginOrHttpAndKeepDraft() {
        Probe().use { probe ->
            val source = LeaseSource(probe.config)
            val begins = AtomicInteger()
            val vm = vm(turnStore = { repository, id ->
                val real = repository.turnStore(id)
                object : ChatTurnStore by real {
                    override fun begin(user: SessionRecord): String {
                        begins.incrementAndGet()
                        return real.begin(user)
                    }
                }
            }, acquire = source::acquire)
            val id = checkNotNull(vm.state.value.selected).id
            val context = seedProfileContext(id)
            vm.refresh(); settled(vm)
            assertFalse(vm.state.value.contextBlocked)
            val draft = "d".repeat(513)
            val options = ChatRequestOptions(systemPrompt = "s".repeat(1024))
            assertEquals(65537, context.sumOf { it.content.length } + draft.length + options.systemPrompt.length)
            vm.editDraft(draft)
            vm.send("provider/model", SESSION_A, options)
            val rejected = settled(vm)

            assertEquals(draft, rejected.draft)
            assertTrue(rejected.notice.contains("上下文达到本机安全上限"))
            assertEquals(0, begins.get())
            assertEquals(0, probe.requests.get())
            assertTrue(probe.bodies.isEmpty())
            assertEquals(1, source.captures.get())
            assertEquals(1, source.closes.get())
            runBlocking(Dispatchers.IO) {
                assertEquals(context, repo.context(id))
                assertEquals(listOf("user", "assistant"), repo.messages(id).map { it.role })
                assertNull(db.chatDao().runningOperation(id))
            }
        }
    }

    @Test fun systemPromptContextAndDraftExactlyAtLimitReachHttpAndCommitRoomTurn() {
        Probe().use { probe ->
            val begins = AtomicInteger()
            val vm = vm(turnStore = { repository, id ->
                val real = repository.turnStore(id)
                object : ChatTurnStore by real {
                    override fun begin(user: SessionRecord): String {
                        begins.incrementAndGet()
                        return real.begin(user)
                    }
                }
            }, acquire = { lease(probe.config) })
            val id = checkNotNull(vm.state.value.selected).id
            val context = seedProfileContext(id)
            vm.refresh(); settled(vm)
            val draft = "d".repeat(512)
            val options = ChatRequestOptions(systemPrompt = "s".repeat(1024), temperature = 0.0, topP = 0.0, maxTokens = 1)
            assertEquals(65536, context.sumOf { it.content.length } + draft.length + options.systemPrompt.length)
            vm.editDraft(draft)
            vm.send("provider/model", SESSION_A, options)
            assertTrue(probe.started.await(5, TimeUnit.SECONDS))
            probe.release.countDown()
            val completed = settled(vm)

            assertEquals("", completed.draft)
            assertEquals("本轮已保存。", completed.notice)
            assertEquals(1, begins.get())
            assertEquals(1, probe.requests.get())
            val body = requestBody(probe, 0)
            assertRequestOptions(body, options)
            assertEquals(listOf(ChatClient.Message("system", options.systemPrompt)) + context + ChatClient.Message("user", draft),
                requestMessages(body))
            assertEquals(65536, requestMessages(body).sumOf { it.content.length })
            runBlocking(Dispatchers.IO) {
                val messages = repo.messages(id)
                assertEquals(listOf("user", "assistant", "user", "assistant"), messages.map { it.role })
                assertTrue(messages.all { it.state == "SUCCEEDED" })
                assertTrue(messages.none { it.role == "system" || it.text == options.systemPrompt })
                assertEquals(context + ChatClient.Message("user", draft) + ChatClient.Message("assistant", "answer"), repo.context(id))
                assertNull(db.chatDao().runningOperation(id))
            }
        }
    }

    private fun requestBody(probe: Probe, index: Int): JsonObject = Json.parseToJsonElement(probe.bodies[index]).jsonObject

    private fun requestMessages(body: JsonObject): List<ChatClient.Message> = body.getValue("messages").jsonArray.map {
        ChatClient.Message(it.jsonObject.getValue("role").jsonPrimitive.content,
            it.jsonObject.getValue("content").jsonPrimitive.content)
    }

    private fun assertRequestOptions(body: JsonObject, options: ChatRequestOptions) {
        assertEquals(options.temperature, body["temperature"]?.jsonPrimitive?.double)
        assertEquals(options.topP, body["top_p"]?.jsonPrimitive?.double)
        assertEquals(options.maxTokens, body["max_tokens"]?.jsonPrimitive?.int)
    }

    private fun seedProfileContext(id: String): List<ChatClient.Message> = runBlocking(Dispatchers.IO) {
        val user = "u".repeat(32000)
        val assistant = "a".repeat(32000)
        val turns = repo.turnStore(id)
        val turnId = turns.begin(SessionRecord(1, "user", "chat:provider/model", user))
        turns.complete(turnId, SessionRecord(2, "assistant", "chat:provider/model:stream", assistant))
        listOf(ChatClient.Message("user", user), ChatClient.Message("assistant", assistant))
    }

    @Test fun cancellationAfterCommittedBeginClosesTurnAndAllowsNextExplicitSend() = runBlocking(Dispatchers.IO) {
        val id = repo.createConversation()
        val real = repo.turnStore(id)
        val job = Job()
        val cancelAtCommit = object : ChatTurnStore by real {
            override fun begin(user: SessionRecord): String = real.begin(user).also { job.cancel() }
        }
        val guarded = cancellableTurnStore(cancelAtCommit, job)
        assertThrows(CancellationException::class.java) { guarded.begin(SessionRecord(1, "user", "chat:p/m", "cancel before HTTP")) }
        assertEquals(listOf("FAILED", "FAILED"), repo.messages(id).map { it.state })
        assertTrue(repo.context(id).isEmpty())
        val next = real.begin(SessionRecord(2, "user", "chat:p/m", "new explicit turn"))
        real.fail(next, SessionRecord(3, "error", "chat:p/m", "HTTP 429"))
    }

    @Test fun clearingViewModelStoreCancelsSilentHeadersAndSilentBodyAndFinalizesRoomTurn() {
        for (sendHeaders in listOf(false, true)) {
            SilentProbe(sendHeaders).use { probe ->
                val vm = vm { lease(probe.config) }
                vm.newConversation(); settled(vm)
                val id = checkNotNull(vm.state.value.selected).id
                vm.editDraft("one turn with no callbacks")
                vm.send("provider/model", SESSION_A)
                assertTrue(probe.started.await(5, TimeUnit.SECONDS))
                assertTrue(vm.state.value.sending)
                assertEquals("", vm.state.value.partial)
                assertEquals("", vm.state.value.partialReasoning)
                assertNotNull(runBlocking(Dispatchers.IO) { db.chatDao().runningOperation(id) })

                val startedClearing = System.nanoTime()
                stores.last().clear()
                assertTrue("ViewModelStore.clear must not wait for blocked HTTP I/O",
                    System.nanoTime() - startedClearing < TimeUnit.SECONDS.toNanos(3))
                assertTrue("Cancelled HTTP peer must close before its 30-second read timeout",
                    probe.peerClosed.await(5, TimeUnit.SECONDS))
                runBlocking { withTimeout(5000) { vm.state.first { !it.sending } } }

                runBlocking(Dispatchers.IO) {
                    assertNull(db.chatDao().runningOperation(id))
                    val messages = repo.messages(id)
                    assertEquals(listOf("user", "error"), messages.map { it.role })
                    assertTrue(messages.all { it.state == "FAILED" })
                    assertEquals("Chat failed; partial characters=0", messages.last().text)
                    assertTrue(repo.context(id).isEmpty())
                }
                // Reopening reads the finalized failure; it must not replay the cancelled turn.
                val reopened = vm { lease(probe.config) }
                assertEquals(id, reopened.state.value.selected?.id)
                assertFalse(reopened.state.value.sending)
                assertTrue(reopened.state.value.messages.none { it.role == "assistant" })
                assertEquals(1, probe.requests.get())
            }
        }
    }

    @Test fun completeGuardRejectsCancellationBeforeEnteringStorageAndAllowsFailureFinalization() = runBlocking(Dispatchers.IO) {
        val id = repo.createConversation()
        val real = repo.turnStore(id)
        val job = Job()
        val entered = AtomicInteger()
        val observed = object : ChatTurnStore by real {
            override fun complete(turnId: String, assistant: SessionRecord) {
                entered.incrementAndGet()
                real.complete(turnId, assistant)
            }
        }
        val guarded = cancellableTurnStore(observed, job)
        val turn = guarded.begin(SessionRecord(1, "user", "chat:p/m", "cancel before commit"))
        job.cancel()

        assertThrows(CancellationException::class.java) {
            guarded.complete(turn, SessionRecord(2, "assistant", "chat:p/m", "must not commit"))
        }

        assertEquals(0, entered.get())
        assertEquals("RUNNING", db.chatDao().operation(turn)?.state)
        // Session cleanup can still close the known turn even though its send Job is cancelled.
        guarded.fail(turn, SessionRecord(3, "error", "chat:p/m", "Chat failed; partial characters=0"))
        assertEquals("FAILED", db.chatDao().operation(turn)?.state)
        assertNull(db.chatDao().runningOperation(id))
        assertTrue(repo.messages(id).none { it.role == "assistant" })
    }

    @Test fun completeGuardKeepsSuccessWhenCancellationOccursAfterStorageWasEntered() = runBlocking(Dispatchers.IO) {
        val id = repo.createConversation()
        val real = repo.turnStore(id)
        val job = Job()
        val entered = AtomicInteger()
        val cancelInsideStorage = object : ChatTurnStore by real {
            override fun complete(turnId: String, assistant: SessionRecord) {
                entered.incrementAndGet()
                job.cancel()
                real.complete(turnId, assistant)
            }
        }
        val guarded = cancellableTurnStore(cancelInsideStorage, job)
        val turn = guarded.begin(SessionRecord(1, "user", "chat:p/m", "commit this turn"))

        guarded.complete(turn, SessionRecord(2, "assistant", "chat:p/m", "committed answer"))

        assertEquals(1, entered.get())
        assertEquals("SUCCEEDED", db.chatDao().operation(turn)?.state)
        assertNull(db.chatDao().runningOperation(id))
        assertEquals(listOf("user", "assistant"), repo.messages(id).map { it.role })
        assertEquals(listOf("commit this turn", "committed answer"), repo.context(id).map { it.content })
    }

    @Test fun missingExpectedCatalogSessionDoesNotAcquireOrBegin() {
        Probe().use { probe ->
            val source = LeaseSource(probe.config)
            val vm = vm(acquire = source::acquire)
            val id = checkNotNull(vm.state.value.selected).id
            vm.editDraft("draft without a catalog identity")
            vm.send("provider/model", null)
            assertFalse(vm.state.value.sending)
            assertEquals("draft without a catalog identity", vm.state.value.draft)
            assertEquals(0, source.captures.get())
            assertEquals(0, probe.requests.get())
            assertTrue(runBlocking(Dispatchers.IO) { repo.messages(id) }.isEmpty())
        }
    }

    @Test fun staleCatalogClickCannotAcquireCurrentEpochOrReplacementOwner() {
        for (replacement in listOf(SESSION_A_NEXT, SESSION_B)) {
            Probe().use { probe ->
                val source = LeaseSource(probe.config)
                val vm = vm(acquire = source::acquire)
                val id = checkNotNull(vm.state.value.selected).id
                vm.editDraft("old catalog click")
                source.change(replacement)
                vm.send("provider/model", SESSION_A)
                assertFalse(vm.state.value.sending)
                assertEquals("old catalog click", vm.state.value.draft)
                assertTrue(vm.state.value.notice.contains("未自动重发"))
                assertEquals(0, source.captures.get())
                assertEquals(0, probe.requests.get())
                assertTrue(runBlocking(Dispatchers.IO) { repo.messages(id) }.isEmpty())
            }
        }
    }

    @Test fun queuedSendCannotCrossAnEpochOrOwnerBeforeMainOrIoStarts() {
        for (holdMain in listOf(false, true)) {
            for (replacement in listOf(SESSION_A_NEXT, SESSION_B)) {
                Probe().use { probe ->
                    val source = LeaseSource(probe.config)
                    val io = GatedDispatcher()
                    val vm = vm(dispatcher = io, acquire = source::acquire)
                    val id = checkNotNull(vm.state.value.selected).id
                    vm.editDraft("fixed queued draft")
                    val main = if (holdMain) StandardTestDispatcher().also { Dispatchers.setMain(it) } else null
                    io.hold = true
                    try {
                        vm.send("provider/model", SESSION_A)
                        // The lease is captured even if the Main launch has not run yet.
                        assertEquals(1, source.captures.get())
                        assertTrue(vm.state.value.sending)
                        assertEquals(if (holdMain) 0 else 1, io.queued)
                        // Same config and secret; guard must reject by full identity even without a signal.
                        source.change(replacement, invalidate = false)
                        main?.scheduler?.runCurrent()
                        if (main != null) Dispatchers.setMain(UnconfinedTestDispatcher())
                        io.release()
                        val after = settled(vm)
                        assertEquals("fixed queued draft", after.draft)
                        assertTrue(after.notice.contains("未自动重发"))
                        assertEquals(0, probe.requests.get())
                        assertTrue(runBlocking(Dispatchers.IO) { repo.messages(id) }.isEmpty())
                        assertEquals(1, source.captures.get())
                        assertEquals(1, source.closes.get())
                        assertThrows(CancellationException::class.java) { source.controls.single().ensureActive() }
                    } finally {
                        Dispatchers.setMain(UnconfinedTestDispatcher())
                        io.release()
                        main?.scheduler?.runCurrent()
                    }
                }
            }
        }
    }

    @Test fun disposingBeforeLaunchOrBeforeIoReleasesCapturedLeaseExactlyOnce() {
        for (holdMain in listOf(false, true)) {
            Probe().use { probe ->
                val source = LeaseSource(probe.config)
                val io = GatedDispatcher()
                val vm = vm(dispatcher = io, acquire = source::acquire)
                val id = checkNotNull(vm.state.value.selected).id
                vm.editDraft("dispose queued draft")
                val main = if (holdMain) StandardTestDispatcher().also { Dispatchers.setMain(it) } else null
                io.hold = true
                try {
                    vm.send("provider/model", SESSION_A)
                    assertEquals(1, source.captures.get())
                    stores.last().clear()
                    main?.scheduler?.runCurrent()
                    if (main != null) Dispatchers.setMain(UnconfinedTestDispatcher())
                    io.release()
                    val after = settled(vm)
                    assertEquals("dispose queued draft", after.draft)
                    assertEquals(0, probe.requests.get())
                    assertEquals(1, source.closes.get())
                    assertTrue(runBlocking(Dispatchers.IO) { repo.messages(id) }.isEmpty())
                    assertThrows(CancellationException::class.java) { source.controls.single().ensureActive() }
                } finally {
                    Dispatchers.setMain(UnconfinedTestDispatcher())
                    io.release()
                    main?.scheduler?.runCurrent()
                }
            }
        }
    }

    @Test fun sendingOnAnAlreadyDisposedScopeReleasesSynchronousCaptureWithoutRoomOrHttp() {
        Probe().use { probe ->
            val source = LeaseSource(probe.config)
            val vm = vm(acquire = source::acquire)
            val id = checkNotNull(vm.state.value.selected).id
            vm.editDraft("scope already gone")
            stores.last().clear()
            vm.send("provider/model", SESSION_A)
            assertFalse(vm.state.value.sending)
            assertEquals("scope already gone", vm.state.value.draft)
            assertEquals(1, source.captures.get())
            assertEquals(1, source.closes.get())
            assertEquals(0, probe.requests.get())
            assertTrue(runBlocking(Dispatchers.IO) { repo.messages(id) }.isEmpty())
        }
    }

    @Test fun sessionChangeAfterContextBeforeBeginCreatesNoTurnOrRequest() {
        Probe().use { probe ->
            val source = LeaseSource(probe.config)
            val vm = vm(turnStore = { repository, id ->
                // Factory is called after context has been read, immediately before guarded begin.
                source.change(SESSION_A_NEXT, invalidate = false)
                repository.turnStore(id)
            }, acquire = source::acquire)
            val id = checkNotNull(vm.state.value.selected).id
            vm.editDraft("expired before begin")
            vm.send("provider/model", SESSION_A)
            val after = settled(vm)
            assertEquals("expired before begin", after.draft)
            assertEquals(0, probe.requests.get())
            assertTrue(runBlocking(Dispatchers.IO) { repo.messages(id) }.isEmpty())
            assertEquals(1, source.closes.get())
        }
    }

    @Test fun invalidationAfterBeginClosesKnownTurnAndOnlyNewExplicitSendUsesNewSession() {
        Probe().use { probe ->
            val source = LeaseSource(probe.config)
            val invalidateOnce = AtomicBoolean(true)
            val vm = vm(turnStore = { repository, id ->
                val real = repository.turnStore(id)
                object : ChatTurnStore by real {
                    override fun begin(user: SessionRecord): String = real.begin(user).also {
                        if (invalidateOnce.compareAndSet(true, false)) source.change(SESSION_A_NEXT, invalidate = false)
                    }
                }
            }, acquire = source::acquire)
            val id = checkNotNull(vm.state.value.selected).id
            vm.editDraft("manual retry after known failure")
            vm.send("provider/model", SESSION_A)
            settled(vm)
            assertEquals(0, probe.requests.get())
            assertEquals("manual retry after known failure", vm.state.value.draft)
            runBlocking(Dispatchers.IO) {
                assertNull(db.chatDao().runningOperation(id))
                val failed = repo.messages(id)
                assertEquals(listOf("user", "error"), failed.map { it.role })
                assertTrue(failed.all { it.state == "FAILED" })
                assertEquals("Chat failed; partial characters=0", failed.last().text)
                assertTrue(repo.context(id).isEmpty())
            }
            vm.refresh(); settled(vm)
            assertEquals(0, probe.requests.get())
            vm.send("provider/model", SESSION_A_NEXT)
            assertTrue(probe.started.await(5, TimeUnit.SECONDS))
            probe.release.countDown()
            val completed = settled(vm)
            assertEquals("", completed.draft)
            assertEquals(listOf("FAILED", "FAILED", "SUCCEEDED", "SUCCEEDED"), completed.messages.map { it.state })
            assertEquals(1, probe.requests.get())
            assertEquals(2, source.captures.get())
            assertEquals(2, source.closes.get())
        }
    }

    @Test fun epochAndOwnerInvalidationCloseRealSilentHeaderAndBodySocketsWithoutReplay() {
        for (replacement in listOf(SESSION_A_NEXT, SESSION_B)) {
            for (sendHeaders in listOf(false, true)) {
                SilentProbe(sendHeaders).use { probe ->
                    val source = LeaseSource(probe.config)
                    val vm = vm(acquire = source::acquire)
                    vm.newConversation(); settled(vm)
                    val id = checkNotNull(vm.state.value.selected).id
                    vm.editDraft("retain this silent request draft")
                    vm.send("provider/model", SESSION_A)
                    assertTrue(probe.started.await(5, TimeUnit.SECONDS))
                    assertTrue(vm.state.value.sending)
                    source.change(replacement)
                    assertTrue("Native invalidation must close blocked HTTP", probe.peerClosed.await(5, TimeUnit.SECONDS))
                    val after = settled(vm)
                    assertEquals("retain this silent request draft", after.draft)
                    assertTrue(after.notice.contains("未自动重发"))
                    assertEquals(1, source.closes.get())
                    runBlocking(Dispatchers.IO) {
                        assertNull(db.chatDao().runningOperation(id))
                        val failed = repo.messages(id)
                        assertEquals(listOf("user", "error"), failed.map { it.role })
                        assertTrue(failed.all { it.state == "FAILED" })
                        assertTrue(repo.context(id).isEmpty())
                    }
                    vm.refresh(); settled(vm)
                    assertEquals(1, probe.requests.get())
                    assertEquals(1, source.captures.get())
                }
            }
        }
    }

    @Test fun invalidationPreservesFailedPreviewButNeverSavesPartialReasoningOrReplays() {
        Probe(reasoning = "visible unfinished reasoning", pauseAfterPreview = true).use { probe ->
            val source = LeaseSource(probe.config)
            val vm = vm(acquire = source::acquire)
            val id = checkNotNull(vm.state.value.selected).id
            vm.editDraft("preview must remain only a preview")
            vm.send("provider/model", SESSION_A)
            assertTrue(probe.started.await(5, TimeUnit.SECONDS))
            probe.release.countDown()
            runBlocking { withTimeout(5000) {
                vm.state.first { it.partial == "answer" && it.partialReasoning == "visible unfinished reasoning" }
            } }
            source.change(SESSION_A_NEXT)
            assertTrue(probe.peerClosed.await(5, TimeUnit.SECONDS))
            val failed = settled(vm)
            assertEquals("preview must remain only a preview", failed.draft)
            assertEquals("answer", failed.partial)
            assertEquals("visible unfinished reasoning", failed.partialReasoning)
            runBlocking(Dispatchers.IO) {
                val messages = repo.messages(id)
                assertEquals(listOf("user", "error"), messages.map { it.role })
                assertTrue(messages.all { it.state == "FAILED" && it.reasoning.isEmpty() })
                assertTrue(repo.context(id).isEmpty())
            }
            vm.refresh(); settled(vm)
            assertEquals("answer", vm.state.value.partial)
            assertEquals(1, source.captures.get())
            assertEquals(1, probe.requests.get())
            assertEquals(1, source.closes.get())
        }
    }

    @Test fun permittedFallbackUsesOriginalLeaseAndInvalidationClosesItsSilentSocket() {
        for (replacement in listOf(SESSION_A_NEXT, SESSION_B)) {
            for (sendHeaders in listOf(false, true)) {
                FallbackProbe(sendHeaders).use { probe ->
                    val source = LeaseSource(probe.config)
                    val vm = vm(acquire = source::acquire)
                    vm.newConversation(); settled(vm)
                    val id = checkNotNull(vm.state.value.selected).id
                    vm.editDraft("one stream and one permitted fallback")
                    vm.send("provider/model", SESSION_A)
                    assertTrue(probe.fallbackStarted.await(5, TimeUnit.SECONDS))
                    assertEquals(listOf(true, false), probe.streaming.toList())
                    assertTrue(vm.state.value.sending)
                    source.change(replacement)
                    assertTrue("The fallback must share the registered cancellation signal",
                        probe.peerClosed.await(5, TimeUnit.SECONDS))
                    val failed = settled(vm)
                    assertEquals("one stream and one permitted fallback", failed.draft)
                    assertTrue(failed.notice.contains("未自动重发"))
                    assertEquals(1, source.captures.get())
                    assertEquals(1, source.closes.get())
                    assertEquals(2, probe.requests.get())
                    runBlocking(Dispatchers.IO) {
                        assertNull(db.chatDao().runningOperation(id))
                        assertEquals(listOf("user", "error"), repo.messages(id).map { it.role })
                        assertTrue(repo.context(id).isEmpty())
                    }
                    vm.refresh(); settled(vm)
                    assertEquals(2, probe.requests.get())
                    assertEquals(1, source.captures.get())
                }
            }
        }
    }

    @Test fun completeGuardRejectsStaleLeaseButCanFinalizeFailedRoomTurn() = runBlocking(Dispatchers.IO) {
        val source = LeaseSource(GatewayConfig(gatewayBaseUrl = "http://127.0.0.1:1", gatewayKey = "synthetic"))
        val lease = source.acquire(SESSION_A)
        val id = repo.createConversation()
        val real = repo.turnStore(id)
        val entries = AtomicInteger()
        val committed = AtomicBoolean()
        val observed = object : ChatTurnStore by real {
            override fun complete(turnId: String, assistant: SessionRecord) {
                entries.incrementAndGet()
                real.complete(turnId, assistant)
            }
        }
        val guarded = cancellableTurnStore(observed, Job(), lease::ensureCurrent, { committed.set(true) })
        val turn = guarded.begin(SessionRecord(1, "user", "chat:p/m", "reject before storage"))
        source.change(SESSION_A_NEXT, invalidate = false)
        assertThrows(CancellationException::class.java) {
            guarded.complete(turn, SessionRecord(2, "assistant", "chat:p/m", "must not commit"))
        }
        assertFalse(committed.get())
        assertEquals(0, entries.get())
        guarded.fail(turn, SessionRecord(3, "error", "chat:p/m", "Chat failed; partial characters=0"))
        assertEquals("FAILED", db.chatDao().operation(turn)?.state)
        assertTrue(repo.context(id).isEmpty())
        lease.close()
    }

    @Test fun invalidationInsideSuccessfulCompleteKeepsAuthoritativeRoomOutcomeAndClearsDraft() {
        assertCommittedDraftOutcome(cancelSendJob = false)
    }

    @Test fun promptCancellationReturningFromCommittedIoClearsDraftWhileViewModelRemainsUsable() {
        assertCommittedDraftOutcome(cancelSendJob = true)
    }

    private fun assertCommittedDraftOutcome(cancelSendJob: Boolean) {
        Probe(reasoning = "saved reasoning").use { probe ->
            val source = LeaseSource(probe.config)
            val owner = AtomicReference<ChatViewModel>()
            val enteredComplete = CountDownLatch(1)
            val finishComplete = CountDownLatch(1)
            val vm = vm(turnStore = { repository, id ->
                val real = repository.turnStore(id)
                object : ChatTurnStore by real {
                    override fun complete(turnId: String, assistant: SessionRecord) {
                        // The guarded storage entry has already been accepted. Complete must win.
                        if (cancelSendJob) {
                            owner.get().viewModelScope.coroutineContext[Job]!!.children.single().cancel()
                        }
                        enteredComplete.countDown()
                        check(finishComplete.await(10, TimeUnit.SECONDS))
                        real.complete(turnId, assistant)
                    }
                }
            }, acquire = source::acquire)
            owner.set(vm)
            val id = checkNotNull(vm.state.value.selected).id
            try {
                vm.editDraft("success must not remain a retransmittable draft")
                vm.send("provider/model", SESSION_A)
                assertTrue(probe.started.await(5, TimeUnit.SECONDS))
                probe.release.countDown()
                assertTrue(enteredComplete.await(5, TimeUnit.SECONDS))
                if (!cancelSendJob) source.change(SESSION_A_NEXT)
                // Invalidating or cancelling cannot enable another send before this worker exits.
                assertTrue(vm.state.value.sending)
                vm.send("provider/model", SESSION_A_NEXT)
                assertEquals(1, source.captures.get())
                assertEquals(0, source.closes.get())
                finishComplete.countDown()
                val after = settled(vm)
                assertEquals("", after.draft)
                assertEquals("", after.partial)
                assertEquals("", after.partialReasoning)
                assertEquals("本轮已保存。", after.notice)
                runBlocking(Dispatchers.IO) {
                    val messages = repo.messages(id)
                    assertEquals(listOf("user", "assistant"), messages.map { it.role })
                    assertTrue(messages.all { it.state == "SUCCEEDED" })
                    assertEquals("saved reasoning", messages.last().reasoning)
                    assertNull(db.chatDao().runningOperation(id))
                }
                assertEquals(1, probe.requests.get())
                assertEquals(1, source.closes.get())
                // Scope itself was retained; history/selection refresh cannot restore the committed draft.
                vm.newConversation(); settled(vm)
                vm.selectConversation(id)
                assertEquals("", settled(vm).draft)
                assertEquals(1, probe.requests.get())
            } finally { finishComplete.countDown() }
        }
    }

    private class LeaseSource(private val config: GatewayConfig) {
        private val registry = GatewayRequestLeases()
        private val current = AtomicReference(SESSION_A)
        val captures = AtomicInteger()
        val closes = AtomicInteger()
        val controls = CopyOnWriteArrayList<cc.fplj.magpiemobile.core.ChatRequestControl>()

        fun acquire(key: ProviderSessionKey): GatewayRequestLease {
            if (key != current.get()) throw CancellationException("Stale synthetic session")
            captures.incrementAndGet()
            val lease = registry.acquire(config) { current.get() == key }
            controls.add(lease.control)
            return object : GatewayRequestLease by lease {
                override fun close() { closes.incrementAndGet(); lease.close() }
            }
        }

        fun change(key: ProviderSessionKey, invalidate: Boolean = true) {
            current.set(key)
            if (invalidate) registry.invalidate()
        }
    }

    /** Queues only when explicitly held; released work still runs off Robolectric's main thread. */
    private class GatedDispatcher : CoroutineDispatcher() {
        @Volatile var hold = false
        private val lock = Any()
        private val pending = ConcurrentLinkedQueue<Pair<CoroutineContext, Runnable>>()
        val queued: Int get() = pending.size
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val queued = synchronized(lock) {
                if (hold) { pending.add(context to block); true } else false
            }
            if (!queued) Dispatchers.IO.dispatch(context, block)
        }
        fun release() {
            val released = synchronized(lock) {
                hold = false
                buildList {
                    while (true) { add(pending.poll() ?: break) }
                }
            }
            released.forEach { (context, block) -> Dispatchers.IO.dispatch(context, block) }
        }
    }

    /** Actual HTTP 405 permits one non-stream request, which then stays silent until cancelled. */
    private class FallbackProbe(private val sendHeaders: Boolean) : AutoCloseable {
        private val server = ServerSocket(0)
        @Volatile private var accepted: Socket? = null
        val fallbackStarted = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        val requests = AtomicInteger()
        val streaming = CopyOnWriteArrayList<Boolean>()
        val config = GatewayConfig(gatewayBaseUrl = "http://127.0.0.1:" + server.localPort,
            gatewayKey = "synthetic-fallback-key", readTimeout = Duration.ofSeconds(30))
        private val worker = thread(isDaemon = true) {
            try {
                repeat(2) { attempt ->
                    server.accept().use { client ->
                        accepted = client
                        client.soTimeout = 15000
                        requests.incrementAndGet()
                        val input = client.getInputStream().buffered()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val value = input.read()
                            if (value < 0) return@use
                            header.append(value.toChar())
                        }
                        val length = header.lineSequence().firstOrNull { it.startsWith("Content-Length:", true) }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val body = ByteArray(length)
                        for (index in body.indices) body[index] = input.read().also { check(it >= 0) }.toByte()
                        streaming.add(String(body, Charsets.UTF_8).contains("\"stream\":true"))
                        if (attempt == 0) {
                            client.getOutputStream().apply {
                                write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                flush()
                            }
                        } else {
                            if (sendHeaders) client.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 128\r\nConnection: close\r\n\r\n".toByteArray())
                                flush()
                            }
                            fallbackStarted.countDown()
                            try {
                                if (input.read() < 0) peerClosed.countDown()
                            } catch (_: SocketException) { peerClosed.countDown() }
                        }
                    }
                }
            } catch (_: java.io.IOException) { /* Listener/peer closes during test cleanup. */ }
        }
        override fun close() {
            accepted?.close()
            server.close()
            worker.join(5000)
        }
    }

    /** The server never releases a response: only cancellation can close the client promptly. */
    private class SilentProbe(private val sendHeaders: Boolean) : AutoCloseable {
        private val server = ServerSocket(0)
        @Volatile private var accepted: Socket? = null
        val started = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        val requests = AtomicInteger()
        val config = GatewayConfig(gatewayBaseUrl = "http://127.0.0.1:" + server.localPort,
            gatewayKey = "synthetic-silent-request-key", readTimeout = Duration.ofSeconds(30))
        private val worker = thread(isDaemon = true) {
            try {
                server.accept().use { client ->
                    accepted = client
                    client.soTimeout = 15000
                    requests.incrementAndGet()
                    val input = client.getInputStream().buffered()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val value = input.read()
                        if (value < 0) return@use
                        header.append(value.toChar())
                    }
                    val length = header.lineSequence().firstOrNull { it.startsWith("Content-Length:", true) }
                        ?.substringAfter(':')?.trim()?.toInt() ?: 0
                    repeat(length) { check(input.read() >= 0) }
                    if (sendHeaders) {
                        client.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                                "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
                            flush()
                        }
                    }
                    started.countDown()
                    try {
                        if (input.read() < 0) peerClosed.countDown()
                    } catch (_: SocketException) { peerClosed.countDown() }
                }
            } catch (_: SocketException) { /* Test cleanup closes the listener or accepted socket. */ }
        }
        override fun close() {
            accepted?.close()
            server.close()
            worker.join(5000)
        }
    }

    /** Local synthetic SSE only: no vendor key, DNS, inference, or public network. */
    private class Probe(
        val success: Boolean = true,
        val reasoning: String? = null,
        private val pauseAfterPreview: Boolean = false,
    ) : AutoCloseable {
        private val socket = ServerSocket(0)
        @Volatile private var active: Socket? = null
        val peerClosed = CountDownLatch(1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val bodies = CopyOnWriteArrayList<String>()
        val config = GatewayConfig(gatewayBaseUrl = "http://127.0.0.1:" + socket.localPort,
            gatewayKey = "synthetic-gateway-secret", readTimeout = Duration.ofSeconds(10))
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val accepted = try { socket.accept() } catch (_: java.net.SocketException) { break }
                requests.incrementAndGet()
                active = accepted
                try { accepted.use { client ->
                val input = client.getInputStream().buffered()
                val requestHeader = StringBuilder()
                while (!requestHeader.endsWith("\r\n\r\n")) {
                    val value = input.read()
                    if (value < 0) return@use
                    requestHeader.append(value.toChar())
                }
                val length = requestHeader.lineSequence()
                    .firstOrNull { it.startsWith("Content-Length:", true) }
                    ?.substringAfter(':')?.trim()?.toInt()
                    ?: 0
                val body = ByteArray(length)
                for (index in body.indices) {
                    val value = input.read()
                    if (value < 0) return@use
                    body[index] = value.toByte()
                }
                bodies.add(String(body, Charsets.UTF_8))
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                val reasoningBody = reasoning?.let {
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"$it\"}}]}\n\n"
                }.orEmpty()
                val preview = reasoningBody + "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n"
                val suffix = if (success) "data: [DONE]\n\n" else ""
                val bytes = (preview + suffix).toByteArray()
                val header = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n"
                client.getOutputStream().apply {
                    write(header.toByteArray())
                    write(if (pauseAfterPreview) preview.toByteArray() else bytes)
                    flush()
                }
                if (pauseAfterPreview) {
                    try {
                        if (input.read() < 0) peerClosed.countDown()
                    } catch (_: SocketException) { peerClosed.countDown() }
                }
                } } catch (_: java.io.IOException) { /* Cancellation/cleanup closes the peer. */ }
            }
        }
        override fun close() {
            release.countDown()
            active?.close()
            socket.close()
            worker.join(10000)
        }
    }

    private companion object {
        val SESSION_A = ProviderSessionKey(81, 1)
        val SESSION_A_NEXT = ProviderSessionKey(81, 2)
        val SESSION_B = ProviderSessionKey(82, 1)
    }

}
