package com.runanywhere.agent.kernel

object SystemPrompts {

    val DECISION_SCHEMA = """
{
  "type":"object",
  "properties":{
    "a":{"type":"string","enum":["tap","type","enter","swipe","long","back","home","url","search","notif","quick","screenshot","wait","done"]},
    "i":{"type":"integer"},
    "t":{"type":"string"},
    "d":{"type":"string","enum":["u","d","l","r"]},
    "u":{"type":"string"},
    "q":{"type":"string"},
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
        lastActionResult: String? = null
    ): String {
        val lastResultSection = lastActionResult?.let {
            "\nLAST_ACTION: $it"
        } ?: ""

        return """
GOAL: $goal
SCREEN:
$screenState$lastResultSection$history
ACTIONS: tap(i), type(t), enter, swipe(d:u/d/l/r), long(i), back, home, url(u), search(q), notif, quick, screenshot, wait, done
RULES:
1. After typing, use enter to submit
2. If action failed, try different approach
3. Check HISTORY - don't repeat same action
4. For YouTube: tap search → type → enter → tap video
5. For WhatsApp: search contact → tap chat → type → tap send
6. Output done when goal achieved
OUTPUT: {"a":"ACTION","i":INDEX,"t":"text","d":"direction","r":"reason"}
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
ACTIONS: tap(i), type(t), enter, swipe(d:u/d/l/r), long(i), back, home, url(u), search(q), notif, quick, screenshot, wait, done
OUTPUT: {"a":"ACTION","i":INDEX,"t":"text","d":"direction","r":"reason"}
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
ACTIONS: tap(i), type(t), enter, swipe(d:u/d/l/r), long(i), back, home, url(u), search(q), notif, quick, screenshot, wait, done
OUTPUT: {"a":"ACTION","i":INDEX,"t":"text","d":"direction","r":"reason"}
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
