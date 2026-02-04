package com.runanywhere.agent.kernel

import android.content.Context
import android.util.Log
import com.runanywhere.agent.AgentApplication
import com.runanywhere.agent.BuildConfig
import com.runanywhere.agent.actions.AppActions
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
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.util.regex.Pattern

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

    private val gptClient = GPTClient(
        apiKeyProvider = { BuildConfig.GPT52_API_KEY },
        onLog = onLog
    )

    private var activeModelId: String = AgentApplication.DEFAULT_MODEL
    private var isRunning = false
    private var planResult: PlanResult? = null

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
        history.clear()
        planResult = null

        try {
            emit(AgentEvent.Log("Starting agent..."))

            // Check if goal matches a shortcut pattern
            val shortcutMessage = tryShortcut(goal)
            if (shortcutMessage != null) {
                emit(AgentEvent.Log(shortcutMessage))
                emit(AgentEvent.Done("Completed via shortcut"))
                return@flow
            }

            if (gptClient.isConfigured()) {
                emit(AgentEvent.Log("Requesting GPT-5.2 plan..."))
                planResult = gptClient.generatePlan(goal)
                planResult?.let { plan ->
                    if (plan.steps.isNotEmpty()) {
                        emit(AgentEvent.Log("Plan:"))
                        plan.steps.forEachIndexed { index, step ->
                            emit(AgentEvent.Log("${index + 1}. $step"))
                        }
                    }
                    plan.successCriteria?.let { criteria ->
                        emit(AgentEvent.Log("Success criteria: $criteria"))
                    }
                }
            } else {
                emit(AgentEvent.Log("GPT-5.2 API key missing. Skipping planning."))
            }

            // Ensure model is ready
            emit(AgentEvent.Log("Loading model: $activeModelId"))
            ensureModelReady()
            emit(AgentEvent.Log("Model ready"))

            val startTime = System.currentTimeMillis()
            var step = 0

            while (step < MAX_STEPS) {
                step++
                emit(AgentEvent.Log("Step $step/$MAX_STEPS"))

                // Parse screen
                val screen = screenParser.parse()
                if (screen.elementCount == 0) {
                    emit(AgentEvent.Log("No elements found, waiting..."))
                    delay(STEP_DELAY_MS)
                    continue
                }

                // Get LLM decision with context
                val historyPrompt = history.formatForPrompt()
                val lastActionResult = history.getLastActionResult()
                val lastAction = history.getLastAction()

                val loopDetected = lastAction != null && history.isRepetitive(lastAction.action, lastAction.target)
                val hadFailure = history.hadRecentFailure()

                // Choose appropriate prompt based on context
                val prompt = when {
                    loopDetected -> {
                        emit(AgentEvent.Log("Loop detected, adding recovery prompt"))
                        SystemPrompts.buildLoopRecoveryPrompt(goal, screen.compactText, historyPrompt, lastActionResult)
                    }
                    hadFailure -> {
                        emit(AgentEvent.Log("Recent failure, adding recovery hints"))
                        SystemPrompts.buildFailureRecoveryPrompt(goal, screen.compactText, historyPrompt, lastActionResult)
                    }
                    else -> {
                        SystemPrompts.buildPrompt(goal, screen.compactText, historyPrompt, lastActionResult)
                    }
                }

                val escalateReason = when {
                    !gptClient.isConfigured() -> null
                    loopDetected -> "loop recovery"
                    hadFailure -> "failure recovery"
                    step > 3 && step % 4 == 0 -> "checkpoint"
                    else -> null
                }

                val decisionJson = if (escalateReason != null) {
                    emit(AgentEvent.Log("Using GPT-5.2 for this step ($escalateReason)"))
                    callRemoteLLM(prompt) ?: run {
                        emit(AgentEvent.Log("GPT-5.2 unavailable, falling back to local model"))
                        callLocalLLM(prompt)
                    }
                } else {
                    callLocalLLM(prompt)
                }
                val decision = parseDecision(decisionJson)

                emit(AgentEvent.Log("Action: ${decision.action}"))

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
        }
    }

    fun stop() {
        isRunning = false
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

    private suspend fun callLocalLLM(prompt: String): String {
        val options = LLMGenerationOptions(
            maxTokens = 32,
            temperature = 0.0f,
            topP = 0.95f,
            streamingEnabled = false,
            systemPrompt = null,
            structuredOutput = StructuredOutputConfig(
                typeName = "Act",
                includeSchemaInPrompt = true,
                jsonSchema = SystemPrompts.DECISION_SCHEMA
            )
        )

        return try {
            val result = withContext(Dispatchers.Default) {
                RunAnywhere.generate(prompt, options)
            }
            result.text
        } catch (e: Exception) {
            Log.e(TAG, "LLM call failed: ${e.message}", e)
            "{\"a\":\"done\"}"
        }
    }

    private suspend fun callRemoteLLM(prompt: String): String? {
        return gptClient.generateAction(prompt)
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
        val action = obj.optString("a", "").ifEmpty { obj.optString("action", "") }

        return Decision(
            action = action.ifEmpty { "done" },
            elementIndex = obj.optInt("i", -1).takeIf { it >= 0 },
            text = obj.optString("t", "").ifEmpty { obj.optString("text") }?.takeIf { it.isNotEmpty() },
            direction = obj.optString("d", "").ifEmpty { obj.optString("direction") }?.takeIf { it.isNotEmpty() },
            url = obj.optString("u", "").ifEmpty { obj.optString("url") }?.takeIf { it.isNotEmpty() },
            query = obj.optString("q", "").ifEmpty { obj.optString("query") }?.takeIf { it.isNotEmpty() }
        )
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
            else -> Decision("done")
        }
    }

    private fun tryShortcut(goal: String): String? {
        val lower = goal.lowercase().trim()

        // "open <app>" shortcut
        val openPatterns = listOf("open ", "launch ", "start ")
        for (prefix in openPatterns) {
            if (lower.startsWith(prefix)) {
                var appName = goal.substring(prefix.length).trim()
                val separators = listOf(" and ", " then ", ",")
                for (sep in separators) {
                    val idx = appName.lowercase().indexOf(sep)
                    if (idx >= 0) {
                        appName = appName.substring(0, idx).trim()
                        break
                    }
                }
                if (appName.lowercase().contains("setting")) {
                    if (actionExecutor.openSettings()) {
                        return "Opened Settings"
                    }
                }
                if (appName.isNotEmpty() && actionExecutor.openApp(appName)) {
                    return "Opened ${appName.trim()}"
                }
            }
        }

        // Settings shortcuts
        if (lower.contains("bluetooth settings") || lower == "turn on bluetooth" || lower == "turn off bluetooth") {
            if (actionExecutor.openSettings("bluetooth")) {
                return "Opened Bluetooth settings"
            }
        }
        if (lower.contains("wifi settings") || lower.contains("wi-fi settings")) {
            if (actionExecutor.openSettings("wifi")) {
                return "Opened Wi-Fi settings"
            }
        }

        val youtubeMatch = Regex("(?i)(?:play|watch) (.+?) on youtube").find(goal)
        if (youtubeMatch != null) {
            val query = youtubeMatch.groupValues[1].trim()
            if (query.isNotEmpty() && AppActions.openYouTubeSearch(context, query)) {
                return "Opened YouTube search for \"$query\""
            }
        }

        if (lower.contains("open clock") || lower.contains("start clock") || lower.contains("launch clock")) {
            if (AppActions.openClock(context)) {
                return "Opened Clock"
            }
        }

        val timerMatch = Regex("(?i)set (?:an? )?timer for (.+)").find(goal)
        if (timerMatch != null) {
            val timerText = timerMatch.groupValues[1]
            val seconds = parseTimerDuration(timerText)
            if (seconds != null && AppActions.setTimer(context, seconds, timerText)) {
                return "Timer set for ${formatDuration(seconds)}"
            }
        }

        return null
    }

    private fun parseTimerDuration(text: String): Int? {
        val pattern = Regex("(\\d+)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|sec|s)", RegexOption.IGNORE_CASE)
        var totalSeconds = 0
        var matched = false
        pattern.findAll(text).forEach { match ->
            val value = match.groupValues[1].toIntOrNull() ?: return@forEach
            val unit = match.groupValues[2].lowercase()
            totalSeconds += when {
                unit.startsWith("h") -> value * 3600
                unit.startsWith("m") -> value * 60
                else -> value
            }
            matched = true
        }

        if (!matched) {
            text.trim().toIntOrNull()?.let {
                totalSeconds = it
                matched = true
            }
        }

        return if (matched && totalSeconds > 0) totalSeconds else null
    }

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remaining = seconds % 60
        return when {
            hours > 0 && minutes > 0 && remaining > 0 -> "$hours h $minutes m $remaining s"
            hours > 0 && minutes > 0 -> "$hours h $minutes m"
            hours > 0 -> "$hours h"
            minutes > 0 && remaining > 0 -> "$minutes m $remaining s"
            minutes > 0 -> "$minutes m"
            else -> "$remaining s"
        }
    }
}
