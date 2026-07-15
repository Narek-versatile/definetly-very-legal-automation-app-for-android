package com.legal.automation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.legal.automation.engine.AutomationRunState
import com.legal.automation.model.Automation
import com.legal.automation.shizuku.ShizukuManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface Screen {
    data object Home : Screen
    data class Edit(val automation: Automation) : Screen
    data object Logs : Screen
    data object Scans : Screen
    data object Sweep : Screen
    data object Setup : Screen
}

@Composable
fun AppRoot(vm: MainViewModel) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    when (val s = screen) {
        Screen.Home -> HomeScreen(
            vm = vm,
            onEdit = { screen = Screen.Edit(it) },
            onNew = {
                screen = Screen.Edit(Automation(name = "New automation"))
            },
            onLogs = { screen = Screen.Logs },
            onScans = { screen = Screen.Scans },
            onSweep = { screen = Screen.Sweep },
        )

        is Screen.Edit -> EditorScreen(
            vm = vm,
            initial = s.automation,
            onDone = { screen = Screen.Home },
        )

        Screen.Logs -> LogsScreen(vm = vm, onBack = { screen = Screen.Home })
        Screen.Scans -> ScansScreen(vm = vm, onBack = { screen = Screen.Home })
        Screen.Sweep -> SweepScreen(
            vm = vm,
            onBack = { screen = Screen.Home },
            onOpenSetup = { screen = Screen.Setup },
        )
        Screen.Setup -> SetupScreen(vm = vm, onBack = { screen = Screen.Sweep })
    }
}

// --- Home --------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    vm: MainViewModel,
    onEdit: (Automation) -> Unit,
    onNew: () -> Unit,
    onLogs: () -> Unit,
    onScans: () -> Unit,
    onSweep: () -> Unit,
) {
    val automations by vm.automations.collectAsState()
    val runState by AutomationRunState.state.collectAsState()
    val shizuku by vm.shizukuStatus.collectAsState()
    val useShizuku by vm.useShizuku.collectAsState()
    val username by vm.username.collectAsState()
    val realTaps by vm.realTaps.collectAsState()
    val accessibilityEnabled = rememberAccessibilityEnabled()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Legal Automation") },
                actions = {
                    IconButton(onClick = onSweep) {
                        Icon(Icons.Filled.Repeat, contentDescription = "Vote sweep")
                    }
                    IconButton(onClick = onScans) {
                        Icon(Icons.Filled.Search, contentDescription = "Screen scans")
                    }
                    IconButton(onClick = onLogs) {
                        Icon(Icons.Filled.Receipt, contentDescription = "Run logs")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = "New automation")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item {
                PermissionCard(
                    title = "Accessibility service",
                    ok = accessibilityEnabled,
                    okText = "Enabled",
                    badText = "Off — required to tap and read other apps",
                    buttonText = "Open settings",
                    onClick = { SystemAccess.openAccessibilitySettings(context) },
                )
            }

            item {
                ShizukuModeCard(
                    useShizuku = useShizuku,
                    status = shizuku,
                    onToggle = { vm.setUseShizuku(it) },
                    onGrant = { vm.requestShizuku() },
                    onRecheck = { vm.refreshShizuku() },
                )
            }

            item {
                UsernameCard(username = username, onChange = { vm.setUsername(it) })
            }

            item {
                ToggleCard(
                    title = "Use real taps",
                    subtitle = if (realTaps) {
                        "On — genuine touch (for buttons that ignore normal clicks)"
                    } else {
                        "Off — accessibility click first (bypasses ad overlays)"
                    },
                    checked = realTaps,
                    onToggle = { vm.setRealTaps(it) },
                )
            }

            if (runState.running) {
                item { RunningBanner(runState) }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Automations",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.reloadBuiltIns() }) {
                        Text("Reload built-in votes")
                    }
                }
            }

            if (automations.isEmpty()) {
                item { Text("No automations yet. Tap + to create one.") }
            }

            items(automations, key = { it.id }) { automation ->
                AutomationCard(
                    automation = automation,
                    onRun = { vm.run(automation) },
                    onEdit = { onEdit(automation) },
                    onDelete = { vm.delete(automation.id) },
                )
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun RunningBanner(state: AutomationRunState.State) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("Running “${state.automationName}”", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Step ${state.stepIndex + 1} / ${state.totalSteps}: ${state.lastMessage}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            val progress = if (state.totalSteps == 0) 0f
            else (state.stepIndex + 1f) / state.totalSteps
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AutomationCard(
    automation: Automation,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(automation.name, style = MaterialTheme.typography.titleMedium)
            if (automation.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    automation.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("${automation.steps.size} steps", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onRun) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("Run")
                }
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("Edit")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    ok: Boolean,
    okText: String,
    badText: String,
    buttonText: String,
    onClick: () -> Unit,
) {
    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                tint = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (ok) okText else badText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!ok) {
                TextButton(onClick = onClick) { Text(buttonText) }
            }
        }
    }
}

