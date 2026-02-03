package com.runanywhere.agent.kernel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.runanywhere.agent.accessibility.AgentAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

class ActionExecutor(
    private val context: Context,
    private val accessibilityService: () -> AgentAccessibilityService?,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "ActionExecutor"
    }

    suspend fun execute(decision: Decision, indexToCoords: Map<Int, Pair<Int, Int>>): ExecutionResult {
        val service = accessibilityService()
        if (service == null && decision.action !in listOf("url", "search", "open", "done", "wait")) {
            return ExecutionResult(false, "Accessibility service not connected")
        }

        return when (decision.action) {
            "tap" -> executeTap(service!!, decision, indexToCoords)
            "tap_text" -> executeTapText(service!!, decision)
            "tap_id" -> executeTapId(service!!, decision)
            "type" -> executeType(service!!, decision)
            "enter" -> executeEnter(service!!)
            "swipe" -> executeSwipe(service!!, decision)
            "long" -> executeLongPress(service!!, decision, indexToCoords)
            "toggle" -> executeToggle(service!!, decision)
            "wait_for" -> executeWaitFor(service!!, decision)
            "scroll_find" -> executeScrollFind(service!!, decision)
            "back" -> executeBack(service!!)
            "home" -> executeHome(service!!)
            "url" -> executeOpenUrl(decision)
            "search" -> executeWebSearch(decision)
            "open" -> executeOpenApp(decision)
            "notif" -> executeOpenNotifications(service!!)
            "quick" -> executeOpenQuickSettings(service!!)
            "screenshot" -> executeScreenshot(service!!)
            "wait" -> executeWait()
            "done" -> ExecutionResult(true, "Goal complete")
            else -> ExecutionResult(false, "Unknown action: ${decision.action}")
        }
    }

    private fun executeTapText(service: AgentAccessibilityService, decision: Decision): ExecutionResult {
        val text = decision.tapText ?: return ExecutionResult(false, "No text provided")
        onLog("Tapping by text: $text")
        val node = service.findNodeByText(text) ?: return ExecutionResult(false, "Text not found: $text")
        val ok = service.clickByBounds(node)
        return ExecutionResult(ok, if (ok) "Tapped text: $text" else "Tap failed")
    }

    private fun executeTapId(service: AgentAccessibilityService, decision: Decision): ExecutionResult {
        val id = decision.resourceId ?: return ExecutionResult(false, "No resource id provided")
        onLog("Tapping by id: $id")
        val node = service.findNodeByResourceId(id) ?: return ExecutionResult(false, "Id not found: $id")
        val ok = service.clickByBounds(node)
        return ExecutionResult(ok, if (ok) "Tapped id: $id" else "Tap failed")
    }

    private fun executeToggle(service: AgentAccessibilityService, decision: Decision): ExecutionResult {
        val keyword = decision.toggleKeyword ?: return ExecutionResult(false, "No toggle keyword provided")
        onLog("Toggling: $keyword")
        val node = service.findToggleNode(keyword) ?: return ExecutionResult(false, "Toggle not found: $keyword")
        val ok = service.clickByBounds(node)
        return ExecutionResult(ok, if (ok) "Toggled: $keyword" else "Toggle failed")
    }

    private suspend fun executeWaitFor(service: AgentAccessibilityService, decision: Decision): ExecutionResult {
        val timeoutMs = (decision.timeoutMs ?: 5000).coerceIn(500, 30_000)
        val targetText = decision.tapText
        val targetId = decision.resourceId
        if (targetText == null && targetId == null) return ExecutionResult(false, "wait_for requires tt or id")

        onLog("Waiting for ${targetId?.let { "id=$it" } ?: "text=$targetText"} (timeout ${timeoutMs}ms)")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val found = when {
                targetId != null -> service.findNodeByResourceId(targetId)
                else -> service.findNodeByText(targetText!!)
            }
            if (found != null) return ExecutionResult(true, "Found")
            delay(300)
        }
        return ExecutionResult(false, "Not found within ${timeoutMs}ms")
    }

    private suspend fun executeScrollFind(service: AgentAccessibilityService, decision: Decision): ExecutionResult {
        val maxSwipes = (decision.maxSwipes ?: 6).coerceIn(1, 20)
        val direction = (decision.direction ?: "u")
        val targetText = decision.tapText
        val targetId = decision.resourceId
        if (targetText == null && targetId == null) return ExecutionResult(false, "scroll_find requires tt or id")

        onLog("Scrolling to find ${targetId?.let { "id=$it" } ?: "text=$targetText"} (d=$direction, n=$maxSwipes)")

        repeat(maxSwipes + 1) { idx ->
            val found = when {
                targetId != null -> service.findNodeByResourceId(targetId)
                else -> service.findNodeByText(targetText!!)
            }
            if (found != null) {
                val ok = service.clickByBounds(found)
                return ExecutionResult(ok, if (ok) "Found and tapped" else "Found but tap failed")
            }

            if (idx < maxSwipes) {
                val swiped = suspendCancellableCoroutine { cont ->
                    service.swipe(direction) { ok -> cont.resume(ok) }
                }
                if (!swiped) return ExecutionResult(false, "Swipe failed")
                delay(600)
            }
        }

        return ExecutionResult(false, "Not found")
    }

    private suspend fun executeTap(
        service: AgentAccessibilityService,
        decision: Decision,
        indexToCoords: Map<Int, Pair<Int, Int>>
    ): ExecutionResult {
        val coords = indexToCoords[decision.elementIndex]
            ?: return ExecutionResult(false, "Invalid element index: ${decision.elementIndex}")

        onLog("Tapping element ${decision.elementIndex} at (${coords.first}, ${coords.second})")

        return suspendCancellableCoroutine { cont ->
            service.tap(coords.first, coords.second) { success ->
                if (success) {
                    cont.resume(ExecutionResult(true, "Tapped element ${decision.elementIndex}"))
                } else {
                    cont.resume(ExecutionResult(false, "Tap failed"))
                }
            }
        }
    }

    private fun executeType(service: AgentAccessibilityService, decision: Decision): ExecutionResult {
        val text = decision.text ?: return ExecutionResult(false, "No text to type")
        onLog("Typing: $text")
        val success = service.typeText(text)
        return ExecutionResult(success, if (success) "Typed: $text" else "Type failed - no editable field")
    }

    private fun executeEnter(service: AgentAccessibilityService): ExecutionResult {
        onLog("Pressing Enter")
        val success = service.submit()
        return ExecutionResult(success, if (success) "Pressed Enter" else "Enter failed")
    }

    private suspend fun executeSwipe(
        service: AgentAccessibilityService,
        decision: Decision
    ): ExecutionResult {
        val direction = decision.direction ?: "u"
        val dirName = when (direction) {
            "u" -> "up"
            "d" -> "down"
            "l" -> "left"
            "r" -> "right"
            else -> direction
        }
        onLog("Swiping $dirName")

        return suspendCancellableCoroutine { cont ->
            service.swipe(direction) { success ->
                cont.resume(ExecutionResult(success, if (success) "Swiped $dirName" else "Swipe failed"))
            }
        }
    }

    private suspend fun executeLongPress(
        service: AgentAccessibilityService,
        decision: Decision,
        indexToCoords: Map<Int, Pair<Int, Int>>
    ): ExecutionResult {
        val coords = indexToCoords[decision.elementIndex]
            ?: return ExecutionResult(false, "Invalid element index: ${decision.elementIndex}")

        onLog("Long pressing element ${decision.elementIndex}")

        return suspendCancellableCoroutine { cont ->
            service.longPress(coords.first, coords.second) { success ->
                cont.resume(ExecutionResult(success, if (success) "Long pressed" else "Long press failed"))
            }
        }
    }

    private fun executeBack(service: AgentAccessibilityService): ExecutionResult {
        onLog("Going back")
        val success = service.pressBack()
        return ExecutionResult(success, if (success) "Went back" else "Back failed")
    }

    private fun executeHome(service: AgentAccessibilityService): ExecutionResult {
        onLog("Going home")
        val success = service.pressHome()
        return ExecutionResult(success, if (success) "Went home" else "Home failed")
    }

    private fun executeOpenUrl(decision: Decision): ExecutionResult {
        val url = decision.url ?: return ExecutionResult(false, "No URL provided")
        onLog("Opening URL: $url")
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ExecutionResult(true, "Opened URL: $url")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL: ${e.message}")
            ExecutionResult(false, "Failed to open URL: ${e.message}")
        }
    }

    private fun executeWebSearch(decision: Decision): ExecutionResult {
        val query = decision.query ?: return ExecutionResult(false, "No search query provided")
        onLog("Searching: $query")
        return try {
            val searchUrl = "https://www.google.com/search?q=${Uri.encode(query)}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ExecutionResult(true, "Searched: $query")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search: ${e.message}")
            ExecutionResult(false, "Failed to search: ${e.message}")
        }
    }

    private fun executeOpenApp(decision: Decision): ExecutionResult {
        val app = decision.app ?: return ExecutionResult(false, "No app name provided")
        onLog("Opening app: $app")
        val opened = openApp(app)
        return ExecutionResult(opened, if (opened) "Opened app: $app" else "App not found: $app")
    }

    private fun executeOpenNotifications(service: AgentAccessibilityService): ExecutionResult {
        onLog("Opening notifications")
        val success = service.openNotifications()
        return ExecutionResult(success, if (success) "Opened notifications" else "Failed to open notifications")
    }

    private fun executeOpenQuickSettings(service: AgentAccessibilityService): ExecutionResult {
        onLog("Opening quick settings")
        val success = service.openQuickSettings()
        return ExecutionResult(success, if (success) "Opened quick settings" else "Failed to open quick settings")
    }

    private suspend fun executeScreenshot(service: AgentAccessibilityService): ExecutionResult {
        onLog("Taking screenshot")
        val file = File(context.cacheDir, "screenshot_${System.currentTimeMillis()}.png")

        return suspendCancellableCoroutine { cont ->
            service.takeScreenshot(file) { success ->
                if (success) {
                    cont.resume(ExecutionResult(true, "Screenshot saved: ${file.absolutePath}"))
                } else {
                    cont.resume(ExecutionResult(false, "Screenshot failed"))
                }
            }
        }
    }

    private suspend fun executeWait(): ExecutionResult {
        onLog("Waiting...")
        delay(2000)
        return ExecutionResult(true, "Waited 2 seconds")
    }

    // ========== App Shortcuts ==========

    fun openApp(appName: String): Boolean {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(intent, 0)
        val target = appName.lowercase().replace("[^a-z0-9 ]".toRegex(), "").trim()
        val targetWords = target.split(" ").filter { it.isNotEmpty() }
        val targetCompact = target.replace(" ", "")

        // Score matches: exact match > starts with > contains > word match
        val scored = apps.mapNotNull { info ->
            val label = info.loadLabel(pm)?.toString().orEmpty()
            val labelLower = label.lowercase()
            val labelNorm = labelLower.replace("[^a-z0-9]".toRegex(), "")
            val pkgNorm = (info.activityInfo.packageName ?: "").lowercase().replace("[^a-z0-9]".toRegex(), "")

            val score = when {
                labelNorm == targetCompact -> 100  // Exact label match
                labelNorm.startsWith(targetCompact) -> 80  // Label starts with target
                // Check if any target word exactly matches the label (e.g., "Google Maps" -> "Maps")
                targetWords.any { word -> labelNorm == word } -> 75
                pkgNorm.endsWith(targetCompact) -> 70  // Package ends with target
                // Check if label contains any significant target word (length > 3)
                targetWords.any { word -> word.length > 3 && labelNorm == word } -> 65
                labelNorm.contains(targetCompact) && !labelNorm.contains("music") -> 60
                pkgNorm.contains(targetCompact) && !pkgNorm.contains("music") -> 50
                // Word-based partial match (e.g., "maps" in "com.google.android.apps.maps")
                targetWords.any { word -> word.length > 3 && pkgNorm.contains(word) && !pkgNorm.contains("music") } -> 45
                labelNorm.contains(targetCompact) -> 30
                pkgNorm.contains(targetCompact) -> 20
                else -> 0
            }

            if (score > 0) Pair(info, score) else null
        }.sortedByDescending { it.second }

        val match = scored.firstOrNull()?.first

        if (match != null) {
            val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch != null) {
                context.startActivity(launch)
                onLog("Opened app: ${match.loadLabel(pm)}")
                return true
            }
        }

        onLog("App not found: $appName")
        return false
    }

    fun openSettings(settingType: String? = null): Boolean {
        val action = when (settingType?.lowercase()) {
            "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
            "wifi", "wi-fi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
            "display" -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
            "sound", "audio" -> android.provider.Settings.ACTION_SOUND_SETTINGS
            "battery" -> android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> android.provider.Settings.ACTION_SETTINGS
        }

        return try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            onLog("Opened settings: ${settingType ?: "main"}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings: ${e.message}")
            false
        }
    }
}

data class Decision(
    val action: String,
    val elementIndex: Int? = null,
    val text: String? = null,
    val tapText: String? = null,
    val resourceId: String? = null,
    val toggleKeyword: String? = null,
    val direction: String? = null,
    val timeoutMs: Int? = null,
    val maxSwipes: Int? = null,
    val url: String? = null,
    val query: String? = null,
    val app: String? = null,
    val reason: String? = null
)

data class ExecutionResult(
    val success: Boolean,
    val message: String
)
