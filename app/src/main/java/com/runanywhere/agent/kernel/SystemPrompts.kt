package com.runanywhere.agent.kernel

object SystemPrompts {

    const val AGENT_SYSTEM_PROMPT = """
You are an Android UI automation agent running on-device.
You can only interact via the provided ACTIONS and must output a SINGLE JSON object matching the given schema.
Prefer stable strategies: open apps by name, tap by visible text/resource id when possible, avoid repeating actions, and verify success criteria before outputting done.
"""

    val DECISION_SCHEMA = """
{
  "type":"object",
  "properties":{
    "a":{"type":"string","enum":["tap","tap_text","tap_id","type","enter","swipe","long","toggle","wait_for","scroll_find","back","home","url","search","open","notif","quick","screenshot","wait","done"]},
    "i":{"type":"integer"},
    "t":{"type":"string"},
    "tt":{"type":"string"},
    "id":{"type":"string"},
    "k":{"type":"string"},
    "d":{"type":"string","enum":["u","d","l","r"]},
    "ms":{"type":"integer"},
    "n":{"type":"integer"},
    "u":{"type":"string"},
    "q":{"type":"string"},
    "app":{"type":"string"},
    "r":{"type":"string"}
  },
  "required":["a"]
}
    """.trimIndent()

    val PLANNING_SCHEMA = """
{
  "type":"object",
  "properties":{
    "steps":{"type":"array","items":{"type":"string"}},
    "success_criteria":{"type":"string"}
  },
  "required":["steps"]
}
    """.trimIndent()

    fun buildPrompt(
        goal: String,
        screenState: String,
        history: String,
        lastActionResult: String? = null,
        plan: List<String>? = null,
        successCriteria: String? = null,
        activeStepIndex: Int? = null
    ): String {
        val lastResultSection = lastActionResult?.let {
            "\nLAST_ACTION: $it"
        } ?: ""

        val planSection = if (!plan.isNullOrEmpty()) {
            val idx = activeStepIndex?.coerceAtLeast(0) ?: 0
            val step = plan.getOrNull(idx)
            val steps = plan.take(8).mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
            val criteria = successCriteria?.let { "\nSUCCESS_CRITERIA: $it" }.orEmpty()
            "\nPLAN:\n$steps\nCURRENT_STEP: ${idx + 1}${step?.let { " — $it" }.orEmpty()}$criteria"
        } else {
            ""
        }

        return """
GOAL: $goal
SCREEN:
$screenState$lastResultSection$history$planSection
ACTIONS: open(app), tap(i), tap_text(tt), tap_id(id), type(t), enter, toggle(k), wait_for(tt|id, ms), scroll_find(tt|id, d, n), swipe(d:u/d/l/r), long(i), back, home, url(u), search(q), notif, quick, screenshot, wait, done
RULES:
1. After typing, use enter to submit
2. If action failed, try different approach
3. Check HISTORY - don't repeat same action
4. Prefer tap_text/tap_id for stable clicks; only use tap(i) when needed
5. Output done ONLY when success criteria is satisfied
OUTPUT (single JSON object): {"a":"tap","i":0,"t":"text","tt":"visible text","id":"resource id","k":"toggle keyword","d":"u","ms":5000,"n":6,"u":"https://example.com","q":"search query","app":"app name","r":"short reason"}
        """.trimIndent()
    }

    fun buildLoopRecoveryPrompt(
        goal: String,
        screenState: String,
        history: String,
        lastActionResult: String? = null
    ): String {
        val lastResultSection = lastActionResult?.let {
            "\nLAST_ACTION: $it"
        } ?: ""

        return """
GOAL: $goal
SCREEN:
$screenState$lastResultSection$history
⚠️ LOOP DETECTED - You repeated the same action. Try a DIFFERENT action or element.
ACTIONS: open(app), tap(i), tap_text(tt), tap_id(id), type(t), enter, toggle(k), wait_for(tt|id, ms), scroll_find(tt|id, d, n), swipe(d:u/d/l/r), long(i), back, home, url(u), search(q), notif, quick, screenshot, wait, done
OUTPUT (single JSON object): {"a":"tap","i":0,"t":"text","tt":"visible text","id":"resource id","k":"toggle keyword","d":"u","ms":5000,"n":6,"u":"https://example.com","q":"search query","app":"app name","r":"short reason"}
        """.trimIndent()
    }

    fun buildFailureRecoveryPrompt(
        goal: String,
        screenState: String,
        history: String,
        lastActionResult: String? = null
    ): String {
        val lastResultSection = lastActionResult?.let {
            "\nLAST_ACTION (FAILED): $it"
        } ?: ""

        return """
GOAL: $goal
SCREEN:
$screenState$lastResultSection$history
⚠️ LAST ACTION FAILED - Try a different approach. Maybe:
- The element moved or changed
- Need to scroll to find the element
- Need to wait for loading
- Try a different element
ACTIONS: open(app), tap(i), tap_text(tt), tap_id(id), type(t), enter, toggle(k), wait_for(tt|id, ms), scroll_find(tt|id, d, n), swipe(d:u/d/l/r), long(i), back, home, url(u), search(q), notif, quick, screenshot, wait, done
OUTPUT (single JSON object): {"a":"tap","i":0,"t":"text","tt":"visible text","id":"resource id","k":"toggle keyword","d":"u","ms":5000,"n":6,"u":"https://example.com","q":"search query","app":"app name","r":"short reason"}
        """.trimIndent()
    }

    fun buildPlanningPrompt(task: String): String {
        return """
TASK: $task
Create a step-by-step plan to accomplish this task on an Android device.
Be specific about what to tap, type, or swipe.
OUTPUT: {"steps":["step1","step2","step3"],"success_criteria":"how to know task is done"}
        """.trimIndent()
    }
}
