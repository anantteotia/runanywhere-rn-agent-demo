package com.runanywhere.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.runanywhere.agent.AgentViewModel
import com.runanywhere.agent.ui.components.ModelSelector
import com.runanywhere.agent.ui.components.StatusBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(viewModel: AgentViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Recheck service status whenever the app resumes (e.g., returning from Settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkServiceStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RunAnywhere Agent") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Service Status
            if (!uiState.isServiceEnabled) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Accessibility service not enabled",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Button(onClick = { viewModel.openAccessibilitySettings() }) {
                            Text("Enable")
                        }
                    }
                }
            }

            // Model Selector
            ModelSelector(
                models = uiState.availableModels.map { it.name },
                selectedIndex = uiState.selectedModelIndex,
                onSelect = viewModel::setModel,
                enabled = uiState.status != AgentViewModel.Status.RUNNING
            )

            // Goal Input
            OutlinedTextField(
                value = uiState.goal,
                onValueChange = viewModel::setGoal,
                label = { Text("Enter your goal") },
                placeholder = { Text("e.g., 'Play lofi music on YouTube'") },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.status != AgentViewModel.Status.RUNNING,
                minLines = 2,
                maxLines = 4
            )

            // Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = viewModel::startAgent,
                    modifier = Modifier.weight(1f),
                    enabled = uiState.status != AgentViewModel.Status.RUNNING &&
                            uiState.isServiceEnabled &&
                            uiState.goal.isNotBlank()
                ) {
                    Text("Start Agent")
                }

                if (uiState.status == AgentViewModel.Status.RUNNING) {
                    Button(
                        onClick = viewModel::stopAgent,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Stop")
                    }
                }
            }

            // Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(status = uiState.status)

                if (uiState.logs.isNotEmpty()) {
                    TextButton(onClick = viewModel::clearLogs) {
                        Text("Clear Logs")
                    }
                }
            }

            // Log Output
            LogPanel(
                logs = uiState.logs,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
fun LogPanel(
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Agent logs will appear here",
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs) { log ->
                    val color = when {
                        log.startsWith("ERROR") -> Color(0xFFFF6B6B)
                        log.startsWith("Step") -> Color(0xFF69DB7C)
                        log.contains("Downloading") -> Color(0xFF74C0FC)
                        log.contains("done", ignoreCase = true) -> Color(0xFF69DB7C)
                        else -> Color(0xFFADB5BD)
                    }
                    Text(
                        text = log,
                        color = color,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
