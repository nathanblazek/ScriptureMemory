package com.nathanblazek.scripturememory.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nathanblazek.scripturememory.car.CarSetupCheck

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(vm: AppViewModel, snackbar: SnackbarHostState, onApiKey: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var carCheck by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importData)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::exportData)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scripture Memory") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("ESV API key") }, onClick = { menuOpen = false; onApiKey() })
                        DropdownMenuItem(
                            text = { Text("Import data…") },
                            onClick = { menuOpen = false; importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        )
                        DropdownMenuItem(
                            text = { Text("Export data…") },
                            onClick = { menuOpen = false; exportLauncher.launch("scripture-memory.json") },
                        )
                        DropdownMenuItem(text = { Text("Android Auto check") }, onClick = { menuOpen = false; carCheck = CarSetupCheck.report(context) })
                        DropdownMenuItem(text = { Text("Version $version") }, onClick = {}, enabled = false)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New collection") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val collections = vm.data.collections
        if (collections.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook, null,
                        Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Create a collection to get started", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Collections group the passages you're memorizing, e.g. “Romans Road” or “Psalm 119”. " +
                            "Tap New collection, or import your data from the Windows app with ⋮ › Import data.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(collections, key = { it.id }) { c ->
                    Card(onClick = { vm.open(Screen.Collection(c.id)) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                passageSummary(c.passages.size, c.passages.count { it.mastered }),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    carCheck?.let { report ->
        AlertDialog(
            onDismissRequest = { carCheck = null },
            title = { Text("Android Auto check") },
            text = { Text(report) },
            confirmButton = { TextButton(onClick = { carCheck = null }) { Text("OK") } },
        )
    }

    if (creating) {
        TextInputDialog(
            title = "New collection",
            label = "Name",
            initial = "",
            confirmText = "Create",
            onConfirm = { creating = false; vm.addCollection(it) },
            onDismiss = { creating = false },
        )
    }
}

fun passageSummary(count: Int, mastered: Int): String =
    if (count == 0) "No passages yet"
    else "$count passage${if (count == 1) "" else "s"} · $mastered mastered"
