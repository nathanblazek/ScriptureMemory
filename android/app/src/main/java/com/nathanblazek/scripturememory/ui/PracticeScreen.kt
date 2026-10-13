package com.nathanblazek.scripturememory.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nathanblazek.scripturememory.model.Passage
import com.nathanblazek.scripturememory.practice.PracticeController
import com.nathanblazek.scripturememory.practice.PracticeMode
import com.nathanblazek.scripturememory.practice.RevealKind
import com.nathanblazek.scripturememory.practice.TokenKind
import com.nathanblazek.scripturememory.practice.WordToken
import com.nathanblazek.scripturememory.speech.AndroidSpeechEngine

private const val ESV_COPYRIGHT =
    "Scripture quotations are from the ESV® Bible (The Holy Bible, English Standard Version®), © 2001 by Crossway, " +
        "a publishing ministry of Good News Publishers. Used by permission. All rights reserved."

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun PracticeScreen(vm: AppViewModel, collectionId: String, passage: Passage, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val controller = remember(passage.id) {
        PracticeController(passage.text, vm.voiceProfile) { words, start, _, events ->
            AndroidSpeechEngine(context, words, start, events)
        }
    }
    var state by remember(controller) { mutableStateOf(controller.state) }
    DisposableEffect(controller) {
        controller.onChanged = { state = it }
        controller.onPracticeCompleted = { vm.recordPractice(collectionId, passage.id) }
        controller.onProfileChanged = { vm.save() }
        onDispose { controller.shutdown() }
    }

    // Keep the screen on while listening.
    val view = LocalView.current
    val micActive = state.listening || state.calibrating
    DisposableEffect(micActive) {
        view.keepScreenOn = micActive
        onDispose { view.keepScreenOn = false }
    }

    var pendingMicAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingMicAction?.invoke()
        else vm.message = "Microphone permission is needed to recite out loud."
        pendingMicAction = null
    }
    fun withMic(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingMicAction = action
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    var fontSize by rememberSaveable { mutableFloatStateOf(22f) }
    var showSizeSlider by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${passage.reference} (ESV)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { controller.shutdown(); vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to collection")
                    }
                },
                actions = {
                    IconButton(onClick = { showSizeSlider = !showSizeSlider }) { Icon(Icons.Default.TextFields, "Text size") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showSizeSlider) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("A", fontSize = 14.sp)
                        Slider(
                            value = fontSize,
                            onValueChange = { fontSize = it },
                            valueRange = 14f..40f,
                            steps = 12,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        Text("A", fontSize = 24.sp)
                    }
                }

                val modes = listOf(PracticeMode.Full to "Show all", PracticeMode.FirstLetter to "First letters", PracticeMode.Blur to "Blur")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = state.mode == mode,
                            onClick = { controller.setMode(mode) },
                            enabled = !state.calibrating,
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            icon = {},
                        ) { Text(label, maxLines = 1) }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.listening) {
                        Button(onClick = { controller.stopSpeech() }) {
                            Icon(Icons.Default.MicOff, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stop")
                        }
                    } else {
                        Button(
                            onClick = { withMic { controller.startSpeech() } },
                            enabled = state.mode != PracticeMode.Full && !state.calibrating,
                        ) {
                            Icon(Icons.Default.Mic, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Speak")
                        }
                    }
                    OutlinedButton(onClick = { controller.reset() }, enabled = !state.calibrating) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reset")
                    }
                    OutlinedButton(onClick = {
                        if (state.calibrating) controller.finishCalibration(save = true)
                        else withMic { controller.startCalibration() }
                    }) {
                        Icon(Icons.Default.RecordVoiceOver, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.calibrating) "Finish" else "Calibrate", maxLines = 1)
                    }
                }

                if (micActive) {
                    LinearProgressIndicator(
                        progress = { state.micLevel / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.stats, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                state.banner?.let { banner ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(12.dp).fillMaxWidth()) {
                            Text(banner.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(banner.message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            val scroll = rememberScrollState()
            val currentRequester = remember { BringIntoViewRequester() }
            LaunchedEffect(state.currentWord) {
                if (state.currentWord != null) {
                    withFrameNanos { } // let the marker's new position lay out first
                    currentRequester.bringIntoView()
                }
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                PassageText(
                    controller = controller,
                    state = state,
                    fontSize = fontSize,
                    currentRequester = currentRequester,
                )
                Spacer(Modifier.height(24.dp))
                Text(ESV_COPYRIGHT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun PassageText(
    controller: PracticeController,
    state: com.nathanblazek.scripturememory.practice.PracticeState,
    fontSize: Float,
    currentRequester: BringIntoViewRequester,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy((fontSize * 0.28f).dp),
        verticalArrangement = Arrangement.spacedBy((fontSize * 0.45f).dp),
    ) {
        controller.tokens.forEachIndexed { i, token ->
            when (token.kind) {
                TokenKind.ParagraphBreak -> Spacer(Modifier.fillMaxWidth().height((fontSize * 0.3f).dp))
                TokenKind.VerseNumber -> Text(
                    token.display,
                    fontSize = (fontSize * 0.55f).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp),
                )
                TokenKind.Word -> {
                    val w = controller.wordIndexOfToken[i]
                    val isCurrent = state.currentWord == w
                    Word(
                        token = token,
                        mode = state.mode,
                        reveal = state.reveal[w],
                        readMark = state.readMark[w],
                        isCurrent = isCurrent,
                        fontSize = fontSize,
                        onTap = { controller.wordTapped(w) },
                        modifier = if (isCurrent) Modifier.bringIntoViewRequester(currentRequester) else Modifier,
                    )
                }
            }
        }
    }
}

/** One word of the passage: the word, a first-letter hint, or a blurred version. */
@Composable
private fun Word(
    token: WordToken,
    mode: PracticeMode,
    reveal: RevealKind,
    readMark: Boolean,
    isCurrent: Boolean,
    fontSize: Float,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val concealed = mode != PracticeMode.Full && token.hasLetters && reveal == RevealKind.Hidden
    val marker = MaterialTheme.colorScheme.primary
    val color = when {
        reveal == RevealKind.Spoken -> WordColors.spoken
        reveal == RevealKind.Peeked -> MaterialTheme.colorScheme.primary
        reveal == RevealKind.Missed -> WordColors.missed
        readMark -> WordColors.spoken
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier
            .then(if (concealed) Modifier.clickable(onClick = onTap) else Modifier)
            .drawBehind {
                if (isCurrent) {
                    val h = 3.dp.toPx()
                    drawRoundRect(
                        marker,
                        topLeft = Offset(0f, size.height + 1.dp.toPx()),
                        size = Size(size.width, h),
                        cornerRadius = CornerRadius(h / 2),
                    )
                }
            },
    ) {
        when {
            !concealed -> Text(token.display, fontSize = fontSize.sp, color = color)
            mode == PracticeMode.FirstLetter ->
                Text(token.hint, fontSize = fontSize.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                Text(
                    token.display,
                    fontSize = fontSize.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.blur((fontSize * 0.3f).dp, BlurredEdgeTreatment.Unbounded),
                )
            // Blur needs Android 12; older versions get a shaded block the size of the word.
            else -> Text(
                token.display,
                fontSize = fontSize.sp,
                color = Color.Transparent,
                modifier = Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f), RoundedCornerShape(4.dp)),
            )
        }
    }
}
