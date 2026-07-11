package com.legal.automation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.legal.automation.model.Automation
import com.legal.automation.model.ScrollDirection
import com.legal.automation.model.Step
import com.legal.automation.model.UrlTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    vm: MainViewModel,
    initial: Automation,
    onDone: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    val steps = remember { initial.steps.toMutableStateList() }

    var showAddDialog by remember { mutableStateOf(false) }
    var showJson by remember { mutableStateOf(false) }

    fun current() = initial.copy(name = name, description = description, steps = steps.toList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit automation") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showJson = true }) {
                        Icon(Icons.Filled.Code, contentDescription = "Edit JSON")
                    }
                    IconButton(onClick = {
                        vm.save(current())
                        vm.run(current())
                    }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Save & run")
                    }
                    IconButton(onClick = {
                        vm.save(current())
                        onDone()
                    }) {
                        Icon(Icons.Filled.Save, contentDescription = "Save")
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text("Steps", style = MaterialTheme.typography.titleMedium)
            }

            itemsIndexed(steps) { index, step ->
                StepRow(
                    index = index,
                    step = step,
                    canMoveUp = index > 0,
                    canMoveDown = index < steps.size - 1,
                    onUp = {
                        if (index > 0) {
                            val s = steps.removeAt(index); steps.add(index - 1, s)
                        }
                    },
                    onDown = {
                        if (index < steps.size - 1) {
                            val s = steps.removeAt(index); steps.add(index + 1, s)
                        }
                    },
                    onDelete = { steps.removeAt(index) },
                )
            }

            item {
                OutlinedButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.height(4.dp))
                    Text("Add step")
                }
            }
            item { Spacer(Modifier.height(60.dp)) }
        }
    }

    if (showAddDialog) {
        AddStepDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { steps.add(it); showAddDialog = false },
        )
    }

    if (showJson) {
        JsonEditorDialog(
            initialJson = vm.toJson(current()),
            onDismiss = { showJson = false },
            onApply = { json ->
                val parsed = vm.parse(json)
                if (parsed != null) {
                    name = parsed.name
                    description = parsed.description
                    steps.clear()
                    steps.addAll(parsed.steps)
                    showJson = false
                }
            },
        )
    }
}

@Composable
private fun StepRow(
    index: Int,
    step: Step,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${index + 1}.", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(0.dp))
            Text(
                "  ${step.describe()}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onUp, enabled = canMoveUp) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
            }
            IconButton(onClick = onDown, enabled = canMoveDown) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete step")
            }
        }
    }
}

private enum class StepType(val label: String) {
    OPEN_URL("Open URL in app"),
    WEB_SEARCH("Google search"),
    LAUNCH_APP("Launch app"),
    TAP_TEXT("Tap text"),
    TAP_ID("Tap id"),
    INPUT_TEXT("Type text"),
    MANUAL_STEP("Manual step (captcha)"),
    SCROLL("Scroll"),
    WAIT_FOR("Wait for text"),
    VERIFY("Verify text"),
    SLEEP("Wait (ms)"),
    TAP_XY("Tap x,y"),
}

