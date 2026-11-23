package cc.fplj.magpiemobile

import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Xml
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import cc.fplj.magpiemobile.core.ProviderCreateDraft
import cc.fplj.magpiemobile.core.ProviderEditDraft
import cc.fplj.magpiemobile.core.ProviderEditSnapshot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * CI-only candidate: run alone on the prepared disposable AVD, after --prepare-browser.
 * All web interactions use Chrome's real accessibility UI. No embedded WebView, cookie extraction,
 * browser-entry HTTP request, JS injection, synthetic File, or direct import RPC is used.
 * The first real CI run must calibrate the explicitly documented web/picker selectors.
 */
@RunWith(AndroidJUnit4::class)
class BrowserBackupInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val evidenceDirectoryName = "browser-backup-${SystemClock.elapsedRealtime()}"
    private val artifacts = File(context.getExternalFilesDir(null), "plugin-runtime/$evidenceDirectoryName")
    private val secrets = mutableListOf<String>()
    private var phase = "initializing"
    private var cleanRedirectObserved = false
    private var downloaded: Download? = null
    private var downloadRetryClicked = false
    private var privateArchiveChecks = 0
    private var passed = false
    private var failureHierarchyCaptured = false
    private var webControlOrdinal = 0
    private var webControlTrace = JSONArray()
    private var lastWebObservation: JSONObject? = null
    private var wrongPhraseStatePreserved = false
    private var correctPhraseStateRestored = false
    private var wrongPhraseErrorObserved = false
    private var browserContentTreeLostAfterWrongPhrase = false
    private var browserContentTreeLostAfterCorrectPhrase = false

    @Test fun officialBrowserBackupRoundTripUsesRealDownloadedFile() {
        // Never pass these via instrumentation arguments or write their values to evidence.
        val phrase = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        val wrongPhrase = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        val fixtureKey = UUID.randomUUID().toString()
        secrets += listOf(phrase, wrongPhrase, fixtureKey)
        var failureType: String? = null
        var failureReason: FailureReason? = null
        var failureLine: Int? = null
        try {
            check(phrase != wrongPhrase) { "distinct runtime phrases required" }
            check(android.os.Build.FINGERPRINT.startsWith("google/sdk_gphone") &&
                "x86_64" in android.os.Build.SUPPORTED_ABIS) { "dedicated Google x86_64 AVD required" }
            artifacts.mkdirs()
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName, android.Manifest.permission.POST_NOTIFICATIONS,
                )
            }
            device.setOrientationNatural()
            ActivityScenario.launch(MainActivity::class.java).use {
                try {
                    phase = "starting_gateway"
                    await(30_000) { device.hasObject(By.res("gateway-power-switch")) }
                    val power = requireNotNull(device.findObject(By.res("gateway-power-switch")))
                    if (!power.isChecked) power.click()
                    await(120_000) { NativeCoreSession.status.value.ready }
                    val session = requireNotNull(NativeCoreSession.providerSessionKey())
                    accept(NativeCoreSession.managementClient().capabilities().coreVersion == "0.1.3781-androidhost.4",
                        FailureReason.CORE_VERSION_MISMATCH)
                    accept(NativeCoreSession.providerSessionKey() == session, FailureReason.CORE_SESSION_CHANGED)

                    phase = "creating_A_via_production_RPC"
                    rpc(session) { client ->
                        check(client.list().providers.none { it.id == FIXTURE_ID }) { "fresh fixture required" }
                        val draft = ProviderCreateDraft(FIXTURE_ID, A_NAME, A_URL, fixtureKey, listOf(A_MODEL))
                        try { client.create(draft) } finally { draft.clearSecret() }
                    }
                    assertFixture(session, before = true)
                    val baseline = completeDownloads().keys
                    val privateArchiveBaseline = privateBackupArchives()

                    phase = "opening_production_browser_button"
                    clickNamed(context.packageName, listOf("提供商"))
                    revealNativeBrowserButton().click()
                    // This click is NativeNavigation's actual ACTION_VIEW path. CI preparation
                    // only warmed a harmless page and cannot replace this authenticated entry.
                    await(30_000) {
                        rejectBrowserFirstRun()
                        device.currentPackageName == CHROME && device.hasObject(By.res(CHROME, "url_bar"))
                    }
                    confirmOfficialPage(CLEAN_ENTRY_URL)
                    cleanRedirectObserved = true
                    safeHierarchy("browser-backup-clean-entry.xml")

                    phase = "opening_official_sync_settings"
                    openSyncSettings().click()
                    openWebForm(EXPORT_OPEN, expectedPasswords = 2)
                    phase = "exporting_via_official_web_form"
                    setRealPasswordFields(listOf(phrase, phrase))
                    revealWebControlByScroll(EXPORT_SUBMIT, allowClipped = true)
                    clickWebControl(EXPORT_SUBMIT, allowClipped = true)
                    downloaded = awaitRealDownload(baseline)
                    assertPrivateArchivesUnchanged(privateArchiveBaseline)
                    safeHierarchy("browser-backup-after-download.xml")

                    phase = "changing_A_to_B_via_same_connection_RPC"
                    rpc(session) { client ->
                        val draft = ProviderEditDraft(client.editable(FIXTURE_ID),
                            name = B_NAME, chatUrl = B_URL, modelIds = listOf(B_MODEL))
                        try { client.edit(draft) } finally { draft.clearSecret() }
                    }
                    assertFixture(session, before = false)

                    phase = "choosing_real_download_from_official_import"
                    openWebForm(IMPORT_OPEN, expectedPasswords = 1)
                    clickWebControl(CHOOSE)
                    chooseActualDownload(requireNotNull(downloaded))
                    awaitStableImportForm(requireNotNull(downloaded).name)
                    assertDownloadUnchanged()
                    safeHierarchy("browser-backup-real-file-selected.xml")

                    phase = "wrong_phrase_must_preserve_B"
                    setRealPasswordFields(listOf(wrongPhrase))
                    clickVisibleWebControl(IMPORT_SUBMIT)
                    // Chrome 124 can drop its WebView accessibility subtree after
                    // a real web click. The import request still runs. Wait for
                    // the sealed-file failure, then prove rejection through the
                    // same-session production API instead of a transient DOM node.
                    SystemClock.sleep(10_000)
                    wrongPhraseErrorObserved = hasWebText(WRONG_PHRASE_ERROR)
                    browserContentTreeLostAfterWrongPhrase = webViewOrNull() == null
                    accept(!hasImportSuccess(), FailureReason.WRONG_PHRASE_ACCEPTED)
                    assertFixture(session, before = false)
                    SystemClock.sleep(500)
                    assertFixture(session, before = false)
                    assertDownloadUnchanged()
                    assertPrivateArchivesUnchanged(privateArchiveBaseline)
                    wrongPhraseStatePreserved = true
                    safeHierarchy("browser-backup-wrong-phrase-rejected.xml")

                    phase = "reopening_official_import_for_correct_phrase"
                    // Chrome can keep the form alive while hiding its complete
                    // accessibility subtree. Reload the official page in the
                    // same real-browser tab so its sessionStorage proof remains
                    // available, then choose the same public download again.
                    reopenOfficialTab()
                    openSyncSettings().click()
                    openWebForm(IMPORT_OPEN, expectedPasswords = 1)
                    clickWebControl(CHOOSE)
                    chooseActualDownload(requireNotNull(downloaded))
                    awaitStableImportForm(requireNotNull(downloaded).name)
                    assertDownloadUnchanged()
                    phase = "correct_phrase_must_restore_A"
                    setRealPasswordFields(listOf(phrase))
                    clickVisibleWebControl(IMPORT_SUBMIT)
                    browserContentTreeLostAfterCorrectPhrase = webViewOrNull() == null
                    await(60_000, FailureReason.RESTORE_RESULT_NOT_REACHED) {
                        fixtureMatches(session, before = true)
                    }
                    assertFixture(session, before = true)
                    correctPhraseStateRestored = true
                    assertDownloadUnchanged()
                    assertPrivateArchivesUnchanged(privateArchiveBaseline)
                    // The successful import may have removed the content tree.
                    // Reload this proof-bearing tab before final evidence.
                    reopenOfficialTab()
                    openSyncSettings().click()
                    confirmOfficialSyncContent()
                    safeHierarchy("browser-backup-restored.xml")
                    // Import replaces the form; never capture a password form or a ticket URL.
                    val raw = hierarchyBytes()
                    check(!String(raw, Charsets.UTF_8).contains("password=\"true\"")) {
                        "password control remains; screenshot refused"
                    }
                    check(secrets.none { String(raw, Charsets.UTF_8).contains(it) }) {
                        "secret remains; screenshot refused"
                    }
                    check(device.takeScreenshot(File(artifacts, "browser-backup-restored.png"))) {
                        "final screenshot failed"
                    }
                    passed = true
                    phase = "passed"
                } catch (failure: Throwable) {
                    // Preserve the real browser/picker state before use closes the Activity.
                    // Raw nodes stay in memory; only the existing allowlisted XML is saved.
                    try {
                        safeHierarchy("browser-backup-final-sanitized.xml")
                        failureHierarchyCaptured = true
                    } catch (_: Throwable) {
                        // Keep the operation failure; the outer fallback can still collect.
                    }
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            // Do not propagate the original exception: framework/HTTP text can include inputs.
            failureType = failure.javaClass.simpleName
            failureReason = (failure as? AcceptanceFailure)?.reason ?: FailureReason.UNEXPECTED_EXCEPTION
            // Source location only: exception messages/causes can contain form inputs.
            failureLine = failure.stackTrace.firstOrNull {
                it.className == BrowserBackupInstrumentedTest::class.java.name
            }?.lineNumber?.takeIf { it in 1..20_000 }
        }
        try {
            if (!failureHierarchyCaptured) safeHierarchy("browser-backup-final-sanitized.xml")
        } catch (failure: Throwable) {
            failureType = failureType ?: failure.javaClass.simpleName
            failureReason = failureReason ?: FailureReason.EVIDENCE_SANITIZATION_FAILED
        }
        try {
            val record = JSONObject()
                .put("status", if (passed && failureType == null) "PASS" else "FAIL")
                .put("phase", phase)
                .put("failureType", failureType ?: JSONObject.NULL)
                .put("failureReason", failureReason?.name ?: JSONObject.NULL)
                .put("failureLine", failureLine ?: JSONObject.NULL)
                .put("cleanRedirectObserved", cleanRedirectObserved)
                .put("realDownloadObserved", downloaded != null)
                .put("downloadRetryClicked", downloadRetryClicked)
                .put("privateArchiveChecks", privateArchiveChecks)
                .put("browserPackage", CHROME)
                .put("webControlOrdinal", webControlOrdinal)
                .put("webControlTrace", webControlTrace)
                .put("lastWebObservation", lastWebObservation ?: JSONObject.NULL)
                .put("wrongPhraseStatePreserved", wrongPhraseStatePreserved)
                .put("correctPhraseStateRestored", correctPhraseStateRestored)
                .put("wrongPhraseErrorObserved", wrongPhraseErrorObserved)
                .put("browserContentTreeLostAfterWrongPhrase", browserContentTreeLostAfterWrongPhrase)
                .put("browserContentTreeLostAfterCorrectPhrase", browserContentTreeLostAfterCorrectPhrase)
                .put("scope", "Real Chrome UI, public Downloads, real system picker, production same-session RPC")
                .put("keyEqualityClaimed", false)
            downloaded?.let {
                record.put("download", JSONObject().put("name", it.name).put("bytes", it.bytes).put("sha256", it.sha256))
            }
            File(artifacts, "browser-backup-result.json").writeText(record.toString(2))
            archiveEvidence()
        } catch (failure: Throwable) {
            failureType = failureType ?: failure.javaClass.simpleName
            failureReason = failureReason ?: FailureReason.EVIDENCE_WRITE_OR_ARCHIVE_FAILED
        } finally {
            secrets.clear()
        }
        if (!passed || failureType != null) {
            throw AssertionError("Browser backup acceptance failed at $phase (${failureReason?.name ?: "INCOMPLETE"}, ${failureType ?: "incomplete"}); see sanitized evidence")
        }
    }

    private fun <T> rpc(session: ProviderSessionKey, action: (cc.fplj.magpiemobile.core.ProviderManagementClient) -> T): T {
        accept(NativeCoreSession.providerSessionKey() == session, FailureReason.CORE_SESSION_CHANGED)
        return runBlocking { withProviderRequest(session, action = action) }.also {
            accept(NativeCoreSession.providerSessionKey() == session, FailureReason.CORE_SESSION_CHANGED)
        }
    }

    private fun assertFixture(session: ProviderSessionKey, before: Boolean) {
        val value: ProviderEditSnapshot = rpc(session) { it.editable(FIXTURE_ID) }
        accept(value.id == FIXTURE_ID && value.name == (if (before) A_NAME else B_NAME), FailureReason.FIXTURE_NAME_MISMATCH)
        accept(value.chatUrl == (if (before) A_URL else B_URL), FailureReason.FIXTURE_URL_MISMATCH)
        accept(value.modelIds == listOf(if (before) A_MODEL else B_MODEL), FailureReason.FIXTURE_MODELS_MISMATCH)
        accept(!value.noModels && value.keySet && value.serverPin.isEmpty(), FailureReason.FIXTURE_METADATA_MISMATCH)
        // keySet is only an existence assertion. B never changes the credential.
    }

    private fun fixtureMatches(session: ProviderSessionKey, before: Boolean): Boolean {
        val value: ProviderEditSnapshot = rpc(session) { it.editable(FIXTURE_ID) }
        return value.id == FIXTURE_ID && value.name == (if (before) A_NAME else B_NAME) &&
            value.chatUrl == (if (before) A_URL else B_URL) &&
            value.modelIds == listOf(if (before) A_MODEL else B_MODEL) &&
            !value.noModels && value.keySet && value.serverPin.isEmpty()
    }

    private fun revealNativeBrowserButton(): UiObject2 {
        var button: UiObject2? = null
        var scrollAttempts = 0
        await(30_000, FailureReason.NATIVE_BROWSER_BUTTON_NOT_REACHED) {
            button = namedControl(context.packageName, listOf("浏览器编辑"))
            if (button != null) {
                true
            } else {
                // A busy provider page can fit on screen and have no scrollable node.
                // Keep waiting for its real enabled action; scroll only when possible.
                if (scrollAttempts < 8) {
                    device.findObjects(By.pkg(context.packageName).scrollable(true))
                        .maxByOrNull { it.visibleBounds.height() }
                        ?.let { scrollable ->
                            scrollAttempts += 1
                            scrollable.scroll(Direction.DOWN, .65f)
                        }
                }
                false
            }
        }
        return requireNotNull(button)
    }

    private fun rejectBrowserFirstRun() {
        accept(!device.hasObject(By.res(CHROME, "fre_pager")) &&
            !device.hasObject(By.res(CHROME, "signin_fre_dismiss_button")), FailureReason.BROWSER_SETUP_REQUIRED)
    }

    private fun reopenOfficialTab() {
        rejectBrowserFirstRun()
        accept(device.currentPackageName == CHROME, FailureReason.INDEPENDENT_BROWSER_NOT_FOREGROUND)
        // The browser proof lives in tab-scoped sessionStorage. Keep using
        // this tab instead of opening a new tab, which would lose that proof.
        await(5_000, FailureReason.OFFICIAL_BROWSER_NAVIGATION_REJECTED) {
            device.currentPackageName == CHROME && chromeNavigationField() != null
        }
        requireNotNull(chromeNavigationField()).click()
        await(5_000, FailureReason.OFFICIAL_BROWSER_NAVIGATION_REJECTED) {
            chromeNavigationField()?.isFocused == true
        }
        requireNotNull(chromeNavigationField()).setText(CLEAN_ENTRY)
        await(5_000, FailureReason.OFFICIAL_BROWSER_NAVIGATION_REJECTED) {
            CLEAN_ENTRY_URL.matches(chromeNavigationField()?.text?.trim().orEmpty())
        }
        accept(device.pressKeyCode(KeyEvent.KEYCODE_ENTER), FailureReason.OFFICIAL_BROWSER_NAVIGATION_REJECTED)
        await(30_000, FailureReason.OFFICIAL_UI_NOT_RECOGNIZED) {
            device.currentPackageName == CHROME && webViewOrNull() != null
        }
        dismissWebImeOnce()
        var backPresses = 0
        var nextBackAt = 0L
        await(5_000, FailureReason.OFFICIAL_BROWSER_NAVIGATION_REJECTED) {
            val field = chromeNavigationField()
            val suggestionsVisible = device.hasObject(By.res(CHROME, "omnibox_suggestions_dropdown"))
            if (field != null && !field.isFocused && !suggestionsVisible) true else {
                val now = SystemClock.elapsedRealtime()
                if ((field?.isFocused == true || suggestionsVisible) && backPresses < 2 && now >= nextBackAt) {
                    device.pressBack()
                    backPresses += 1
                    nextBackAt = SystemClock.elapsedRealtime() + 1_000
                }
                false
            }
        }
        confirmOfficialPageContent()
    }

    private fun confirmOfficialPageContent() {
        rejectBrowserFirstRun()
        accept(device.currentPackageName == CHROME, FailureReason.INDEPENDENT_BROWSER_NOT_FOREGROUND)
        // A fresh tab already received the exact public entry URL above. Do not
        // reopen Chrome's omnibox here: on the new-tab search field that leaves
        // the page behind a suggestions layer even though the official UI is loaded.
        await(30_000, FailureReason.OFFICIAL_UI_NOT_RECOGNIZED) {
            device.currentPackageName == CHROME &&
                chromeNavigationField() != null &&
                webViewOrNull() != null &&
                namedControl(CHROME, SETTINGS) != null
        }
    }

    private fun confirmOfficialSyncContent() {
        rejectBrowserFirstRun()
        accept(device.currentPackageName == CHROME, FailureReason.INDEPENDENT_BROWSER_NOT_FOREGROUND)
        await(30_000, FailureReason.OFFICIAL_UI_NOT_RECOGNIZED) {
            val exports = webButtonNodes(EXPORT_OPEN)
            val imports = webButtonNodes(IMPORT_OPEN)
            try {
                accept(exports.size <= 1 && imports.size <= 1, FailureReason.AMBIGUOUS_ACCESSIBLE_CONTROL)
                device.currentPackageName == CHROME &&
                    webViewOrNull() != null &&
                    namedControl(CHROME, SETTINGS) != null &&
                    exports.size == 1 &&
                    imports.size == 1
            } finally {
                exports.forEach { it.recycle() }
                imports.forEach { it.recycle() }
            }
        }
    }

    private fun confirmOfficialPage(expectedUrl: Regex) {
        rejectBrowserFirstRun()
        accept(device.currentPackageName == CHROME, FailureReason.INDEPENDENT_BROWSER_NOT_FOREGROUND)
        // Chrome already has the harmless preparation tab. Its toolbar/WebView alone
        // cannot prove that the production ACTION_VIEW has reached the official UI.
        await(30_000, FailureReason.OFFICIAL_UI_NOT_RECOGNIZED) {
            device.hasObject(By.res(CHROME, "url_bar")) && webViewOrNull() != null &&
                namedControl(CHROME, SETTINGS) != null
        }
        // Chrome 124 opens a search-ready omnibox. Its first current-page suggestion's
        // exact Edit action refines the full URL into the field without navigating
        // (EditUrlSuggestionProcessor -> onRefineSuggestion -> setOmniboxEditingText).
        // Never validate the unfocused display text: Chrome can shorten that value.
        requireNotNull(device.findObject(By.res(CHROME, "url_bar"))).click()
        var editCurrentUrl: UiObject2? = null
        await(5_000, FailureReason.CURRENT_URL_EDIT_ACTION_NOT_REACHED) {
            val dropdowns = device.findObjects(By.res(CHROME, "omnibox_suggestions_dropdown"))
            accept(dropdowns.size <= 1, FailureReason.AMBIGUOUS_ACCESSIBLE_CONTROL)
            val firstSuggestion = dropdowns.singleOrNull()?.children?.firstOrNull()
            val hasCurrentPageLines = firstSuggestion?.findObject(By.res(CHROME, "line_1")) != null &&
                firstSuggestion.findObject(By.res(CHROME, "line_2")) != null
            val actions = if (hasCurrentPageLines) firstSuggestion!!.findObjects(
                By.pkg(CHROME).clazz("android.widget.ImageView").desc("Edit").enabled(true).clickable(true),
            ).filter { !it.visibleBounds.isEmpty } else emptyList()
            accept(actions.size <= 1, FailureReason.AMBIGUOUS_ACCESSIBLE_CONTROL)
            editCurrentUrl = actions.singleOrNull()
            editCurrentUrl != null
        }
        requireNotNull(editCurrentUrl).click()
        // Inspect only the actual focused field, in memory; never save a ticket URL.
        await(5_000, FailureReason.CLEAN_OFFICIAL_URL_NOT_REACHED) {
            val field = device.findObject(By.res(CHROME, "url_bar"))
            field?.isFocused == true && expectedUrl.matches(field.text?.trim().orEmpty())
        }
        // On this Chrome/Android combination the first Back can dismiss only the IME.
        // Send at most two, each guarded by the still-focused native suggestion UI;
        // never send Back after the page is visible and risk traversing its history.
        var backPresses = 0
        var nextBackAt = 0L
        await(5_000, FailureReason.OFFICIAL_PAGE_NOT_RESTORED) {
            accept(device.currentPackageName == CHROME, FailureReason.INDEPENDENT_BROWSER_NOT_FOREGROUND)
            val field = device.findObject(By.res(CHROME, "url_bar"))
            val suggestionsVisible = device.hasObject(By.res(CHROME, "omnibox_suggestions_dropdown"))
            if (field != null && !field.isFocused && !suggestionsVisible && webViewOrNull() != null &&
                namedControl(CHROME, SETTINGS) != null) {
                true
            } else {
                val now = SystemClock.elapsedRealtime()
                if (field?.isFocused == true && suggestionsVisible && backPresses < 2 && now >= nextBackAt) {
                    device.pressBack()
                    backPresses += 1
                    nextBackAt = SystemClock.elapsedRealtime() + 1_000
                }
                false
            }
        }
    }

    private fun openSyncSettings(): UiObject2 {
        // The pinned phone layout wraps its tabs; it has no horizontal tab scroller.
        // Chrome's native tab text need not equal its DOM label. Match that static
        // label only inside the unique real Settings TabWidget, never page-wide.
        // On a freshly opened Chrome tab the first click can land during WebView
        // restoration and be dropped. Confirm navigation through the tab itself,
        // then retry the visible Settings button a bounded number of times.
        var tab: UiObject2? = null
        var clickAttempts = 0
        var nextClickAt = 0L
        await(30_000, FailureReason.SYNC_TAB_NOT_REACHED) {
            accept(device.currentPackageName == CHROME, FailureReason.INDEPENDENT_BROWSER_NOT_FOREGROUND)
            val document = webViewOrNull()
            val lists = SETTINGS.flatMap { name ->
                document?.findObjects(By.pkg(CHROME).clazz("android.widget.TabWidget").text(name)).orEmpty()
            }.filter { !it.visibleBounds.isEmpty }.distinctBy { it.visibleBounds.toShortString() }
            accept(lists.size <= 1, FailureReason.AMBIGUOUS_ACCESSIBLE_CONTROL)
            val matches = lists.singleOrNull()?.let { list ->
                SYNC_TAB.flatMap { name ->
                    list.findObjects(By.pkg(CHROME).clazz("android.view.View").textContains(name)
                        .enabled(true).clickable(true))
                }.filter { !it.visibleBounds.isEmpty }.distinctBy { it.visibleBounds.toShortString() }
            }.orEmpty()
            accept(matches.size <= 1, FailureReason.AMBIGUOUS_ACCESSIBLE_CONTROL)
            tab = matches.singleOrNull()
            if (tab != null) return@await true

            val now = SystemClock.elapsedRealtime()
            if (now >= nextClickAt) {
                accept(clickAttempts < 4, FailureReason.SYNC_TAB_NOT_REACHED)
                clickAttempts += 1
                val settingsButtons = webNodes { node ->
                    node.className?.toString() == "android.widget.Button" && node.isEnabled && node.isClickable &&
                        node.text?.toString() in SETTINGS
                }
                try {
                    accept(settingsButtons.size <= 1, FailureReason.AMBIGUOUS_ACCESSIBLE_CONTROL)
                    settingsButtons.singleOrNull()?.let { tryClickWebNode(it) }
                } finally {
                    settingsButtons.forEach { it.recycle() }
                }
                nextClickAt = now + 2_000
            }
            false
        }
        return requireNotNull(tab)
    }

    private fun clickWebControl(names: List<String>, allowClipped: Boolean = false) {
        // Chrome's real AX default action avoids UiObject2's center-coordinate
        // tap on an animated button. The page footer may be genuinely visible
        // while Chrome still marks it as offscreen, so only callers that prove
        // an actual viewport intersection opt into clipped activation.
        var showRequested = false
        var clickAttempts = 0
        beginWebControl()
        await(15_000, FailureReason.OFFICIAL_WEB_CONTROL_NOT_REACHED) {
            val nodes = webButtonNodes(names)
            try {
                accept(nodes.size <= 1, FailureReason.AMBIGUOUS_ACCESSIBLE_CONTROL)
                nodes.singleOrNull()?.let { node ->
                    observeWebNode(node)
                    if (webNodeCanActivate(node, allowClipped)) {
                        accept(clickAttempts < 6, FailureReason.OFFICIAL_WEB_CLICK_REJECTED)
                        clickAttempts += 1
                        if (tryClickWebNode(node)) {
                            true
                        } else {
                            SystemClock.sleep(500)
                            false
                        }
                    } else if (!showRequested) {
                        showRequested = true
                        showWebNode(node)
                        false
                    } else false
                } ?: false
            } finally { nodes.forEach { it.recycle() } }
        }
    }

    private fun revealWebControlByScroll(names: List<String>, allowClipped: Boolean = false) {
        beginWebControl()
        var imeDismissed = false
        var pageFocusRequested = false
        var pageDowns = 0
        var swipes = 0
        var lastMotionAt = 0L
        await(240_000, FailureReason.OFFICIAL_WEB_CONTROL_NOT_REACHED) {
            val snapshot = webControlPageSnapshot(names)
            try {
                accept(snapshot.buttons.size <= 1, FailureReason.AMBIGUOUS_ACCESSIBLE_CONTROL)
                snapshot.buttons.singleOrNull()?.let { node ->
                    observeWebNode(node)
                    if (webNodeCanActivate(node, allowClipped)) return@await true
                }

                if (!imeDismissed) {
                    imeDismissed = true
                    dismissWebImeOnce()
                    return@await false
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastMotionAt < 900) return@await false

                accept(device.currentPackageName == CHROME,
                    FailureReason.INDEPENDENT_BROWSER_NOT_FOREGROUND)
                val pane = snapshot.pane ?: return@await false

                if (!pageFocusRequested) {
                    val point = snapshot.pageFocusPoint
                    accept(point != null, FailureReason.OFFICIAL_WEB_FOCUS_REJECTED)
                    val accepted = device.click(point!!.x, point.y)
                    recordWebPaneGesture("FOCUS_PAGE", pane, accepted)
                    accept(accepted, FailureReason.OFFICIAL_WEB_FOCUS_REJECTED)
                    pageFocusRequested = true
                    lastMotionAt = now
                    return@await false
                }

                accept(!snapshot.passwordFocused, FailureReason.OFFICIAL_WEB_FOCUS_REJECTED)
                if (pageDowns < 3) {
                    accept(!inputMethodShown(), FailureReason.OFFICIAL_WEB_FORM_NOT_REACHED)
                    val batch = 3 - pageDowns
                    val accepted = pressPageDownBatch(batch)
                    recordWebPaneGesture("PAGE_DOWN", pane, accepted)
                    accept(accepted, FailureReason.OFFICIAL_WEB_SCROLL_ACTION_REJECTED)
                    pageDowns += batch
                    lastMotionAt = now
                    return@await false
                }

                accept(swipes < 6, FailureReason.OFFICIAL_WEB_CONTROL_NOT_REACHED)
                val batch = 6 - swipes
                swipes += batch
                val accepted = swipeWebPaneBatch(pane, batch)
                recordWebPaneGesture("PANE_DOWN", pane, accepted)
                accept(accepted, FailureReason.OFFICIAL_WEB_SCROLL_ACTION_REJECTED)

                lastMotionAt = now
                false
            } finally {
                snapshot.buttons.forEach { it.recycle() }
                snapshot.pane?.node?.recycle()
            }
        }
    }

    private fun clickVisibleWebControl(names: List<String>) {
        // Reduced display density must put the official footer in the live
        // viewport. Click that exact AX node without first scrolling or
        // focusing the WebView, which makes Chrome drop its content subtree.
        beginWebControl()
        accept(!inputMethodShown(), FailureReason.OFFICIAL_WEB_FORM_NOT_REACHED)
        await(20_000, FailureReason.OFFICIAL_WEB_CONTROL_NOT_REACHED) {
            val nodes = webButtonNodes(names)
            try {
                accept(nodes.size <= 1, FailureReason.AMBIGUOUS_ACCESSIBLE_CONTROL)
                nodes.singleOrNull()?.let { node ->
                    observeWebNode(node)
                    if (webNodeFullyVisibleInViewport(node)) {
                        clickWebNode(node)
                        true
                    } else false
                } ?: false
            } finally {
                nodes.forEach { it.recycle() }
            }
        }
        check(webControlTrace.length() == 1) { "official import click must be the only page action" }
    }

    private fun swipeWebPane(pane: WebPane): Boolean {
        val bounds = pane.bounds
        // Start inside the page rather than its margin. A focus-triggered IME
        // can temporarily leave pane bounds unchanged while covering lower
        // screen coordinates, so the caller dismisses it before this gesture.
        val gutterX = (bounds.left + bounds.width() / 10)
            .coerceIn(bounds.left + 1, bounds.right - 1)
        val startY = bounds.top + bounds.height() * 80 / 100
        val endY = bounds.top + bounds.height() * 20 / 100
        // A slow drag in the page gutter moves the real Settings scroller
        // without touching the password input or its IME.
        return device.swipe(gutterX, startY, gutterX, endY, 70)
    }

    private fun swipeWebPaneBatch(pane: WebPane, count: Int): Boolean {
        repeat(count) {
            if (!swipeWebPane(pane)) return false
            if (it + 1 < count) SystemClock.sleep(1_500)
        }
        return true
    }

    private fun pressPageDownBatch(count: Int): Boolean {
        repeat(count) {
            if (!device.pressKeyCode(KeyEvent.KEYCODE_PAGE_DOWN)) return false
            if (it + 1 < count) SystemClock.sleep(1_200)
        }
        return true
    }

    private fun dismissWebImeOnce() {
        if (!inputMethodShown()) return
        accept(device.currentPackageName == CHROME,
            FailureReason.INDEPENDENT_BROWSER_NOT_FOREGROUND)
        accept(device.pressBack(), FailureReason.OFFICIAL_WEB_FORM_NOT_REACHED)
        await(5_000, FailureReason.OFFICIAL_WEB_FORM_NOT_REACHED) {
            device.currentPackageName == CHROME && !inputMethodShown() && webViewOrNull() != null
        }
    }

    private fun inputMethodShown(): Boolean = shellRead("dumpsys input_method")
        .lineSequence().any { it.trim() == "mInputShown=true" }

    private fun recordWebPaneGesture(operation: String, pane: WebPane, accepted: Boolean) {
        check(webControlTrace.length() < 8) { "bounded web action evidence required" }
        fun rect(value: Rect) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
        webControlTrace.put(JSONObject()
            .put("targetBounds", rect(pane.bounds))
            .put("paneBounds", rect(pane.bounds))
            .put("offscreen", false)
            .put("paneCanScrollUp", pane.canScrollUp)
            .put("paneCanScrollDown", pane.canScrollDown)
            .put("onScreen", true)
            .put("operation", operation)
            .put("accepted", accepted))
    }

    private fun currentWebPane(): WebPane? {
        val nodes = webNodes { node ->
            node.isScrollable && node.isEnabled && node.className?.toString() != "android.webkit.WebView"
        }
        try {
            val node = nodes.singleOrNull() ?: return null
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (bounds.isEmpty) return null
            val canScrollUp = node.actionList.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            }
            val canScrollDown = node.actionList.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            }
            return WebPane(AccessibilityNodeInfo.obtain(node), bounds, canScrollUp, canScrollDown)
        } finally { nodes.forEach { it.recycle() } }
    }

    @Suppress("DEPRECATION")
    private fun webControlPageSnapshot(names: List<String>): WebControlPageSnapshot {
        accept(device.currentPackageName == CHROME, FailureReason.INDEPENDENT_BROWSER_NOT_FOREGROUND)
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        val clickableBounds = mutableListOf<Rect>()
        var pane: WebPane? = null
        var paneCandidates = 0
        var paneConflict = false
        var passwordFocused = false
        var webViews = 0

        fun visit(node: AccessibilityNodeInfo, insideDocument: Boolean) {
            val isDocument = node.packageName?.toString() == CHROME && node.isVisibleToUser &&
                node.className?.toString() == "android.webkit.WebView"
            if (isDocument) webViews += 1
            val inDocument = insideDocument || isDocument
            if (inDocument && node.packageName?.toString() == CHROME && node.isVisibleToUser) {
                if (node.className?.toString() == "android.widget.Button" && node.isEnabled &&
                    node.isClickable &&
                    (node.text?.toString() in names || node.contentDescription?.toString() in names)) {
                    buttons += AccessibilityNodeInfo.obtain(node)
                }
                if (node.isClickable) {
                    clickableBounds += Rect().also { node.getBoundsInScreen(it) }
                }
                if (node.isPassword && node.className?.toString() == "android.widget.EditText" &&
                    node.isFocused) {
                    passwordFocused = true
                }
                if (node.isScrollable && node.isEnabled &&
                    node.className?.toString() != "android.webkit.WebView") {
                    paneCandidates += 1
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    if (paneCandidates > 1) paneConflict = true
                    if (!bounds.isEmpty && pane == null && !paneConflict) {
                        val canScrollUp = node.actionList.any {
                            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                        }
                        val canScrollDown = node.actionList.any {
                            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                        }
                        pane = WebPane(AccessibilityNodeInfo.obtain(node), bounds,
                            canScrollUp, canScrollDown)
                    }
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let {
                try { visit(it, inDocument) } finally { it.recycle() }
            }
        }

        try {
            instrumentation.uiAutomation.rootInActiveWindow?.let {
                try { visit(it, false) } finally { it.recycle() }
            }
            accept(webViews <= 1, FailureReason.AMBIGUOUS_ACCESSIBLE_CONTROL)
            if (paneConflict || paneCandidates != 1) {
                pane?.node?.recycle()
                pane = null
            }
            val pageFocusPoint = pane?.let { webPageFocusPoint(it.bounds, clickableBounds) }
            return WebControlPageSnapshot(buttons, pane, pageFocusPoint, passwordFocused)
        } catch (failure: Throwable) {
            buttons.forEach { it.recycle() }
            pane?.node?.recycle()
            throw failure
        }
    }

    private fun webPageFocusPoint(bounds: Rect, clickableBounds: List<Rect>): WebTapPoint? {
        val y = bounds.centerY()
        return listOf(36, 54, 64).asSequence()
            .map { WebTapPoint(bounds.left + it, y) }
            .firstOrNull { point ->
                bounds.contains(point.x, point.y) &&
                    clickableBounds.none { it.contains(point.x, point.y) }
            }
    }

    private fun awaitStableImportForm(name: String) {
        var stablePolls = 0
        await(30_000, FailureReason.OFFICIAL_WEB_FORM_NOT_REACHED) {
            val passwords = passwordNodes()
            try {
                val ready = device.currentPackageName == CHROME &&
                    webViewOrNull() != null && hasWebName(name) && passwords.size == 1
                stablePolls = if (ready) stablePolls + 1 else 0
                stablePolls >= 3
            } finally { passwords.forEach { it.recycle() } }
        }
    }

    private fun openWebForm(opener: List<String>, expectedPasswords: Int) {
        // ACTION_CLICK only acknowledges receipt. The row changes to Close
        // before syncfiles.js finishes loading its real form, and Chrome can
        // acknowledge a click without dispatching it. Re-target the same real
        // button only while the page still shows no form transition.
        beginWebControl()
        var stablePolls = 0
        var clickAttempts = 0
        var lastClickAt = 0L
        var showRequested = false
        await(60_000, FailureReason.OFFICIAL_WEB_FORM_NOT_REACHED) {
            val openers = webButtonNodes(opener)
            try {
                val closers = webButtonNodes(CLOSE)
                val passwords = passwordNodes()
                try {
                    accept(openers.size <= 1 && closers.size <= 1, FailureReason.AMBIGUOUS_ACCESSIBLE_CONTROL)
                    val ready = openers.isEmpty() && closers.size == 1 &&
                        passwords.size == expectedPasswords
                    stablePolls = if (ready) stablePolls + 1 else 0
                    if (stablePolls >= 3) return@await true
                    val now = SystemClock.elapsedRealtime()
                    openers.singleOrNull()?.let { node ->
                        if (passwords.size != expectedPasswords &&
                            clickAttempts < 4 && now - lastClickAt >= 4_000) {
                            observeWebNode(node)
                            if (webNodeCanActivate(node, allowClipped = false)) {
                                tryClickWebNode(node)
                                clickAttempts += 1
                                lastClickAt = now
                            } else if (!showRequested) {
                                showRequested = true
                                showWebNode(node)
                            }
                        }
                    }
                    false
                } finally {
                    closers.forEach { it.recycle() }
                    passwords.forEach { it.recycle() }
                }
            } finally { openers.forEach { it.recycle() } }
        }
    }

    private fun webButtonNodes(names: List<String>): List<AccessibilityNodeInfo> = webNodes { node ->
        node.className?.toString() == "android.widget.Button" && node.isEnabled && node.isClickable &&
            (node.text?.toString() in names || node.contentDescription?.toString() in names)
    }

    @Suppress("DEPRECATION")
    private fun webNodes(matchesNode: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        accept(device.currentPackageName == CHROME, FailureReason.INDEPENDENT_BROWSER_NOT_FOREGROUND)
        val matches = mutableListOf<AccessibilityNodeInfo>()
        var webViews = 0
        fun visit(node: AccessibilityNodeInfo, insideDocument: Boolean) {
            val isDocument = node.packageName?.toString() == CHROME && node.isVisibleToUser &&
                node.className?.toString() == "android.webkit.WebView"
            if (isDocument) webViews += 1
            val inDocument = insideDocument || isDocument
            if (inDocument && node.packageName?.toString() == CHROME && node.isVisibleToUser && matchesNode(node)) {
                matches += AccessibilityNodeInfo.obtain(node)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let {
                try { visit(it, inDocument) } finally { it.recycle() }
            }
        }
        try {
            instrumentation.uiAutomation.rootInActiveWindow?.let {
                try { visit(it, false) } finally { it.recycle() }
            }
            accept(webViews <= 1, FailureReason.AMBIGUOUS_ACCESSIBLE_CONTROL)
            return matches
        } catch (failure: Throwable) {
            matches.forEach { it.recycle() }
            throw failure
        }
    }

    private fun showWebNode(node: AccessibilityNodeInfo) {
        val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
        val accepted = webAction("SHOW", node) {
            node.actionList.any { it.id == action } && node.performAction(action)
        }
        accept(accepted, FailureReason.OFFICIAL_WEB_SHOW_REJECTED)
    }

    private fun clickWebNode(node: AccessibilityNodeInfo) {
        accept(tryClickWebNode(node), FailureReason.OFFICIAL_WEB_CLICK_REJECTED)
    }

    private fun tryClickWebNode(node: AccessibilityNodeInfo): Boolean =
        webAction("CLICK", node) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

    private data class WebPane(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val canScrollUp: Boolean,
        val canScrollDown: Boolean,
    )

    private data class WebControlPageSnapshot(
        val buttons: List<AccessibilityNodeInfo>,
        val pane: WebPane?,
        val pageFocusPoint: WebTapPoint?,
        val passwordFocused: Boolean,
    )

    private data class WebTapPoint(val x: Int, val y: Int)

    @Suppress("DEPRECATION")
    private fun webPane(node: AccessibilityNodeInfo): WebPane? {
        var ancestor = node.parent
        var paneNode: AccessibilityNodeInfo? = null
        var pane: Rect? = null
        var canScrollUp = false
        var canScrollDown = false
        try {
            while (true) {
                val parent = ancestor ?: return null
                if (parent.packageName?.toString() != CHROME || !parent.isVisibleToUser) return null
                val bounds = Rect().also { parent.getBoundsInScreen(it) }
                if (parent.className?.toString() == "android.webkit.WebView") {
                    val viewport = pane ?: return null
                    val captured = paneNode ?: return null
                    if (!viewport.intersect(bounds) ||
                        !viewport.intersect(0, 0, device.displayWidth, device.displayHeight)) return null
                    paneNode = null
                    return WebPane(captured, viewport, canScrollUp, canScrollDown)
                }
                if (parent.isScrollable) {
                    if (!parent.isEnabled || bounds.isEmpty) return null
                    if (pane == null) {
                        pane = bounds
                        paneNode = AccessibilityNodeInfo.obtain(parent)
                        // BACKWARD can also mean horizontal movement in Chrome.
                        // Record only the exact vertical actions for the pane.
                        canScrollUp = parent.actionList.any {
                            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                        }
                        canScrollDown = parent.actionList.any {
                            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                        }
                    } else if (!pane.intersect(bounds)) return null
                }
                ancestor = parent.parent
                parent.recycle()
            }
        } finally {
            ancestor?.recycle()
            paneNode?.recycle()
        }
    }

    private fun scrollRealWebPane(node: AccessibilityNodeInfo) {
        val pane = webPane(node) ?: throw AcceptanceFailure(FailureReason.OFFICIAL_WEB_SCROLL_PANE_NOT_REACHED)
        val viewport = pane.bounds
        val target = Rect().also { node.getBoundsInScreen(it) }
        val down = target.top >= viewport.bottom
        val up = target.bottom <= viewport.top
        accept((down && pane.canScrollDown) || (up && pane.canScrollUp),
            FailureReason.OFFICIAL_WEB_SCROLL_DIRECTION_NOT_PROVEN)
        try {
            val action = if (down) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            val accepted = webAction(if (down) "PANE_DOWN" else "PANE_UP", node) {
                pane.node.actionList.any { it.id == action } && pane.node.performAction(action)
            }
            accept(accepted, FailureReason.OFFICIAL_WEB_SCROLL_ACTION_REJECTED)
        } finally { pane.node.recycle() }
    }

    private fun beginWebControl() {
        webControlOrdinal += 1
        webControlTrace = JSONArray()
        lastWebObservation = null
    }

    private fun webObservation(node: AccessibilityNodeInfo): JSONObject {
        val target = Rect().also { node.getBoundsInScreen(it) }
        val pane = webPane(node)
        fun rect(value: Rect?) = JSONArray(listOf(value?.left ?: 0, value?.top ?: 0,
            value?.right ?: 0, value?.bottom ?: 0))
        try {
            return JSONObject()
                .put("targetBounds", rect(target)).put("paneBounds", rect(pane?.bounds))
                .put("offscreen", node.extras.getBoolean("AccessibilityNodeInfo.offscreen", false))
                .put("paneCanScrollUp", pane?.canScrollUp == true)
                .put("paneCanScrollDown", pane?.canScrollDown == true)
                .put("onScreen", webNodeOnScreen(node))
        } finally { pane?.node?.recycle() }
    }

    private fun observeWebNode(node: AccessibilityNodeInfo) {
        lastWebObservation = webObservation(node)
    }

    private fun webAction(operation: String, node: AccessibilityNodeInfo, action: () -> Boolean): Boolean {
        check(webControlTrace.length() < 8) { "bounded web action evidence required" }
        val observation = webObservation(node)
        val accepted = action()
        webControlTrace.put(observation.put("operation", operation).put("accepted", accepted))
        return accepted
    }

    @Suppress("DEPRECATION")
    private fun webNodeOnScreen(node: AccessibilityNodeInfo): Boolean {
        // Chrome keeps scrolled-off AX nodes visibleToUser. Its offscreen extra
        // and actual viewport intersection must both be checked after SHOW.
        if (!node.isVisibleToUser || node.extras.getBoolean("AccessibilityNodeInfo.offscreen", false)) return false
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty || !bounds.intersect(0, 0, device.displayWidth, device.displayHeight)) return false
        var ancestor = node.parent
        try {
            while (true) {
                val parent = ancestor ?: return false
                if (parent.packageName?.toString() != CHROME) return false
                if (parent.isScrollable) {
                    val viewport = Rect().also { parent.getBoundsInScreen(it) }
                    if (!parent.isVisibleToUser || viewport.isEmpty || !bounds.intersect(viewport)) return false
                }
                if (parent.className?.toString() == "android.webkit.WebView") {
                    val viewport = Rect().also { parent.getBoundsInScreen(it) }
                    return parent.isVisibleToUser && !viewport.isEmpty && bounds.intersect(viewport)
                }
                ancestor = parent.parent
                parent.recycle()
            }
        } finally { ancestor?.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun webNodeIntersectsScreen(node: AccessibilityNodeInfo): Boolean {
        // The official web footer can sit in the visible WebView while Chrome
        // labels it offscreen and places it below its scrollable content pane.
        // This fallback accepts only a real, visible geometric intersection.
        if (!node.isVisibleToUser) return false
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty || !bounds.intersect(0, 0, device.displayWidth, device.displayHeight)) return false
        var ancestor = node.parent
        try {
            while (true) {
                val parent = ancestor ?: return false
                if (parent.packageName?.toString() != CHROME || !parent.isVisibleToUser) return false
                if (parent.className?.toString() == "android.webkit.WebView") {
                    val viewport = Rect().also { parent.getBoundsInScreen(it) }
                    return !viewport.isEmpty && bounds.intersect(viewport)
                }
                ancestor = parent.parent
                parent.recycle()
            }
        } finally { ancestor?.recycle() }
    }

    private fun webNodeCanActivate(node: AccessibilityNodeInfo, allowClipped: Boolean): Boolean =
        webNodeOnScreen(node) || (allowClipped && webNodeIntersectsScreen(node))

    @Suppress("DEPRECATION")
    private fun webNodeFullyVisibleInViewport(node: AccessibilityNodeInfo): Boolean {
        // The reduced viewport calibration must prove the full official
        // control fits inside the real WebView. Chrome omits a scrollable pane
        // once the complete form fits, so validate that pane only when present.
        // This is stronger than Chrome's offscreen hint and does not permit a
        // clipped or partially hidden footer click.
        if (!node.isVisibleToUser) return false
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty || !Rect(0, 0, device.displayWidth, device.displayHeight).contains(bounds)) return false
        var ancestor = node.parent
        try {
            while (true) {
                val parent = ancestor ?: return false
                if (parent.packageName?.toString() != CHROME || !parent.isVisibleToUser) return false
                if (parent.isScrollable) {
                    val viewport = Rect().also { parent.getBoundsInScreen(it) }
                    if (viewport.isEmpty || !viewport.contains(bounds)) return false
                }
                if (parent.className?.toString() == "android.webkit.WebView") {
                    val viewport = Rect().also { parent.getBoundsInScreen(it) }
                    return !viewport.isEmpty && viewport.contains(bounds)
                }
                ancestor = parent.parent
                parent.recycle()
            }
        } finally { ancestor?.recycle() }
    }

    private fun webViewOrNull(): UiObject2? = device.findObject(By.pkg(CHROME).clazz("android.webkit.WebView"))

    private fun chromeNavigationField(): UiObject2? =
        device.findObject(By.res(CHROME, "url_bar"))
            ?: device.findObject(By.res(CHROME, "search_box_text"))

    private fun clickNamed(pkg: String, names: List<String>) {
        await(15_000) { namedControl(pkg, names) != null }
        requireNotNull(namedControl(pkg, names)).click()
    }

    private fun namedControl(pkg: String, names: List<String>): UiObject2? {
        val candidates = names.flatMap { name ->
            device.findObjects(By.pkg(pkg).text(name)) + device.findObjects(By.pkg(pkg).desc(name))
        }.mapNotNull { found ->
            var node: UiObject2? = found
            var result: UiObject2? = null
            repeat(6) {
                val current = node
                if (result == null && current != null && current.applicationPackage == pkg) {
                    if (current.isEnabled && current.isClickable && !current.visibleBounds.isEmpty) result = current
                    else node = current.parent
                }
            }
            result
        }.distinctBy { it.visibleBounds.toShortString() }
        accept(candidates.size <= 1, FailureReason.AMBIGUOUS_ACCESSIBLE_CONTROL)
        return candidates.singleOrNull()
    }

    private fun hasWebName(name: String): Boolean = device.hasObject(By.pkg(CHROME).text(name)) ||
        device.hasObject(By.pkg(CHROME).desc(name))
    private fun hasWebText(text: String): Boolean = device.hasObject(By.pkg(CHROME).textContains(text)) ||
        device.hasObject(By.pkg(CHROME).descContains(text))
    private fun hasImportSuccess(): Boolean = hasWebText("Providers:") || hasWebText("供应商：")

    private fun setRealPasswordFields(values: List<String>) {
        // First use the actual visible fields. If necessary, ask Chrome to show
        // the real last field, then require the complete field set on screen.
        beginWebControl()
        var showRequested = false
        var gestures = 0
        var lastMotionAt = 0L
        var previousScrollTarget: Rect? = null
        await(15_000, FailureReason.PASSWORD_INPUTS_NOT_RECOGNIZED) {
            if (setVisiblePasswordFields(values)) true else {
                val nodes = passwordNodes()
                try {
                    accept(nodes.size == values.size, FailureReason.PASSWORD_INPUTS_NOT_RECOGNIZED)
                    val hidden = nodes.firstOrNull { !webNodeOnScreen(it) }
                    hidden?.let { node ->
                        observeWebNode(node)
                        if (!showRequested) {
                            showWebNode(node)
                            showRequested = true
                            lastMotionAt = SystemClock.elapsedRealtime()
                        } else if (gestures < 6 && SystemClock.elapsedRealtime() - lastMotionAt >= 750) {
                            val bounds = Rect().also { node.getBoundsInScreen(it) }
                            accept(previousScrollTarget != bounds, FailureReason.OFFICIAL_WEB_SCROLL_NO_PROGRESS)
                            previousScrollTarget = bounds
                            scrollRealWebPane(node)
                            gestures += 1
                            lastMotionAt = SystemClock.elapsedRealtime()
                        }
                    }
                    false
                } finally { nodes.forEach { it.recycle() } }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setVisiblePasswordFields(values: List<String>): Boolean {
        val nodes = passwordNodes()
        try {
            if (nodes.size != values.size || nodes.any { !webNodeOnScreen(it) }) return false
            nodes.zip(values).forEach { (node, value) ->
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }
                // Standard accessibility entry into actual Chrome password inputs;
                // no page JS or browser File/URI object is injected.
                accept(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments),
                    FailureReason.PASSWORD_INPUT_REJECTED)
            }
            return true
        } finally { nodes.forEach { it.recycle() } }
    }

    private fun passwordNodes(): List<AccessibilityNodeInfo> {
        val matches = webNodes { node ->
            node.isPassword && node.isEnabled && node.className?.toString() == "android.widget.EditText"
        }
        return matches.sortedWith(compareBy({ node -> Rect().also { node.getBoundsInScreen(it) }.top },
            { node -> Rect().also { node.getBoundsInScreen(it) }.left }))
    }

    private data class Download(val path: String, val bytes: Long, val modified: Long, val sha256: String) {
        val name: String get() = path.substringAfterLast('/')
    }

    private fun awaitRealDownload(baseline: Set<String>): Download {
        val started = SystemClock.elapsedRealtime()
        var previous: Download? = null
        var stable = 0
        while (SystemClock.elapsedRealtime() - started < 90_000) {
            accept(device.currentPackageName == CHROME, FailureReason.INDEPENDENT_BROWSER_NOT_FOREGROUND)
            accept(!hasWebText("Saved to ") && !hasWebText("已保存到 "), FailureReason.PRIVATE_EXPORT_PATH_RETURNED)
            val fresh = completeDownloads().values.filter { it.path !in baseline }
            accept(fresh.size <= 1, FailureReason.MULTIPLE_NEW_DOWNLOADS)
            val current = fresh.singleOrNull()
            if (current != null && current.bytes > 0) {
                stable = if (current == previous) stable + 1 else 0
                previous = current
                if (stable >= 2) return current
            } else if (!downloadRetryClicked && SystemClock.elapsedRealtime() - started > 15_000 &&
                partialDownloadCount() == 0) {
                namedControl(CHROME, DOWNLOAD_RETRY)?.let { it.click(); downloadRetryClicked = true }
            }
            SystemClock.sleep(1_000)
        }
        throw AcceptanceFailure(FailureReason.STABLE_PUBLIC_DOWNLOAD_NOT_REACHED)
    }

    private fun privateBackupArchives(): Map<String, Pair<Long, Long>> {
        // Read only the disposable test app's own storage; never create an archive
        // or alter its contents to manufacture the no-private-backup assertion.
        return listOf(context.filesDir, context.noBackupFilesDir, context.cacheDir).flatMap { directory ->
            val root = directory.canonicalFile
            if (!root.exists()) emptyList() else root.walkTopDown()
                .onEnter { it.canonicalPath == root.path || it.canonicalPath.startsWith(root.path + File.separator) }
                .filter { it.isFile && it.name.endsWith(".magpie-backup") }
                .map { it.canonicalPath to (it.length() to it.lastModified()) }.toList()
        }.toMap()
    }

    private fun assertPrivateArchivesUnchanged(baseline: Map<String, Pair<Long, Long>>) {
        accept(privateBackupArchives() == baseline, FailureReason.PRIVATE_BACKUP_ARCHIVE_CHANGED)
        privateArchiveChecks += 1
    }

    private fun completeDownloads(): Map<String, Download> {
        // executeShellCommand does not provide shell quoting/globbing. find expands -name,
        // and -exec passes each real path, including spaces, as one stat/hash argument.
        val metadata = shellRead("find /sdcard/Download -maxdepth 1 -type f -name magpie*.magpie-backup -exec stat -c %s:%Y:%n {} +")
        // A genuinely fresh AVD can lack the directory until Chrome's first real download.
        // Accept only this exact absent-directory state, never permission/tool errors.
        if (MISSING_DOWNLOAD_DIRECTORY.matches(metadata.trim())) return emptyMap()
        val hashes = shellRead("find /sdcard/Download -maxdepth 1 -type f -name magpie*.magpie-backup -exec sha256sum {} +")
            .lineSequence().filter { it.isNotBlank() }.associate { line ->
                val match = HASH_LINE.matchEntire(line) ?: throw AcceptanceFailure(FailureReason.DOWNLOAD_HASH_TOOL_FAILED)
                match.groupValues[2] to match.groupValues[1].lowercase()
            }
        return metadata.lineSequence().filter { it.isNotBlank() }.associate { line ->
            val fields = line.split(':', limit = 3)
            check(fields.size == 3) { "download metadata tool failed" }
            val path = fields[2]
            check(path.startsWith("/sdcard/Download/") && SAFE_BASENAME.matches(path.substringAfterLast('/'))) {
                "unexpected public backup filename"
            }
            path to Download(path, fields[0].toLong(), fields[1].toLong(),
                requireNotNull(hashes[path]) { "completed download hash missing" })
        }
    }

    private fun partialDownloadCount(): Int {
        val output = shellRead("find /sdcard/Download -maxdepth 1 -type f -name *.crdownload -exec stat -c %s:%Y:%n {} +")
        if (MISSING_DOWNLOAD_DIRECTORY.matches(output.trim())) return 0
        return output.lineSequence().filter { it.isNotBlank() }.count { line ->
            check(line.matches(Regex("[0-9]+:[0-9]+:/sdcard/Download/.+"))) { "partial download observation failed" }
            true
        }
    }

    private fun assertDownloadUnchanged() {
        val expected = requireNotNull(downloaded)
        accept(completeDownloads()[expected.path] == expected, FailureReason.DOWNLOADED_FILE_CHANGED)
    }

    private fun chooseActualDownload(download: Download) {
        var sourceChosen = false
        await(15_000) {
            if (device.currentPackageName in DOCUMENTS_PACKAGES) true else {
                val pkg = device.currentPackageName.orEmpty()
                if (!sourceChosen && pkg in setOf(CHROME, "com.android.intentresolver", "android")) {
                    namedControl(pkg, listOf("Files", "文件", "Browse", "浏览"))?.let {
                        it.click(); sourceChosen = true
                    }
                }
                false
            }
        }
        val pickerPackage = requireNotNull(device.currentPackageName)
        check(pickerPackage in DOCUMENTS_PACKAGES) { "real DocumentsUI required" }
        safeHierarchy("browser-backup-real-picker.xml")
        // Always select the real Downloads root, even when Recent already shows that name.
        // A matching basename in another provider/root is not sufficient evidence.
        clickNamed(pickerPackage, listOf("Show roots", "显示根目录", "显示根目录列表"))
        clickNamed(pickerPackage, listOf("Downloads", "下载内容", "下载"))
        var scrollAttempts = 0
        // Root navigation is asynchronous; a loading or single-file directory
        // need not expose a scrollable node. Wait for the real exact file row.
        await(30_000, FailureReason.DOWNLOADED_FILE_NOT_REACHED_IN_PICKER) {
            check(device.currentPackageName == pickerPackage) { "unexpected file picker foreground" }
            val file = namedControl(pickerPackage, listOf(download.name))
            if (file != null) {
                file.click()
                true
            } else {
                if (scrollAttempts < 12) device.findObjects(By.pkg(pickerPackage).scrollable(true))
                    .maxByOrNull { it.visibleBounds.height() }?.let { list ->
                        scrollAttempts += 1
                        list.scroll(Direction.DOWN, .65f)
                    }
                false
            }
        }
        var openClicked = false
        await(15_000) {
            if (device.currentPackageName == CHROME && webViewOrNull() != null) true else {
                check(device.currentPackageName == pickerPackage) { "unexpected file picker foreground" }
                if (!openClicked) namedControl(pickerPackage, listOf("Open", "打开"))?.let {
                    it.click(); openClicked = true
                }
                false
            }
        }
    }

    private fun shellRead(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun hierarchyBytes(): ByteArray = ByteArrayOutputStream().use { out ->
        device.dumpWindowHierarchy(out)
        out.toByteArray()
    }

    private fun safeHierarchy(name: String) {
        artifacts.mkdirs()
        // Raw hierarchy is in memory only. Never dump it to a temporary file first.
        val parser = Xml.newPullParser().apply {
            setInput(ByteArrayInputStream(hierarchyBytes()), "UTF-8")
        }
        val output = ByteArrayOutputStream()
        val serializer = Xml.newSerializer().apply { setOutput(output, "UTF-8"); startDocument("UTF-8", true) }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    check(parser.name in setOf("hierarchy", "node")) { "unexpected hierarchy element" }
                    serializer.startTag(null, parser.name)
                    val password = parser.getAttributeValue(null, "password") == "true"
                    for (i in 0 until parser.attributeCount) {
                        val attribute = parser.getAttributeName(i)
                        val value = parser.getAttributeValue(i)
                        val secret = secrets.any { value.contains(it) } || value.contains("ticket=", ignoreCase = true)
                        // Only exact static public labels / validated public filename survive.
                        // Unknown text, hints, passwords, account names and URLs are erased.
                        val safe = when {
                            secret -> "[redacted]"
                            attribute in SAFE_STRUCTURAL_ATTRIBUTES -> value
                            attribute in TEXT_ATTRIBUTES && !password &&
                                (value in SAFE_LABELS || (downloaded?.name != null && value == downloaded?.name)) -> value
                            value.isEmpty() -> ""
                            else -> "[redacted]"
                        }
                        serializer.attribute(null, attribute, safe)
                    }
                }
                XmlPullParser.END_TAG -> serializer.endTag(null, parser.name)
                XmlPullParser.DOCDECL -> throw AssertionError("unexpected hierarchy declaration")
                XmlPullParser.TEXT -> if (!parser.text.isNullOrBlank()) serializer.text("[redacted]")
            }
            event = parser.nextToken()
        }
        serializer.endDocument()
        val sanitized = output.toString("UTF-8")
        check(secrets.none { sanitized.contains(it) } && !sanitized.contains("ticket=")) {
            "evidence redaction failed"
        }
        File(artifacts, name).writeText(sanitized)
    }

    private fun archiveEvidence() {
        check(artifacts.absolutePath.matches(Regex("/[A-Za-z0-9_./-]+"))) { "unsafe artifact path" }
        val destination = "/data/local/tmp/magpie-plugin-runtime/$evidenceDirectoryName"
        check(device.executeShellCommand("mkdir -p $destination").isBlank())
        check(device.executeShellCommand("cp -R ${artifacts.absolutePath}/. $destination/").isBlank())
    }

    private class AcceptanceFailure(val reason: FailureReason) : AssertionError(reason.name)

    private fun accept(condition: Boolean, reason: FailureReason) {
        if (!condition) throw AcceptanceFailure(reason)
    }

    private enum class FailureReason {
        UNEXPECTED_EXCEPTION, UI_WAIT_EXPIRED, CORE_SESSION_CHANGED, CORE_VERSION_MISMATCH,
        FIXTURE_NAME_MISMATCH, FIXTURE_URL_MISMATCH, FIXTURE_MODELS_MISMATCH, FIXTURE_METADATA_MISMATCH,
        NATIVE_PROVIDER_PAGE_NOT_RECOGNIZED, NATIVE_BROWSER_BUTTON_NOT_REACHED,
        BROWSER_SETUP_REQUIRED, INDEPENDENT_BROWSER_NOT_FOREGROUND, CURRENT_URL_EDIT_ACTION_NOT_REACHED,
        CLEAN_OFFICIAL_URL_NOT_REACHED,
        OFFICIAL_UI_NOT_RECOGNIZED, OFFICIAL_PAGE_NOT_RESTORED, SYNC_TAB_NOT_REACHED,
        OFFICIAL_WEB_CONTROL_NOT_REACHED, OFFICIAL_WEB_CLICK_REJECTED, OFFICIAL_WEB_FORM_NOT_REACHED,
        OFFICIAL_WEB_SHOW_REJECTED, OFFICIAL_WEB_FOCUS_REJECTED, OFFICIAL_WEB_SCROLL_PANE_NOT_REACHED,
        OFFICIAL_WEB_SCROLL_DIRECTION_NOT_PROVEN, OFFICIAL_WEB_SCROLL_ACTION_REJECTED, OFFICIAL_WEB_SCROLL_NO_PROGRESS,
        AMBIGUOUS_ACCESSIBLE_CONTROL,
        PASSWORD_INPUT_REJECTED, PASSWORD_INPUTS_NOT_RECOGNIZED, PRIVATE_EXPORT_PATH_RETURNED, PRIVATE_BACKUP_ARCHIVE_CHANGED,
        MULTIPLE_NEW_DOWNLOADS, STABLE_PUBLIC_DOWNLOAD_NOT_REACHED, DOWNLOAD_HASH_TOOL_FAILED,
        DOWNLOADED_FILE_CHANGED, DOCUMENTS_UI_LIST_NOT_RECOGNIZED, DOWNLOADED_FILE_NOT_REACHED_IN_PICKER,
        WRONG_PHRASE_ACCEPTED, WRONG_PHRASE_ERROR_NOT_REACHED, RESTORE_RESULT_NOT_REACHED,
        OFFICIAL_BROWSER_NAVIGATION_REJECTED,
        EVIDENCE_SANITIZATION_FAILED, EVIDENCE_WRITE_OR_ARCHIVE_FAILED,
    }

    private fun await(timeout: Long, reason: FailureReason = FailureReason.UI_WAIT_EXPIRED, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            try { if (condition()) return } catch (_: StaleObjectException) { /* reacquire only */ }
            SystemClock.sleep(200)
        }
        throw AcceptanceFailure(reason)
    }

    companion object {
        private const val CHROME = "com.android.chrome"
        private const val FIXTURE_ID = "browser-backup-fixture"
        private const val A_NAME = "Browser backup before export"
        private const val A_URL = "https://backup.example.invalid/v1"
        private const val A_MODEL = "backup-before"
        private const val B_NAME = "Browser backup modified"
        private const val B_URL = "https://changed.example.invalid/v1"
        private const val B_MODEL = "backup-after"
        private const val CLEAN_ENTRY = "http://127.0.0.1:3430/"
        private const val WRONG_PHRASE_ERROR = "wrong passphrase, or the file was changed"
        private val SETTINGS = listOf("Settings", "设置")
        private val CLEAN_ENTRY_URL = Regex("(?:http://)?127\\.0\\.0\\.1:3430/?(?:\\?view=providers)?")
        // The fixed official .warm-tabs CSS applies text-transform: uppercase;
        // retain both static forms within the Settings-only tab selector.
        private val SYNC_TAB = listOf("Sync and backup", "SYNC AND BACKUP", "同步与备份")
        private val EXPORT_OPEN = listOf("Export…", "导出…")
        private val EXPORT_SUBMIT = listOf("Export", "导出")
        private val IMPORT_OPEN = listOf("Import…", "导入…")
        private val IMPORT_SUBMIT = listOf("Import", "导入")
        private val CLOSE = listOf("Close", "关闭")
        private val CHOOSE = listOf("Choose…", "选择…")
        private val DOWNLOAD_RETRY = listOf("Download · magpie.magpie-backup", "下载 · magpie.magpie-backup")
        private val DOCUMENTS_PACKAGES = setOf("com.google.android.documentsui", "com.android.documentsui")
        // Fresh entry may show Providers through the official SPA's startup routing.
        // Both gates reject tickets, extra parameters, fragments and other origins.
        // CoreManagementClient independently rejects userinfo before the production ACTION_VIEW;
        // Chrome's editing formatter is not evidence of userinfo absence.
        private val SAFE_BASENAME = Regex("magpie(?: \\([0-9]+\\))?\\.magpie-backup")
        private val HASH_LINE = Regex("([0-9a-fA-F]{64})[ \\t]+(.+)")
        private val MISSING_DOWNLOAD_DIRECTORY = Regex("find: '?/sdcard/Download'?: No such file or directory")
        private val TEXT_ATTRIBUTES = setOf("text", "content-desc", "hint")
        private val SAFE_STRUCTURAL_ATTRIBUTES = setOf("rotation", "index", "resource-id", "class", "package", "bounds",
            "checkable", "checked", "clickable", "enabled", "focusable", "focused", "scrollable",
            "long-clickable", "password", "selected", "display-id", "drawing-order", "visible-to-user")
        private val SAFE_LABELS = (SETTINGS + SYNC_TAB + CLOSE + EXPORT_OPEN + EXPORT_SUBMIT + IMPORT_OPEN +
            IMPORT_SUBMIT + CHOOSE + DOWNLOAD_RETRY + listOf("Files", "文件", "Browse", "浏览", "Downloads",
            "下载内容", "下载", "Open", "打开", "Show roots", "显示根目录", "显示根目录列表", "Passphrase",
            "passphrase", "口令", "again", "再输入一次", "No file chosen", "未选择文件", "Cancel", "取消",
            "Download", "Download anyway", "仍然下载", WRONG_PHRASE_ERROR)).toSet()
    }
}
