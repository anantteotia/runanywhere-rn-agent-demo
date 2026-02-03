package com.runanywhere.agent

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.runanywhere.agent.accessibility.AgentAccessibilityService
import com.runanywhere.agent.kernel.AgentKernel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    enum class Status {
        IDLE, RUNNING, DONE, ERROR
    }

    data class UiState(
        val goal: String = "",
        val status: Status = Status.IDLE,
        val logs: List<String> = emptyList(),
        val isServiceEnabled: Boolean = false,
        val selectedModelIndex: Int = 1, // Default to Qwen (best)
        val availableModels: List<ModelInfo> = AgentApplication.AVAILABLE_MODELS
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val agentKernel = AgentKernel(
        context = application,
        onLog = { log -> addLog(log) }
    )

    private var agentJob: Job? = null

    init {
        checkServiceStatus()
    }

    fun checkServiceStatus() {
        val isEnabled = AgentAccessibilityService.isEnabled(getApplication())
        _uiState.value = _uiState.value.copy(isServiceEnabled = isEnabled)
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(intent)
    }

    fun setGoal(goal: String) {
        _uiState.value = _uiState.value.copy(goal = goal)
    }

    fun setModel(index: Int) {
        if (index in AgentApplication.AVAILABLE_MODELS.indices) {
            _uiState.value = _uiState.value.copy(selectedModelIndex = index)
            agentKernel.setModel(AgentApplication.AVAILABLE_MODELS[index].id)
        }
    }

    fun startAgent() {
        val goal = _uiState.value.goal.trim()
        if (goal.isEmpty()) {
            addLog("Please enter a goal")
            return
        }

        if (!_uiState.value.isServiceEnabled) {
            addLog("Accessibility service not enabled")
            return
        }

        _uiState.value = _uiState.value.copy(
            status = Status.RUNNING,
            logs = listOf("Starting: $goal")
        )

        agentJob?.cancel()
        agentJob = viewModelScope.launch {
            agentKernel.run(goal).collect { event ->
                when (event) {
                    is AgentKernel.AgentEvent.Log -> addLog(event.message)
                    is AgentKernel.AgentEvent.Step -> addLog("${event.action}: ${event.result}")
                    is AgentKernel.AgentEvent.Done -> {
                        addLog(event.message)
                        _uiState.value = _uiState.value.copy(status = Status.DONE)
                    }
                    is AgentKernel.AgentEvent.Error -> {
                        addLog("ERROR: ${event.message}")
                        _uiState.value = _uiState.value.copy(status = Status.ERROR)
                    }
                }
            }
        }
    }

    fun stopAgent() {
        agentKernel.stop()
        agentJob?.cancel()
        agentJob = null
        addLog("Agent stopped")
        _uiState.value = _uiState.value.copy(status = Status.IDLE)
    }

    fun clearLogs() {
        _uiState.value = _uiState.value.copy(logs = emptyList())
    }

    private fun addLog(message: String) {
        val current = _uiState.value.logs.toMutableList()
        current.add(message)
        // Keep last 50 logs
        if (current.size > 50) {
            current.removeAt(0)
        }
        _uiState.value = _uiState.value.copy(logs = current)
    }
}