@Composable
private fun AddStepDialog(
    onDismiss: () -> Unit,
    onAdd: (Step) -> Unit,
) {
    var type by remember { mutableStateOf(StepType.OPEN_URL) }
    var typeMenu by remember { mutableStateOf(false) }
    // Generic fields reused across types.
    var f1 by remember { mutableStateOf("") }
    var f2 by remember { mutableStateOf("") }
    var scroll by remember { mutableStateOf(ScrollDirection.DOWN) }
    var scrollMenu by remember { mutableStateOf(false) }
    var expectPresent by remember { mutableStateOf(true) }
    var urlTarget by remember { mutableStateOf(UrlTarget.GOOGLE_APP) }
    var urlTargetMenu by remember { mutableStateOf(false) }
    var pkg by remember { mutableStateOf("") }
    // Which field the app picker fills when a pick is made.
    var pickerFor by remember { mutableStateOf<StepType?>(null) }

    fun build(): Step? = when (type) {
        StepType.OPEN_URL -> f1.ifBlank { null }?.let {
            if (urlTarget == UrlTarget.SPECIFIC_APP && pkg.isBlank()) return@let null
            Step.OpenUrl(
                url = it.trim(),
                target = urlTarget,
                packageName = if (urlTarget == UrlTarget.SPECIFIC_APP) pkg.trim() else null,
            )
        }
        StepType.WEB_SEARCH -> f1.ifBlank { null }?.let { Step.WebSearch(query = it) }
        StepType.MANUAL_STEP -> Step.ManualStep(
            message = f1.ifBlank { "Do the manual step (e.g. solve the captcha), then wait…" },
            timeoutMs = f2.toLongOrNull() ?: 20000,
        )
        StepType.LAUNCH_APP -> f1.ifBlank { null }?.let {
            Step.LaunchApp(packageName = it.trim(), appLabel = f2.ifBlank { it.trim() })
        }
        StepType.TAP_TEXT -> f1.ifBlank { null }?.let { Step.TapText(text = it) }
        StepType.TAP_ID -> f1.ifBlank { null }?.let { Step.TapId(viewId = it.trim()) }
        StepType.INPUT_TEXT -> f1.ifBlank { null }?.let {
            Step.InputText(text = it, intoText = f2.ifBlank { null }?.trim())
        }
        StepType.SCROLL -> Step.Scroll(direction = scroll)
        StepType.WAIT_FOR -> f1.ifBlank { null }?.let {
            Step.WaitFor(text = it, timeoutMs = f2.toLongOrNull() ?: 8000)
        }
        StepType.VERIFY -> f1.ifBlank { null }?.let {
            Step.Verify(text = it, expectPresent = expectPresent)
        }
        StepType.SLEEP -> Step.Sleep(ms = f1.toLongOrNull() ?: 1000)
        StepType.TAP_XY -> {
            val x = f1.toIntOrNull(); val y = f2.toIntOrNull()
            if (x != null && y != null) Step.TapXy(x, y) else null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { build()?.let(onAdd) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add step") },
        text = {
            Column {
                OutlinedButton(onClick = { typeMenu = true }) {
                    Text(type.label)
                }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    StepType.entries.forEach { t ->
                        DropdownMenuItem(
                            text = { Text(t.label) },
                            onClick = {
                                type = t; typeMenu = false; f1 = ""; f2 = ""
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                when (type) {
                    StepType.OPEN_URL -> {
                        Field("URL (https://…)", f1) { f1 = it }
                        OutlinedButton(onClick = { urlTargetMenu = true }) {
                            Text("Open in: ${urlTargetLabel(urlTarget)}")
                        }
                        DropdownMenu(
                            expanded = urlTargetMenu,
                            onDismissRequest = { urlTargetMenu = false },
                        ) {
                            UrlTarget.entries.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(urlTargetLabel(t)) },
                                    onClick = { urlTarget = t; urlTargetMenu = false },
                                )
                            }
                        }
                        if (urlTarget == UrlTarget.SPECIFIC_APP) {
                            Spacer(Modifier.height(6.dp))
                            Field("Package name", pkg) { pkg = it }
                            OutlinedButton(onClick = { pickerFor = StepType.OPEN_URL }) {
                                Text("Pick app")
                            }
                        }
                    }
                    StepType.WEB_SEARCH -> Field("Search query", f1) { f1 = it }
                    StepType.MANUAL_STEP -> {
                        Field("Alert message (optional)", f1) { f1 = it }
                        Field("Wait ms (default 20000)", f2) { f2 = it }
                    }
                    StepType.LAUNCH_APP -> {
                        Field("Package name (e.g. com.android.chrome)", f1) { f1 = it }
                        Field("Label (optional)", f2) { f2 = it }
                        OutlinedButton(onClick = { pickerFor = StepType.LAUNCH_APP }) {
                            Text("Pick app")
                        }
                    }
                    StepType.TAP_TEXT, StepType.WAIT_FOR, StepType.VERIFY ->
                        Field("Text", f1) { f1 = it }
                    StepType.TAP_ID ->
                        Field("Resource id (com.app:id/foo)", f1) { f1 = it }
                    StepType.INPUT_TEXT -> {
                        Field("Text to type (use {username})", f1) { f1 = it }
                        Field("Into field label (optional)", f2) { f2 = it }
                    }
                    StepType.SLEEP -> Field("Milliseconds", f1) { f1 = it }
                    StepType.TAP_XY -> {
                        Field("X", f1) { f1 = it }
                        Field("Y", f2) { f2 = it }
                    }
                    StepType.SCROLL -> {
                        OutlinedButton(onClick = { scrollMenu = true }) {
                            Text("Direction: ${scroll.name.lowercase()}")
                        }
                        DropdownMenu(
                            expanded = scrollMenu,
                            onDismissRequest = { scrollMenu = false },
                        ) {
                            ScrollDirection.entries.forEach { d ->
                                DropdownMenuItem(
                                    text = { Text(d.name.lowercase()) },
                                    onClick = { scroll = d; scrollMenu = false },
                                )
                            }
                        }
                    }
                }
                if (type == StepType.WAIT_FOR) {
                    Field("Timeout ms (optional)", f2) { f2 = it }
                }
                if (type == StepType.VERIFY) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Expect present")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { expectPresent = !expectPresent }) {
                            Text(if (expectPresent) "Yes" else "No")
                        }
                    }
                }
            }
        },
    )

    if (pickerFor != null) {
        AppPickerDialog(
            onDismiss = { pickerFor = null },
            onPick = { packageName, label ->
                when (pickerFor) {
                    StepType.LAUNCH_APP -> { f1 = packageName; f2 = label }
                    StepType.OPEN_URL -> { pkg = packageName }
                    else -> {}
                }
                pickerFor = null
            },
        )
    }
}

private fun urlTargetLabel(target: UrlTarget): String = when (target) {
    UrlTarget.GOOGLE_APP -> "Google app"
    UrlTarget.DEFAULT_BROWSER -> "Default browser"
    UrlTarget.CHOOSER -> "Choose app"
    UrlTarget.SPECIFIC_APP -> "Specific app"
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun JsonEditorDialog(
    initialJson: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialJson) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onApply(text) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Edit as JSON") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
            )
        },
    )
}
