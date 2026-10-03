package com.nathanblazek.scripturememory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nathanblazek.scripturememory.data.Bible
import com.nathanblazek.scripturememory.model.Passage
import com.nathanblazek.scripturememory.model.VerseCollection
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(vm: AppViewModel, collection: VerseCollection, snackbar: SnackbarHostState) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Passage?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(collection.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename collection") }
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Default.DeleteOutline, "Delete collection") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    if (collection.passages.isEmpty()) "No passages yet. Add some verses below."
                    else passageSummary(collection.passages.size, collection.passages.count { it.mastered }),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { AddVersesCard(vm, collection.id) }
            items(collection.passages, key = { it.id }) { p ->
                PassageCard(
                    passage = p,
                    onPractice = { vm.open(Screen.Practice(collection.id, p.id)) },
                    onMastered = { vm.setMastered(collection.id, p.id, it) },
                    onRemove = { removing = p },
                )
            }
        }
    }

    if (renaming) {
        TextInputDialog(
            title = "Rename collection",
            label = "Name",
            initial = collection.name,
            confirmText = "Rename",
            onConfirm = { renaming = false; vm.renameCollection(collection.id, it) },
            onDismiss = { renaming = false },
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = "Delete collection?",
            message = "“${collection.name}” and its ${collection.passages.size} passage(s) will be removed.",
            confirmText = "Delete",
            onConfirm = { deleting = false; vm.deleteCollection(collection.id) },
            onDismiss = { deleting = false },
        )
    }
    removing?.let { p ->
        ConfirmDialog(
            title = "Remove passage?",
            message = "Remove ${p.reference} from “${collection.name}”?",
            confirmText = "Remove",
            onConfirm = { removing = null; vm.deletePassage(collection.id, p.id) },
            onDismiss = { removing = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddVersesCard(vm: AppViewModel, collectionId: String) {
    val scope = rememberCoroutineScope()
    var bookIndex by rememberSaveable { mutableStateOf(Bible.books.indexOfFirst { it.name == "John" }) }
    var bookMenu by remember { mutableStateOf(false) }
    var chapter by rememberSaveable { mutableStateOf("1") }
    var fromVerse by rememberSaveable { mutableStateOf("") }
    var toVerse by rememberSaveable { mutableStateOf("") }
    var reference by rememberSaveable { mutableStateOf("") }
    val book = Bible.books[bookIndex]

    fun addFromPicker() {
        val ch = chapter.toIntOrNull()
        if (ch == null || ch < 1 || ch > book.chapters) {
            vm.message = "${book.name} has chapters 1–${book.chapters}."
            return
        }
        var query = "${book.name} $ch"
        val from = fromVerse.toIntOrNull()
        if (from != null && from >= 1) {
            query += ":$from"
            val to = toVerse.toIntOrNull()
            if (to != null && to > from) query += "-$to"
        }
        scope.launch { vm.addPassage(collectionId, query) }
    }

    fun addFromReference() {
        val query = reference.trim()
        if (query.isEmpty()) return
        scope.launch { if (vm.addPassage(collectionId, query)) reference = "" }
    }

    val number = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add verses", style = MaterialTheme.typography.titleSmall)

            ExposedDropdownMenuBox(expanded = bookMenu, onExpandedChange = { bookMenu = it }) {
                OutlinedTextField(
                    value = book.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Book") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bookMenu) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = bookMenu, onDismissRequest = { bookMenu = false }) {
                    Bible.books.forEachIndexed { i, b ->
                        DropdownMenuItem(
                            text = { Text(b.name) },
                            onClick = {
                                bookIndex = i
                                bookMenu = false
                                if ((chapter.toIntOrNull() ?: 1) > b.chapters) chapter = "1"
                            },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = chapter, onValueChange = { chapter = it.filter(Char::isDigit) },
                    label = { Text("Chapter") }, singleLine = true, keyboardOptions = number,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = fromVerse, onValueChange = { fromVerse = it.filter(Char::isDigit) },
                    label = { Text("From") }, placeholder = { Text("all") }, singleLine = true, keyboardOptions = number,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = toVerse, onValueChange = { toVerse = it.filter(Char::isDigit) },
                    label = { Text("To") }, placeholder = { Text("—") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addFromPicker() }),
                    modifier = Modifier.weight(1f),
                )
            }
            Button(onClick = { addFromPicker() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Add ${book.name} ${chapter.ifEmpty { "?" }}" + if (fromVerse.isNotEmpty()) ":$fromVerse" + (if (toVerse.isNotEmpty()) "-$toVerse" else "") else "")
            }

            OutlinedTextField(
                value = reference,
                onValueChange = { reference = it },
                label = { Text("…or type any reference") },
                placeholder = { Text("Romans 8:28-39, Psalm 23, John 3:16-4:2") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addFromReference() }),
                modifier = Modifier.fillMaxWidth(),
            )
            FilledTonalButton(
                onClick = { addFromReference() },
                enabled = !vm.busy && reference.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add reference") }

            if (vm.busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Fetching from the ESV API…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PassageCard(passage: Passage, onPractice: () -> Unit, onMastered: (Boolean) -> Unit, onRemove: () -> Unit) {
    Card(onClick = onPractice, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(passage.reference, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                passage.preview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(passage.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onMastered(!passage.mastered) }.padding(end = 8.dp),
                ) {
                    Checkbox(checked = passage.mastered, onCheckedChange = onMastered)
                    Text("Mastered")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "Remove from collection") }
                Button(onClick = onPractice) { Text("Practice") }
            }
        }
    }
}
