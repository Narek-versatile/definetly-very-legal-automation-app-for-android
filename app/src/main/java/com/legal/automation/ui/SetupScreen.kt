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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.legal.automation.shizuku.ShizukuManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val keepAwake by vm.keepAwakeWhileCharging.collectAsState()
    val shizuku by vm.shizukuStatus.collectAsState()

    // Recompute the OS-permission checks whenever we come back from a settings
    // screen (they can't be observed, so a resume tick forces a refresh).
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val accessibilityOk = remember(tick) { SystemAccess.isAccessibilityEnabled(context) }
    val batteryOk = remember(tick) { SystemAccess.isIgnoringBatteryOptimizations(context) }
    val exactAlarmOk = remember(tick) { SystemAccess.canScheduleExactAlarms(context) }
    val overlayOk = remember(tick) { SystemAccess.canDrawOverlays(context) }
    val notificationsOk = remember(tick) { NotificationManagerCompat.from(context).areNotificationsEnabled() }

    ScaffoldedColumn(onBack) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Text(
                "Set these up so the 3-hourly reminder / automatic sweep isn't delayed or " +
                    "blocked — especially overnight. On Samsung the battery ones matter most.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item { Text("Permissions", style = MaterialTheme.typography.titleMedium) }

        item {
            ChecklistRow(
                title = "Accessibility service",
                subtitle = "Required to tap and read the vote pages.",
                ok = accessibilityOk,
                onClick = { SystemAccess.openAccessibilitySettings(context) },
            )
        }
        item {
            ChecklistRow(
                title = "Ignore battery optimisation",
                subtitle = "Lets scheduled sweeps fire in Doze / overnight.",
                ok = batteryOk,
                onClick = { SystemAccess.openBatteryOptimization(context) },
            )
        }
        item {
            ChecklistRow(
                title = "Alarms & reminders (exact alarms)",
                subtitle = "Lets the 3-hour timer fire on time.",
                ok = exactAlarmOk,
                onClick = { SystemAccess.openExactAlarmSettings(context) },
            )
        }
        item {
            ChecklistRow(
                title = "Display over other apps",
                subtitle = "Helps the waker launch from the background on Samsung.",
                ok = overlayOk,
                onClick = { SystemAccess.openOverlaySettings(context) },
            )
        }
        item {
            ChecklistRow(
                title = "Notifications",
                subtitle = "For reminders, warnings and the failure alert.",
                ok = notificationsOk,
                onClick = { SystemAccess.openNotificationSettings(context) },
            )
        }

        item { Spacer(Modifier.height(8.dp)) }
        item { Text("Samsung battery", style = MaterialTheme.typography.titleMedium) }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Samsung is aggressive about sleeping apps. In the app's info page set " +
                            "Battery → Unrestricted, and in Settings → Battery → Background usage " +
                            "limits, make sure this app is NOT in “Sleeping / Deep sleeping apps” " +
                            "(add it to “Never sleeping apps”).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { SystemAccess.openAppDetails(context) }) {
                        Text("Open this app's info page")
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
        item { Text("Locked overnight (secure lock)", style = MaterialTheme.typography.titleMedium) }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Keep screen awake while charging (Shizuku)", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Unlock the phone once at night; while it's plugged in the screen " +
                                    "stays on, so a PIN/pattern lock never re-engages and the auto " +
                                    "sweeps run. The app never needs your passcode.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = keepAwake,
                            onCheckedChange = { vm.setKeepAwakeWhileCharging(it) },
                            enabled = shizuku == ShizukuManager.Status.READY || keepAwake,
                        )
                    }
                    if (shizuku != ShizukuManager.Status.READY) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Needs Shizuku running and granted (set it up on the home screen).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (keepAwake) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Heads-up: the screen stays on all night, so the phone is effectively " +
                                "unlocked while charging. Turn your brightness down to save the panel.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("About your passcode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "The app can't (and won't) type your device passcode to unlock a secure " +
                            "lock — no app is allowed to, and it wouldn't be reliable. If you don't " +
                            "want the stay-awake trick, set your screen lock to Swipe or None for the " +
                            "nights you run sweeps instead.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(40.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScaffoldedColumn(
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Overnight / auto setup") },
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
            content = content,
        )
    }
}

@Composable
private fun ChecklistRow(
    title: String,
    subtitle: String,
    ok: Boolean,
    onClick: () -> Unit,
) {
    Card {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
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
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onClick) { Text(if (ok) "Open" else "Fix") }
        }
    }
}
