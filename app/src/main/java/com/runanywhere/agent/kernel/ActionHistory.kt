package com.runanywhere.agent.kernel

data class ActionRecord(
    val step: Int,
    val action: String,
    val target: String?,
    val result: String?,
    val success: Boolean
)

class ActionHistory {
    private val history = mutableListOf<ActionRecord>()
    private var stepCounter = 0

    fun record(action: String, target: String? = null, result: String? = null, success: Boolean = true) {
        stepCounter++
        history.add(ActionRecord(stepCounter, action, target, result, success))
    }

    fun formatForPrompt(): String {
        if (history.isEmpty()) return ""

        val lines = history.takeLast(5).map { record ->
            val targetStr = record.target?.let { " '$it'" } ?: ""
            val resultStr = record.result?.let { " → $it" } ?: ""
            val status = if (record.success) "✓" else "✗"
            "$status Step ${record.step}: ${record.action}$targetStr$resultStr"
        }

        return "\nHISTORY:\n${lines.joinToString("\n")}"
    }

    fun getLastActionResult(): String? {
        return history.lastOrNull()?.let { record ->
            val targetStr = record.target?.let { "'$it'" } ?: ""
            val resultStr = record.result ?: ""
            "${record.action} $targetStr → $resultStr"
        }
    }

    fun isRepetitive(action: String, target: String?): Boolean {
        if (history.isEmpty()) return false

        // Detect simple repeats and short oscillations to avoid getting stuck.
        val last = history.takeLast(6)
        if (last.size < 3) return false

        // AAA (same action+target 3 times)
        val last3 = last.takeLast(3)
        val same3 = last3.all { it.action == action && it.target == target }
        if (same3) return true

        // ABAB (two-step oscillation)
        if (last.size >= 4) {
            val last4 = last.takeLast(4)
            val a = last4[0]
            val b = last4[1]
            val isOscillation =
                last4[2].action == a.action && last4[2].target == a.target &&
                    last4[3].action == b.action && last4[3].target == b.target
            if (isOscillation) return true
        }

        return false
    }

    fun getLastAction(): ActionRecord? = history.lastOrNull()

    fun hadRecentFailure(): Boolean {
        return history.takeLast(2).any { !it.success }
    }

    fun clear() {
        history.clear()
        stepCounter = 0
    }

    fun size(): Int = history.size
}