@Composable
private fun UsernameCard(username: String, onChange: (String) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Your username", style = MaterialTheme.typography.titleSmall)
            Text(
                "Used wherever a step contains {username} — e.g. every vote form.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = username,
                onValueChange = onChange,
                singleLine = true,
                label = { Text("Minecraft username") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ShizukuModeCard(
    useShizuku: Boolean,
    status: ShizukuManager.Status,
    onToggle: (Boolean) -> Unit,
    onGrant: () -> Unit,
    onRecheck: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Low-level input (Shizuku)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (useShizuku) {
                            "On when available — faster app launch & typing"
                        } else {
                            "Off — non-Shizuku mode (accessibility only)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = useShizuku, onCheckedChange = onToggle)
            }

            // Only nudge about Shizuku setup when the user actually wants it.
            if (useShizuku && status != ShizukuManager.Status.READY) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        when (status) {
                            ShizukuManager.Status.NEEDS_PERMISSION -> "Installed but not granted"
                            else -> "Not running — everything still works without it"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = if (status == ShizukuManager.Status.NEEDS_PERMISSION) onGrant else onRecheck,
                    ) {
                        Text(if (status == ShizukuManager.Status.NEEDS_PERMISSION) "Grant" else "Recheck")
                    }
                }
            }
            if (useShizuku && status == ShizukuManager.Status.READY) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Shizuku ready.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2E7D32),
                )
            }
        }
    }
}

// --- Logs --------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val logs by vm.logs.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Run logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            if (logs.isEmpty()) {
                item { Text("No runs yet.") }
            }
            items(logs, key = { it.id }) { log ->
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (log.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                contentDescription = null,
                                tint = if (log.success) Color(0xFF2E7D32)
                                else MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.size(8.dp))
                            Column {
                                Text(log.automationName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    formatTime(log.startedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        log.steps.forEach { step ->
                            Text(
                                "${if (step.success) "✓" else "✗"} ${step.description}" +
                                    if (!step.success) " — ${step.message}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (step.success) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                        if (log.failureScreenshotPath != null) {
                            Spacer(Modifier.height(6.dp))
                            AssistChip(
                                onClick = {},
                                label = { Text("Screenshot saved") },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

// --- Scans -------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScansScreen(vm: MainViewModel, onBack: () -> Unit) {
    val scans by vm.scans.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Screen scans") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            if (scans.isEmpty()) {
                item {
                    Text(
                        "No scans yet. With this app's accessibility service on, open any app, " +
                            "pull down the notification shade and tap “Scan screen”. The full " +
                            "text/ids show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(scans, key = { it.id }) { scan ->
                ScanCard(scan)
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun ScanCard(scan: com.legal.automation.data.Scan) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val fullText = remember(scan) { scan.items.joinToString("\n") }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { expanded = !expanded },
                ) {
                    Text(scan.appLabel, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${formatTime(scan.createdAt)} · ${scan.items.size} items · tap to " +
                            if (expanded) "collapse" else "expand",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(fullText)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("Copy")
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        fullText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

// --- Helpers -----------------------------------------------------------------

@Composable
private fun rememberAccessibilityEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SystemAccess.isAccessibilityEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = SystemAccess.isAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return enabled
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(millis))
