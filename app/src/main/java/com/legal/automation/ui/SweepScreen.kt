package com.legal.automation.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.legal.automation.data.SweepSchedule
import com.legal.automation.engine.SweepRunState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SweepScreen(vm: MainViewModel, onBack: () -> Unit) {
    val nicknamesSaved by vm.nicknames.collectAsState()
    val betweenVotesMs by vm.betweenVotesMs.collectAsState()
    val postAppPackage by vm.postCycleAppPackage.collectAsState()
    val postAppLabel by vm.postCycleAppLabel.collectAsState()
    val postTapX by vm.postCycleTapX.collectAsState()
    val postTapY by vm.postCycleTapY.collectAsState()
    val postWaitMs by vm.postCycleWaitMs.collectAsState()
    val schedule by vm.sweepSchedule.collectAsState()
    val sweepState by vm.sweepState.collectAsState()

    val nicknames = remember(nicknamesSaved) { nicknamesSaved.toMutableStateList() }
    var newNickname by remember { mutableStateOf("") }
    var betweenVotesSec by remember(betweenVotesMs) { mutableStateOf((betweenVotesMs / 1000).toString()) }
    var postWaitSec by remember(postWaitMs) { mutableStateOf((postWaitMs / 1000).toString()) }
    var pickingApp by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val recordTap = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val x = result.data?.getIntExtra(TapRecorderActivity.EXTRA_X, -1) ?: -1
            val y = result.data?.getIntExtra(TapRecorderActivity.EXTRA_Y, -1) ?: -1
            if (x >= 0 && y >= 0) vm.setPostCycleTap(x, y)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vote sweep") },
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

            item {
                Text(
                    "Runs votes 1→7 for each nickname below, in order, waiting between " +
                        "votes. After each nickname's chain, optionally launches an app, taps a " +
                        "recorded spot, waits, then comes back for the next nickname.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (sweepState.running) {
                item { SweepRunningBanner(sweepState) }
            }

            item { Text("Nicknames", style = MaterialTheme.typography.titleMedium) }

            itemsIndexed(nicknames) { index, nickname ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nicknames[index] = it; vm.setNicknames(nicknames.toList()) },
                        singleLine = true,
                        label = { Text("Nickname ${index + 1}") },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        nicknames.removeAt(index)
                        vm.setNicknames(nicknames.toList())
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove nickname")
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newNickname,
                        onValueChange = { newNickname = it },
                        singleLine = true,
                        label = { Text("New nickname") },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        if (newNickname.isNotBlank()) {
                            nicknames.add(newNickname.trim())
                            vm.setNicknames(nicknames.toList())
                            newNickname = ""
                        }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add nickname")
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = betweenVotesSec,
                    onValueChange = { betweenVotesSec = it; it.toLongOrNull()?.let { s -> vm.setBetweenVotesMs(s * 1000) } },
                    singleLine = true,
                    label = { Text("Wait between votes (seconds)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { Text("After each nickname", style = MaterialTheme.typography.titleMedium) }

            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (postAppPackage != null) "App: $postAppLabel" else "No app configured — skips straight to the next nickname",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pickingApp = true }) { Text("Pick app") }
                            if (postAppPackage != null) {
                                TextButton(onClick = { vm.setPostCycleApp(null, "") }) { Text("Clear") }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (postTapX != null && postTapY != null) "Tap location: ($postTapX, $postTapY)"
                            else "No tap location recorded",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { recordTap.launch(TapRecorderActivity.intent(context)) }) {
                                Text("Record tap")
                            }
                            if (postTapX != null) {
                                TextButton(onClick = { vm.setPostCycleTap(null, null) }) { Text("Clear") }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = postWaitSec,
                            onValueChange = { postWaitSec = it; it.toLongOrNull()?.let { s -> vm.setPostCycleWaitMs(s * 1000) } },
                            singleLine = true,
                            label = { Text("Wait after tap (seconds)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item { Text("Schedule (every 3 hours)", style = MaterialTheme.typography.titleMedium) }

            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        ScheduleOption(
                            label = "Off",
                            desc = "Only runs when you tap Start sweep.",
                            selected = schedule == SweepSchedule.OFF,
                            onSelect = { vm.setSweepSchedule(SweepSchedule.OFF) },
                        )
                        ScheduleOption(
                            label = "Remind me every 3h",
                            desc = "Posts a notification with a Start button — you tap to run.",
                            selected = schedule == SweepSchedule.REMINDER,
                            onSelect = { vm.setSweepSchedule(SweepSchedule.REMINDER) },
                        )
                        ScheduleOption(
                            label = "Run automatically every 3h",
                            desc = "Warns 5 and 1 minutes ahead, then runs the sweep on its own.",
                            selected = schedule == SweepSchedule.AUTOMATIC,
                            onSelect = { vm.setSweepSchedule(SweepSchedule.AUTOMATIC) },
                        )
                        if (schedule == SweepSchedule.AUTOMATIC) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Overnight / locked phone: the app wakes the screen and keeps it " +
                                    "on while voting. It can only get past the lock screen if your " +
                                    "screen lock is None or Swipe — a PIN/pattern/password can't be " +
                                    "bypassed, so for unattended runs set the lock to Swipe (or keep " +
                                    "the phone unlocked on a charger). Keep it plugged in.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { vm.startSweep() },
                    enabled = nicknames.isNotEmpty() && !sweepState.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.height(4.dp))
                    Text(if (sweepState.running) "Sweep running…" else "Start sweep")
                }
            }

            item { Spacer(Modifier.height(60.dp)) }
        }
    }

    if (pickingApp) {
        AppPickerDialog(
            onDismiss = { pickingApp = false },
            onPick = { packageName, label ->
                vm.setPostCycleApp(packageName, label)
                pickingApp = false
            },
        )
    }
}

@Composable
private fun ScheduleOption(
    label: String,
    desc: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.height(0.dp))
        Column(Modifier.padding(start = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SweepRunningBanner(state: SweepRunState.State) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Nickname ${state.nicknameIndex + 1} / ${state.totalNicknames}: “${state.currentNickname}”",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.phase == "Voting") "Vote ${state.voteIndex + 1} / ${state.totalVotes}" else state.phase,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            val progress = if (state.totalNicknames == 0) 0f
            else (state.nicknameIndex + 1f) / state.totalNicknames
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}
