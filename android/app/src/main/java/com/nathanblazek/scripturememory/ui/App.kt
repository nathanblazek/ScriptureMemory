package com.nathanblazek.scripturememory.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    var showKeyDialog by remember { mutableStateOf(false) }

    LaunchedEffect(vm.message) {
        val msg = vm.message ?: return@LaunchedEffect
        vm.message = null
        snackbar.showSnackbar(msg)
    }

    BackHandler(enabled = vm.screen != Screen.Collections) { vm.back() }

    when (val screen = vm.screen) {
        Screen.Collections -> CollectionsScreen(vm, snackbar, onApiKey = { showKeyDialog = true })
        is Screen.Collection -> {
            val collection = vm.collection(screen.collectionId)
            if (collection == null) LaunchedEffect(screen) { vm.open(Screen.Collections) }
            else CollectionScreen(vm, collection, snackbar)
        }
        is Screen.Practice -> {
            val passage = vm.collection(screen.collectionId)?.passages?.firstOrNull { it.id == screen.passageId }
            if (passage == null) LaunchedEffect(screen) { vm.open(Screen.Collection(screen.collectionId)) }
            else PracticeScreen(vm, screen.collectionId, passage, snackbar)
        }
    }

    if (showKeyDialog || vm.askForApiKey) {
        ApiKeyDialog(
            initial = vm.apiKey,
            onSave = {
                vm.setApiKey(it)
                showKeyDialog = false
                vm.askForApiKey = false
            },
            onDismiss = {
                showKeyDialog = false
                vm.askForApiKey = false
            },
        )
    }
}

@Composable
fun ApiKeyDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(initial) }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ESV API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Scripture text comes from the ESV API, which needs a free API key. Sign in at api.esv.org, " +
                        "create an application, and paste its key here. The key is stored encrypted on this device."
                )
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.esv.org/account/create-application/")))
                }) { Text("Get an ESV API key") }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API key") },
                    placeholder = { Text("Paste your ESV API token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
