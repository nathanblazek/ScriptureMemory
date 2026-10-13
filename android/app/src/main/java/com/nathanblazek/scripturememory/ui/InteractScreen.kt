package com.nathanblazek.scripturememory.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nathanblazek.scripturememory.model.VerseCollection
import com.nathanblazek.scripturememory.practice.CoachMode
import com.nathanblazek.scripturememory.practice.CoachPhase
import com.nathanblazek.scripturememory.practice.VoiceCommand
import com.nathanblazek.scripturememory.speech.CoachSettings
import com.nathanblazek.scripturememory.speech.ListeningLog
import com.nathanblazek.scripturememory.speech.OfflineModel
import com.nathanblazek.scripturememory.speech.VoiceCoach

/** Hands-free coaching through a whole collection, driven by voice ("verse yes", "verse speak", ...). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InteractScreen(vm: AppViewModel, collection: VerseCollection) {
    val context = LocalContext.current
    // A snapshot of the passages: mastering one mid-session mustn't restart the coach.
    val session = remember(collection.id) { VoiceCoach(context, collection.id, collection.passages) }
    var state by remember(session) { mutableStateOf(session.coach.state) }
    var showText by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    DisposableEffect(session) {
        session.coach.onChanged = { state = it }
        session.coach.onStopped = { vm.back() }
        onDispose { session.shutdown() }
    }

    // Hands-free means the screen mustn't lock mid-session.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) session.start()
        else {
            vm.message = "Microphone permission is needed for voice practice."
            vm.back()
        }
    }
    LaunchedEffect(session) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            session.start()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(collection.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to collection") }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Coach settings") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (state.mode) {
                        CoachMode.Learn -> "Learning · passage ${state.index + 1} of ${state.count}"
                        CoachMode.All -> "Whole collection · passage ${state.index + 1} of ${state.count}"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(state.reference, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                state.title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                if (state.mode == CoachMode.Learn && state.count > 0) {
                    Text(
                        "Perfect in a row: ${state.streak} of ${state.masteryGoal}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (state.phase) {
                            CoachPhase.Idle -> "Starting…"
                            CoachPhase.Speaking -> "Speaking"
                            CoachPhase.Reciting -> "Listening: recite the passage"
                            CoachPhase.Waiting -> "Say “verse yes” to try again"
                            CoachPhase.Finished -> "Done. Say “verse all” to go again"
                            CoachPhase.Failed -> "Stopped"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (state.phase == CoachPhase.Failed) Text(state.error)
                    else if (state.said.isNotEmpty()) Text(state.said)
                    if (state.heard.isNotEmpty() && state.phase != CoachPhase.Speaking) {
                        Text("Heard: ${state.heard}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { session.coach.command(VoiceCommand.Yes) }) { Text("Try again") }
                OutlinedButton(onClick = { session.coach.command(VoiceCommand.Correct) }) { Text("I said it right") }
                OutlinedButton(onClick = { session.coach.command(VoiceCommand.Review) }) { Text("Review") }
                OutlinedButton(onClick = { session.coach.command(VoiceCommand.Speak) }) { Text("Speak") }
                OutlinedButton(onClick = { session.coach.command(VoiceCommand.Hint) }) { Text("Hint") }
                OutlinedButton(onClick = { session.coach.command(VoiceCommand.Next) }) { Text("Next") }
                OutlinedButton(onClick = { session.coach.command(VoiceCommand.All) }) { Text("All") }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Say “verse” and then:", style = MaterialTheme.typography.titleSmall)
                for (c in VoiceCommand.entries) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(c.word, fontWeight = FontWeight.SemiBold)
                        Text(c.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (showSettings) CoachSettingsDialog(session) { showSettings = false }

            TextButton(onClick = { showText = !showText }) { Text(if (showText) "Hide the passage" else "Show the passage") }
            if (showText) Text(state.passageText, style = MaterialTheme.typography.bodyLarge)

            // So a session where it misheard can be sent off and turned into a test.
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Scripture Memory listening log")
                    .putExtra(Intent.EXTRA_TEXT, ListeningLog.read(context).ifEmpty { "Nothing heard yet." })
                context.startActivity(Intent.createChooser(send, "Share listening log"))
            }) { Text("Share listening log") }
        }
    }
}

/** Prompting and sound settings for the coach; changes apply right away. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CoachSettingsDialog(session: VoiceCoach, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { CoachSettings(context) }
    var promptAfter by remember { mutableStateOf(settings.promptAfterSeconds) }
    var blips by remember { mutableStateOf(settings.blips) }
    var chimes by remember { mutableStateOf(settings.chimes) }
    var volume by remember { mutableStateOf(settings.soundVolume.toFloat()) }
    var offline by remember { mutableStateOf(settings.offline) }
    var model by remember { mutableStateOf(OfflineModel.state(context)) }
    DisposableEffect(Unit) {
        val stop = OfflineModel.watch { model = it }
        onDispose { stop() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Coach settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Speech recognizer", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !offline,
                        onClick = {
                            offline = false
                            settings.offline = false
                        },
                        label = { Text("Google") },
                    )
                    FilterChip(
                        selected = offline,
                        onClick = {
                            offline = true
                            settings.offline = true
                        },
                        label = { Text("Offline, passage only") },
                    )
                }
                if (offline) {
                    Text(
                        "Listens only for the passage's words, on the phone, without gaps between phrases. " +
                            "Anything else is heard as a wrong word.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (val m = model) {
                        OfflineModel.State.Missing ->
                            Button(onClick = { OfflineModel.download(context) }) { Text("Download (${OfflineModel.SIZE_MB} MB)") }
                        is OfflineModel.State.Downloading -> Text("Downloading… ${m.percent}%")
                        is OfflineModel.State.Failed -> {
                            Text(m.message, color = MaterialTheme.colorScheme.error)
                            Button(onClick = { OfflineModel.download(context) }) { Text("Try again") }
                        }
                        OfflineModel.State.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Downloaded. Used from the next time it listens.", Modifier.weight(1f))
                            TextButton(onClick = { OfflineModel.delete(context) }) { Text("Delete") }
                        }
                    }
                    if (model != OfflineModel.State.Ready) {
                        Text("Until it's downloaded, Google's recognizer is used.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Text("When I pause, prompt the next words", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((seconds, label) in listOf(0 to "Never", 3 to "3 s", 4 to "4 s", 6 to "6 s", 8 to "8 s")) {
                        FilterChip(
                            selected = promptAfter == seconds,
                            onClick = {
                                promptAfter = seconds
                                settings.promptAfterSeconds = seconds
                            },
                            label = { Text(label) },
                        )
                    }
                }
                SettingSwitch("Blip when I miss a word", blips) {
                    blips = it
                    settings.blips = it
                }
                SettingSwitch("Chime when a verse is right", chimes) {
                    chimes = it
                    settings.chimes = it
                }
                Text("Blip and chime volume", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    onValueChangeFinished = {
                        settings.soundVolume = volume.toInt()
                        session.sample(volume.toInt())
                    },
                    valueRange = 5f..100f,
                )
            }
        },
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
