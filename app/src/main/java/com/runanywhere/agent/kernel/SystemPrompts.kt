package com.runanywhere.agent.kernel

object SystemPrompts {

    val SYSTEM_PROMPT = """
You are an Android Driver Agent. Your job is to achieve the user's goal by navigating the UI.

You will receive:
1. The User's GOAL.
2. A list of interactive UI elements with their index numbers and capabilities.
3. Your PREVIOUS_ACTIONS so you don't repeat yourself.

You must output ONLY a valid JSON object with your next action.

Available Actions:
- {"action": "tap", "index": 3, "reason": "Tapping the Settings button"}
- {"action": "type", "text": "Hello World", "reason": "Typing a message"}
- {"action": "enter", "reason": "Press Enter to submit or search"}
- {"action": "swipe", "direction": "up", "reason": "Scrolling down to find more items"}
- {"action": "back", "reason": "Going back to previous screen"}
- {"action": "home", "reason": "Going to home screen"}
- {"action": "long", "index": 2, "reason": "Long pressing an element"}
- {"action": "wait", "reason": "Waiting for screen to load"}
- {"action": "done", "reason": "Task is complete"}

IMPORTANT RULES:
- Use "tap" with the element "index" number to tap a UI element.
- If an element shows [edit], use "type" action to enter text into it.
- After tapping on a text field, your NEXT action should be "type" to enter text.
- After typing a search query or URL, use "enter" to submit it.
- Do NOT type the same text again if you already typed it. Check PREVIOUS_ACTIONS.
- Do NOT tap the same element repeatedly. If you already tapped it, try a different action.
- If the screen shows your typed text, do NOT type again - use "enter" or tap a result.
- If you need to find an app not on screen, use "home" first, then "swipe" direction "up" to open app drawer.
- Use "swipe" with direction "up" or "down" to scroll through lists.
- Direction values: "up", "down", "left", "right".
- When the goal is achieved, output {"action": "done", "reason": "explanation"}.
- ALWAYS include a "reason" field explaining your decision.
- TIMER NUMPAD: The Android Clock timer numpad fills digits from RIGHT to LEFT (seconds, then minutes, then hours). To set 2 minutes, tap digits 2, 0, 0 (which displays as 02m 00s). To set 1 hour 30 minutes, tap 1, 3, 0, 0, 0. Just tapping "2" alone sets only 2 SECONDS, not 2 minutes.

Example - Tapping element 5:
{"action": "tap", "index": 5, "reason": "Tapping the Timer tab"}

Example - Typing in an edit field:
{"action": "type", "text": "2", "reason": "Entering the number of minutes"}

Example - Submitting after typing:
{"action": "enter", "reason": "Submitting the search query"}

Example - Scrolling to find more items:
{"action": "swipe", "direction": "up", "reason": "Scrolling down to see more options"}
    """.trimIndent()

    val DECISION_SCHEMA = """
{
  "type":"object",
  "properties":{
    "action":{"type":"string","enum":["tap","type","enter","swipe","long","back","home","wait","done"]},
    "index":{"type":"integer"},
    "text":{"type":"string"},
    "direction":{"type":"string","enum":["up","down","left","right"]},
    "reason":{"type":"string"}
  },
  "required":["action","reason"]
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
            "\nLAST_RESULT: $it"
        } ?: ""

        return """
GOAL: $goal

SCREEN_ELEMENTS:
$screenState
$lastResultSection$history

Output ONLY a JSON object with your next action.
        """.trimIndent()
    }

    fun buildLoopRecoveryPrompt(
        goal: String,
        screenState: String,
        history: String,
        lastActionResult: String? = null
    ): String {
        val lastResultSection = lastActionResult?.let {
            "\nLAST_RESULT: $it"
        } ?: ""

        return """
GOAL: $goal

SCREEN_ELEMENTS:
$screenState
$lastResultSection$history

WARNING: You are repeating the same action. You MUST try a DIFFERENT action or element this time.

Output ONLY a JSON object with your next action.
        """.trimIndent()
    }

    fun buildFailureRecoveryPrompt(
        goal: String,
        screenState: String,
        history: String,
        lastActionResult: String? = null
    ): String {
        val lastResultSection = lastActionResult?.let {
            "\nLAST_RESULT (FAILED): $it"
        } ?: ""

        return """
GOAL: $goal

SCREEN_ELEMENTS:
$screenState
$lastResultSection$history

WARNING: Your last action FAILED. Try a different approach:
- The element may have moved - check the current SCREEN_ELEMENTS
- You may need to scroll to find the element
- Try a different element or action

Output ONLY a JSON object with your next action.
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

    val TOOL_AWARE_ADDENDUM = """

TOOLS:
In addition to UI actions, you have access to external tools.
- If you need factual information (time, weather, calculations, device info), USE A TOOL instead of navigating the UI.
- To call a tool, output: <tool_call>{"tool":"tool_name","arguments":{"param":"value"}}</tool_call>
- After a tool returns results, decide your next step: another tool call, a UI action, or "done".
- IMPORTANT: Only call ONE tool at a time. Wait for the result before proceeding.
    """.trimIndent()

    fun buildPromptWithToolResults(
        goal: String,
        screenState: String,
        history: String,
        toolResults: String,
        lastActionResult: String? = null
    ): String {
        val lastResultSection = lastActionResult?.let {
            "\nLAST_RESULT: $it"
        } ?: ""

        return """
GOAL: $goal

SCREEN_ELEMENTS:
$screenState
$lastResultSection$history
$toolResults

Output ONLY a JSON object with your next action OR a <tool_call> if you need information.
        """.trimIndent()
    }
}
