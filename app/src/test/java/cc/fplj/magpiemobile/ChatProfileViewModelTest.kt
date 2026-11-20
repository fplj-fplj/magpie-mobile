package cc.fplj.magpiemobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.fplj.magpiemobile.core.ChatProfile
import cc.fplj.magpiemobile.core.ChatProfileStore
import java.io.File
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ChatProfileViewModelTest {
    @Rule @JvmField val temporary = TemporaryFolder()
    private lateinit var app: Application
    private lateinit var file: File

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        file = File(temporary.root, "profiles.json")
    }

    private fun vm() = ChatProfileViewModel(app, ChatProfileStore(file))

    @Test
    fun defaultProfileIsInitiallySelected() {
        val vm = vm()

        val state = vm.state.value
        assertEquals("default", state.selectedProfileId)
        assertEquals("通用助手", state.activeProfile.name)
        assertEquals(0.7, state.activeProfile.temperature, 0.001)
        assertTrue(state.canWriteProfiles)
        assertEquals("", state.storageError)
    }

    @Test
    fun saveAndSelectCustomProfile() {
        val vm = vm()

        assertTrue(vm.saveProfile(
            id = "creative",
            name = "创意写作",
            systemPrompt = "You are a creative writer.",
            temperature = 1.2,
            topP = 0.9,
            modelAlias = "gpt-creative"
        ))

        val state = vm.state.value
        assertEquals("creative", state.selectedProfileId)
        assertEquals("创意写作", state.activeProfile.name)
        assertEquals(1.2, state.activeProfile.temperature, 0.001)
        assertEquals("gpt-creative", state.activeProfile.modelAlias)
        assertTrue(state.notice.contains("创意写作"))
    }

    @Test
    fun deleteCustomProfileRevertsToDefault() {
        val vm = vm()

        assertTrue(vm.saveProfile("temp", "Temp Profile", temperature = 0.5))
        assertEquals("temp", vm.state.value.selectedProfileId)

        assertTrue(vm.deleteProfile("temp"))
        assertEquals("default", vm.state.value.selectedProfileId)
    }

    @Test
    fun deleteDefaultProfileSetsError() {
        val vm = vm()

        assertFalse(vm.deleteProfile("default"))
        assertTrue(vm.state.value.error.contains("不允许删除"))
    }

    @Test fun invalidTemperatureIsRejectedWithFixedErrorAndDoesNotWrite() {
        val vm = vm()
        for (temperature in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 3.0, -0.1)) {
            assertFalse(vm.saveProfile("test", "Test", temperature = temperature))
            assertEquals(ChatProfileFormState.TEMPERATURE_ERROR, vm.state.value.error)
            assertTrue(vm.state.value.canWriteProfiles)
            assertFalse(file.exists())
            assertEquals(listOf(ChatProfile.DEFAULT), vm.state.value.profiles)
        }
    }

    @Test fun invalidProfileDoesNotExposeRejectedInputOrLockHealthyStorage() {
        val vm = vm()
        assertFalse(vm.saveProfile("invalid/path/marker", "Test"))
        assertEquals(ChatProfileViewModel.INVALID_PROFILE_ERROR, vm.state.value.error)
        assertTrue(vm.state.value.canWriteProfiles)
        assertFalse(file.exists())
    }

    @Test fun corruptInitialSourceDoesNotCrashOrAllowOverwriteBeforeExplicitRetry() {
        val source = "[{\"id\":\"private-source-marker\",\"name\":".toByteArray()
        file.writeBytes(source)
        val vm = vm()
        assertTrue(vm.state.value.profiles.isEmpty())
        assertEquals(ChatProfileViewModel.STORAGE_ERROR, vm.state.value.storageError)
        assertFalse(vm.state.value.canWriteProfiles)
        assertFalse(vm.saveProfile("new", "New"))
        assertFalse(vm.deleteProfile("default"))
        assertTrue(source.contentEquals(file.readBytes()))
        assertFalse(vm.retryReload())
        assertTrue(source.contentEquals(file.readBytes()))

        // Repairing the file alone must not silently release the ViewModel's read gate.
        file.writeText("[]")
        vm.selectProfile("default")
        vm.clearNotice()
        assertEquals(ChatProfileViewModel.STORAGE_ERROR, vm.state.value.storageError)
        assertFalse(vm.saveProfile("new", "New"))
        assertFalse(vm.deleteProfile("default"))
        assertEquals("[]", file.readText())
        assertTrue(vm.retryReload())
        assertTrue(vm.state.value.canWriteProfiles)
        assertEquals("", vm.state.value.storageError)
        assertEquals(listOf(ChatProfile.DEFAULT), vm.state.value.profiles)
        assertTrue(vm.saveProfile("new", "New"))
    }

    @Test fun selectionAndClearNoticeKeepReadFailureAndLastLoadedProfiles() {
        val vm = vm()
        assertTrue(vm.saveProfile("creative", "Creative"))
        val lastLoaded = vm.state.value.profiles
        val source = "private-invalid-json-marker".toByteArray()
        file.writeBytes(source)
        assertFalse(vm.retryReload())
        val failure = vm.state.value.storageError

        vm.selectProfile("default")
        vm.clearNotice()
        assertEquals("default", vm.state.value.selectedProfileId)
        assertEquals(failure, vm.state.value.storageError)
        assertEquals(lastLoaded, vm.state.value.profiles)
        assertFalse(vm.state.value.canWriteProfiles)
        assertFalse(vm.saveProfile("new", "New"))
        assertFalse(vm.deleteProfile("creative"))
        assertTrue(source.contentEquals(file.readBytes()))
    }

    @Test fun initialFilesystemReadFailureDoesNotCrashOrTreatDirectoryAsMissingSource() {
        assertTrue(file.mkdir())
        val vm = vm()
        assertEquals(ChatProfileViewModel.STORAGE_ERROR, vm.state.value.storageError)
        assertTrue(vm.state.value.profiles.isEmpty())
        assertFalse(vm.retryReload())
        assertFalse(vm.saveProfile("new", "New"))
        assertFalse(vm.deleteProfile("default"))
        assertTrue(file.isDirectory)
    }

    @Test fun saveFailureReturnsFalseAndUsesSafeMessagesWhileKeepingLoadedSelection() {
        val vm = vm()
        assertTrue(vm.saveProfile("existing", "Existing"))
        val previous = vm.state.value.profiles
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        val untouched = File(file, "private-directory-marker").apply { writeText("keep me") }

        assertFalse(vm.saveProfile("next", "Next", systemPrompt = "keep my prompt"))
        assertEquals(previous, vm.state.value.profiles)
        assertEquals("existing", vm.state.value.selectedProfileId)
        assertEquals(ChatProfileViewModel.SAVE_ERROR, vm.state.value.error)
        assertEquals(ChatProfileViewModel.STORAGE_ERROR, vm.state.value.storageError)
        assertFalse(vm.state.value.canWriteProfiles)
        assertEquals("keep me", untouched.readText())
        vm.clearNotice()
        vm.selectProfile("default")
        assertEquals(ChatProfileViewModel.STORAGE_ERROR, vm.state.value.storageError)
        assertFalse(vm.deleteProfile("existing"))
    }

    @Test fun deleteReadFailureIsSafeAndRequiresRetryBeforeMutation() {
        val vm = vm()
        assertTrue(vm.saveProfile("existing", "Existing"))
        val previous = vm.state.value.profiles
        val source = "{private-invalid-json-marker".toByteArray()
        file.writeBytes(source)
        assertFalse(vm.deleteProfile("existing"))
        assertEquals(previous, vm.state.value.profiles)
        assertEquals(ChatProfileViewModel.DELETE_ERROR, vm.state.value.error)
        assertEquals(ChatProfileViewModel.STORAGE_ERROR, vm.state.value.storageError)
        assertTrue(source.contentEquals(file.readBytes()))
    }

    @Test fun validReloadKeepsExistingSelectionAndRestoresDeletedSelectionToDefault() {
        val vm = vm()
        assertTrue(vm.saveProfile("existing", "Existing"))
        assertTrue(vm.retryReload())
        assertEquals("existing", vm.state.value.selectedProfileId)
        file.writeText("[]")
        assertTrue(vm.retryReload())
        assertEquals("default", vm.state.value.selectedProfileId)
        assertEquals(ChatProfile.DEFAULT, vm.state.value.activeProfile)
    }

    @Test fun formKeepsLongPromptForValidationAndStorageFailuresAndClearsOnlyOnSuccess() {
        val vm = vm()
        val draft = ChatProfileFormState(
            editing = true, id = "helper", name = "编程助手", prompt = "保留这一段提示词\n".repeat(1500) + "末行", temperature = "0.2",
        )
        fun submit(form: ChatProfileFormState) = form.submit { id, name, prompt, temperature ->
            vm.saveProfile(id, name, prompt, temperature)
        }

        val invalidId = draft.copy(id = "invalid id")
        assertEquals(invalidId, submit(invalidId))
        assertEquals(ChatProfileViewModel.INVALID_PROFILE_ERROR, vm.state.value.error)
        val invalidTemperature = draft.copy(temperature = "abc")
        assertEquals(invalidTemperature.copy(error = ChatProfileFormState.TEMPERATURE_ERROR), submit(invalidTemperature))
        assertFalse(file.exists())

        assertTrue(file.mkdir())
        assertEquals(draft, submit(draft))
        assertEquals(ChatProfileViewModel.SAVE_ERROR, vm.state.value.error)
        assertTrue(file.delete())
        assertEquals(draft, submit(draft))
        assertFalse(file.exists())
        assertTrue(vm.retryReload())
        assertEquals(ChatProfileFormState(), submit(draft))
        assertEquals(draft.prompt, vm.state.value.activeProfile.systemPrompt)
    }
}
