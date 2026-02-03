package com.runanywhere.agent.kernel

import android.content.Context
import android.util.Log
import com.runanywhere.agent.AgentApplication
import com.runanywhere.agent.accessibility.AgentAccessibilityService
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.LLM.LLMGenerationOptions
import com.runanywhere.sdk.public.extensions.LLM.StructuredOutputConfig
import com.runanywhere.sdk.public.extensions.downloadModel
import com.runanywhere.sdk.public.extensions.generate
import com.runanywhere.sdk.public.extensions.loadLLMModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.util.regex.Pattern
import kotlin.coroutines.coroutineContext

class AgentKernel(
    private val context: Context,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "AgentKernel"
        private const val MAX_STEPS = 15
        private const val MAX_DURATION_MS = 60_000L
        private const val STEP_DELAY_MS = 1000L
    }

    private val history = ActionHistory()
    private val screenParser = ScreenParser { AgentAccessibilityService.instance }
    private val actionExecutor = ActionExecutor(
        context = context,
        accessibilityService = { AgentAccessibilityService.instance },
        onLog = onLog
    )

    data class Plan(
        val steps: List<String>,
        val successCriteria: String?
    )

    private var activeModelId: String = AgentApplication.DEFAULT_MODEL
    private var isRunning = false
    @Volatile private var stopRequested = false

    fun setModel(modelId: String) {
        activeModelId = modelId
    }

    fun getModel(): String = activeModelId

    sealed class AgentEvent {
        data class Log(val message: String) : AgentEvent()
        data class Step(val step: Int, val action: String, val result: String) : AgentEvent()
        data class Done(val message: String) : AgentEvent()
        data class Error(val message: String) : AgentEvent()
    }

    fun run(goal: String): Flow<AgentEvent> = flow {
        if (isRunning) {
            emit(AgentEvent.Error("Agent already running"))
            return@flow
        }

        isRunning = true
        stopRequested = false
        history.clear()

        try {
            emit(AgentEvent.Log("Starting agent..."))

            if (!coroutineContext.isActive || stopRequested) {
                emit(AgentEvent.Log("Agent cancelled"))
                return@flow
            }

            // Check if goal matches a shortcut pattern
            if (tryShortcut(goal)) {
                emit(AgentEvent.Done("Completed via shortcut"))
                return@flow
            }

            // Ensure model is ready
            emit(AgentEvent.Log("Loading model: $activeModelId"))
            ensureModelReady()
            emit(AgentEvent.Log("Model ready"))

            val plan = try {
                emit(AgentEvent.Log("Planning..."))
                callPlanner(goal)
            } catch (e: Exception) {
                emit(AgentEvent.Log("Planning skipped: ${e.message}"))
                null
            }

            val startTime = System.currentTimeMillis()
            var step = 0
            var planStepIndex = 0

            while (step < MAX_STEPS) {
                if (!coroutineContext.isActive || stopRequested) {
                    emit(AgentEvent.Log("Agent cancelled"))
                    return@flow
                }

                step++
                emit(AgentEvent.Log("Step $step/$MAX_STEPS"))

                // Parse screen
                val screen = screenParser.parse(maxElements = if (history.hadRecentFailure()) 20 else 12)
                if (screen.elementCount == 0) {
                    emit(AgentEvent.Log("No elements found, waiting..."))
                    delay(STEP_DELAY_MS)
                    continue
                }

                // Get LLM decision with context
                val historyPrompt = history.formatForPrompt()
                val lastActionResult = history.getLastActionResult()
                val lastAction = history.getLastAction()

                // Choose appropriate prompt based on context
                val prompt = when {
                    // Loop detected
                    lastAction != null && history.isRepetitive(lastAction.action, lastAction.target) -> {
                        emit(AgentEvent.Log("Loop detected, adding recovery prompt"))
                        SystemPrompts.buildLoopRecoveryPrompt(goal, screen.compactText, historyPrompt, lastActionResult)
                    }
                    // Recent failure
                    history.hadRecentFailure() -> {
                        emit(AgentEvent.Log("Recent failure, adding recovery hints"))
                        SystemPrompts.buildFailureRecoveryPrompt(goal, screen.compactText, historyPrompt, lastActionResult)
                    }
                    // Normal prompt with action result feedback
                    else -> {
                        SystemPrompts.buildPrompt(
                            goal = goal,
                            screenState = screen.compactText,
                            history = historyPrompt,
                            lastActionResult = lastActionResult,
                            plan = plan?.steps,
                            successCriteria = plan?.successCriteria,
                            activeStepIndex = planStepIndex
                        )
                    }
                }

                val decisionJson = callLLM(prompt)
                val decision = parseDecision(decisionJson)

                val reason = decision.reason?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                emit(AgentEvent.Log("Action: ${decision.action}$reason"))

                // Execute action
                val result = actionExecutor.execute(decision, screen.indexToCoords)
                emit(AgentEvent.Step(step, decision.action, result.message))

                // Record in history with success/failure
                val target = when {
                    decision.elementIndex != null -> screenParser.getElementLabel(decision.elementIndex)
                    decision.text != null -> decision.text
                    decision.url != null -> decision.url
                    decision.query != null -> decision.query
                    else -> null
                }
                history.record(decision.action, target, result.message, result.success)

                if (result.success && decision.action != "wait") {
                    planStepIndex = (planStepIndex + 1).coerceAtMost((plan?.steps?.size ?: 1) - 1)
                }

                // Check for completion
                if (decision.action == "done") {
                    emit(AgentEvent.Done("Goal achieved"))
                    return@flow
                }

                // Check timeout
                if (System.currentTimeMillis() - startTime > MAX_DURATION_MS) {
                    emit(AgentEvent.Done("Max duration reached"))
                    return@flow
                }

                delay(STEP_DELAY_MS)
            }

            emit(AgentEvent.Done("Max steps reached"))

        } catch (e: CancellationException) {
            emit(AgentEvent.Log("Agent cancelled"))
        } catch (e: Exception) {
            Log.e(TAG, "Agent error: ${e.message}", e)
            emit(AgentEvent.Error(e.message ?: "Unknown error"))
        } finally {
            isRunning = false
            stopRequested = false
        }
    }

    fun stop() {
        stopRequested = true
    }

    private suspend fun ensureModelReady() {
        try {
            RunAnywhere.loadLLMModel(activeModelId)
        } catch (e: Exception) {
            onLog("Downloading model...")
            var lastPercent = -1
            RunAnywhere.downloadModel(activeModelId).collect { progress ->
                val percent = (progress.progress * 100).toInt()
                if (percent != lastPercent && percent % 10 == 0) {
                    lastPercent = percent
                    onLog("Downloading... $percent%")
                }
            }
            RunAnywhere.loadLLMModel(activeModelId)
        }
    }

    private suspend fun callLLM(prompt: String): String {
        val options = LLMGenerationOptions(
            maxTokens = 128,
            temperature = 0.0f,
            topP = 0.95f,
            streamingEnabled = false,
            systemPrompt = SystemPrompts.AGENT_SYSTEM_PROMPT,
            structuredOutput = StructuredOutputConfig(
                typeName = "Act",
                includeSchemaInPrompt = true,
                jsonSchema = SystemPrompts.DECISION_SCHEMA
            )
        )

        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val result = withContext(Dispatchers.Default) {
                    RunAnywhere.generate(prompt, options)
                }
                return result.text
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "LLM call failed (attempt ${attempt + 1}): ${e.message}", e)
                delay(500)
            }
        }
        throw lastError ?: RuntimeException("LLM call failed")
    }

    private suspend fun callPlanner(goal: String): Plan {
        val options = LLMGenerationOptions(
            maxTokens = 256,
            temperature = 0.2f,
            topP = 0.95f,
            streamingEnabled = false,
            systemPrompt = SystemPrompts.AGENT_SYSTEM_PROMPT,
            structuredOutput = StructuredOutputConfig(
                typeName = "Plan",
                includeSchemaInPrompt = true,
                jsonSchema = SystemPrompts.PLANNING_SCHEMA
            )
        )

        val prompt = SystemPrompts.buildPlanningPrompt(goal)
        val result = withContext(Dispatchers.Default) {
            RunAnywhere.generate(prompt, options)
        }

        val cleaned = result.text.replace("```json", "").replace("```", "").trim()
        val obj = JSONObject(cleaned)
        val steps = obj.optJSONArray("steps")
        val list = mutableListOf<String>()
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val s = steps.optString(i).trim()
                if (s.isNotEmpty()) list.add(s)
            }
        }
        if (list.isEmpty()) list.add("Complete the goal")
        return Plan(
            steps = list,
            successCriteria = obj.optString("success_criteria").takeIf { it.isNotBlank() }
        )
    }

    private fun parseDecision(text: String): Decision {
        val cleaned = text
            .replace("```json", "")
            .replace("```", "")
            .trim()

        // Try parsing as JSON
        try {
            val obj = JSONObject(cleaned)
            return extractDecision(obj)
        } catch (_: JSONException) {}

        // Try extracting JSON from text
        val matcher = Pattern.compile("\\{.*?\\}", Pattern.DOTALL).matcher(cleaned)
        if (matcher.find()) {
            try {
                return extractDecision(JSONObject(matcher.group()))
            } catch (_: JSONException) {}
        }

        // Fallback: heuristic parsing
        return heuristicDecision(cleaned)
    }

    private fun extractDecision(obj: JSONObject): Decision {
        val rawAction = obj.optString("a", "").ifEmpty { obj.optString("action", "") }
        val action = normalizeAction(rawAction, obj)

        return Decision(
            action = action.ifEmpty { "done" },
            elementIndex = obj.optInt("i", -1).takeIf { it >= 0 },
            text = obj.optString("t", "").ifEmpty { obj.optString("text") }?.takeIf { it.isNotEmpty() },
            tapText = obj.optString("tt", "").ifEmpty { obj.optString("tap_text") }?.takeIf { it.isNotEmpty() },
            resourceId = obj.optString("id", "").ifEmpty { obj.optString("resource_id") }?.takeIf { it.isNotEmpty() },
            toggleKeyword = obj.optString("k", "").ifEmpty { obj.optString("keyword") }?.takeIf { it.isNotEmpty() },
            direction = obj.optString("d", "").ifEmpty { obj.optString("direction") }?.takeIf { it.isNotEmpty() },
            timeoutMs = obj.optInt("ms", -1).takeIf { it > 0 },
            maxSwipes = obj.optInt("n", -1).takeIf { it > 0 },
            url = obj.optString("u", "").ifEmpty { obj.optString("url") }?.takeIf { it.isNotEmpty() },
            query = obj.optString("q", "").ifEmpty { obj.optString("query") }?.takeIf { it.isNotEmpty() },
            app = obj.optString("app", "").ifEmpty { obj.optString("p") }?.takeIf { it.isNotEmpty() },
            reason = obj.optString("r", "").ifEmpty { obj.optString("reason") }?.takeIf { it.isNotEmpty() }
        )
    }

    private fun normalizeAction(rawAction: String, obj: JSONObject): String {
        val a = rawAction.trim().lowercase()

        // Common failure mode: model returns placeholder "ACTION".
        if (a.isBlank() || a == "action") {
            val hasQ = obj.optString("q", obj.optString("query", "")).isNotBlank()
            val hasU = obj.optString("u", obj.optString("url", "")).isNotBlank()
            val hasApp = obj.optString("app", obj.optString("p", "")).isNotBlank()
            val hasT = obj.optString("t", obj.optString("text", "")).isNotBlank()
            val hasTt = obj.optString("tt", obj.optString("tap_text", "")).isNotBlank()
            val hasId = obj.optString("id", obj.optString("resource_id", "")).isNotBlank()
            val hasI = obj.has("i") && obj.optInt("i", -1) >= 0
            val hasD = obj.optString("d", obj.optString("direction", "")).isNotBlank()
            val hasK = obj.optString("k", obj.optString("keyword", "")).isNotBlank()

            return when {
                hasQ -> "search"
                hasU -> "url"
                hasApp -> "open"
                hasT -> "type"
                hasTt -> "tap_text"
                hasId -> "tap_id"
                hasI -> "tap"
                hasK -> "toggle"
                hasD -> "swipe"
                else -> "done"
            }
        }

        // Accept a few aliases.
        return when (a) {
            "click" -> "tap"
            "taptext" -> "tap_text"
            "tap_text" -> "tap_text"
            "tapid" -> "tap_id"
            "tap_id" -> "tap_id"
            "press_enter", "submit" -> "enter"
            "scroll" -> "swipe"
            else -> a
        }
    }

    private fun heuristicDecision(text: String): Decision {
        val lower = text.lowercase()

        return when {
            lower.contains("done") -> Decision("done")
            lower.contains("back") -> Decision("back")
            lower.contains("home") -> Decision("home")
            lower.contains("enter") -> Decision("enter")
            lower.contains("wait") -> Decision("wait")
            lower.contains("swipe") || lower.contains("scroll") -> {
                val dir = when {
                    lower.contains("up") -> "u"
                    lower.contains("down") -> "d"
                    lower.contains("left") -> "l"
                    lower.contains("right") -> "r"
                    else -> "u"
                }
                Decision("swipe", direction = dir)
            }
            lower.contains("tap") || lower.contains("click") -> {
                val idx = Regex("\\d+").find(text)?.value?.toIntOrNull() ?: 0
                Decision("tap", elementIndex = idx)
            }
            lower.contains("type") -> {
                val textMatch = Regex("\"([^\"]+)\"").find(text)
                Decision("type", text = textMatch?.groupValues?.getOrNull(1) ?: "")
            }
            lower.startsWith("open ") || lower.startsWith("launch ") -> {
                val app = text.substringAfter(' ').trim()
                Decision("open", app = app)
            }
            else -> Decision("done")
        }
    }

    private suspend fun tryShortcut(goal: String): Boolean {
        val lower = goal.lowercase().trim()

        // "play on youtube" shortcut - open YouTube and search via accessibility (no hardcoded coordinates)
        if ((lower.contains("youtube") || lower.contains("yt")) &&
            (lower.contains("play") || lower.contains("watch"))) {

            // Extract search query if present
            val searchQuery = extractSearchQuery(lower)

            val opened = actionExecutor.openApp("YouTube")
            if (opened) {
                delay(2500) // Wait for YouTube to load

                val service = AgentAccessibilityService.instance
                if (service != null && searchQuery != null) {
                    onLog("Searching YouTube for: $searchQuery")

                    // Open search UI
                    val searchNode = service.findNodeByText("Search")
                        ?: service.findNodeByResourceId("search")
                    if (searchNode != null) {
                        service.clickByBounds(searchNode)
                        delay(800)
                    }

                    // Type query
                    val typed = service.typeText(searchQuery)
                    delay(300)

                    // Submit
                    val submitted = service.submit()
                    if (!typed || !submitted) {
                        return false
                    }

                    delay(2500)

                    // Tap first visible video-like element.
                    val videoNode = service.findNodeByText("views")
                        ?: service.findNodeByText("Watch")
                        ?: service.findNodeByText("minutes")
                    if (videoNode != null) {
                        service.clickByBounds(videoNode)
                        return true
                    }
                }

                // If query is missing, let the LLM continue.
                return searchQuery == null
            }
            return false
        }

        // "open <app>" shortcut
        val openPatterns = listOf("open ", "launch ", "start ")
        for (prefix in openPatterns) {
            if (lower.startsWith(prefix)) {
                var appName = goal.substring(prefix.length).trim()
                var hasMoreSteps = false

                // Stop at conjunctions and check if there's more to do
                val separators = listOf(" and ", " then ", ",")
                for (sep in separators) {
                    val idx = appName.lowercase().indexOf(sep)
                    if (idx >= 0) {
                        appName = appName.substring(0, idx).trim()
                        hasMoreSteps = true  // Goal continues after opening app
                        break
                    }
                }

                // Check if it's a settings shortcut
                if (appName.lowercase().contains("setting")) {
                    val opened = actionExecutor.openSettings()
                    // If there are more steps, let LLM continue
                    return opened && !hasMoreSteps
                }

                if (appName.isNotEmpty()) {
                    val opened = actionExecutor.openApp(appName)
                    if (opened && hasMoreSteps) {
                        // App opened, but there's more to do - let LLM continue
                        return false
                    }
                    return opened
                }
            }
        }

        // Settings shortcuts
        if (lower.contains("bluetooth settings") || lower == "turn on bluetooth" || lower == "turn off bluetooth") {
            return actionExecutor.openSettings("bluetooth")
        }
        if (lower.contains("wifi settings") || lower.contains("wi-fi settings")) {
            return actionExecutor.openSettings("wifi")
        }

        return false
    }

    private fun extractSearchQuery(goal: String): String? {
        // Extract what to search for from goal
        // "play drake on youtube" -> "drake"
        // "watch lofi music on youtube" -> "lofi music"
        // "play a video on youtube" -> null (just play any video)

        val patterns = listOf(
            "play (.+?) on (?:youtube|yt)",
            "watch (.+?) on (?:youtube|yt)",
            "search (?:for )?(.+?) on (?:youtube|yt)",
            "(?:youtube|yt).+?(?:play|watch|search) (.+)",
            "(?:play|watch) (.+?) (?:video|song|music)"
        )

        for (pattern in patterns) {
            val match = Regex(pattern, RegexOption.IGNORE_CASE).find(goal)
            if (match != null) {
                val query = match.groupValues[1].trim()
                // Filter out generic words
                if (query.isNotEmpty() &&
                    query !in listOf("a", "any", "some", "random", "a video", "video", "something")) {
                    return query
                }
            }
        }
        return null
    }
}
