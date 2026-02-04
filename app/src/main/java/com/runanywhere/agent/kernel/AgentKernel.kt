package com.runanywhere.agent.kernel

import android.content.Context
import android.util.Log
import com.runanywhere.agent.AgentApplication
import com.runanywhere.agent.BuildConfig
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
        private const val MAX_STEPS = 20
        private const val MAX_DURATION_MS = 120_000L
        private const val STEP_DELAY_MS = 2000L
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

            if (gptClient.isConfigured()) {
                emit(AgentEvent.Log("Requesting GPT-4o plan..."))
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
                emit(AgentEvent.Log("GPT-4o API key missing. Skipping planning."))
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

                val decisionJson = if (gptClient.isConfigured()) {
                    emit(AgentEvent.Log("Using GPT-4o..."))
                    callRemoteLLM(prompt) ?: run {
                        emit(AgentEvent.Log("GPT-4o unavailable, falling back to local model"))
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
        val action = obj.optString("action", "").ifEmpty { obj.optString("a", "") }

        // Support both "index" (new) and "i" (old) keys
        val index = obj.optInt("index", -1).let { if (it >= 0) it else obj.optInt("i", -1) }.takeIf { it >= 0 }

        // Map direction values: support both full words and abbreviations
        val rawDirection = obj.optString("direction", "").ifEmpty { obj.optString("d", "") }.takeIf { it.isNotEmpty() }
        val direction = when (rawDirection) {
            "up" -> "u"
            "down" -> "d"
            "left" -> "l"
            "right" -> "r"
            else -> rawDirection
        }

        return Decision(
            action = action.ifEmpty { "done" },
            elementIndex = index,
            text = obj.optString("text", "").ifEmpty { obj.optString("t") }?.takeIf { it.isNotEmpty() },
            direction = direction,
            url = obj.optString("url", "").ifEmpty { obj.optString("u") }?.takeIf { it.isNotEmpty() },
            query = obj.optString("query", "").ifEmpty { obj.optString("q") }?.takeIf { it.isNotEmpty() }
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

}
