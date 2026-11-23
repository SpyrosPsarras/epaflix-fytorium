package com.epaflix.fytorium.plants

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.epaflix.fytorium.*

@Composable internal fun SpeciesPicker(writable: Boolean, result: SpeciesSearch?, onSearch: (String) -> Unit,
    onSelect: (SpeciesCandidate, String, Boolean) -> Unit, imageApi: ApiClient? = null, onDirty: (() -> Boolean) -> Unit = {},
    autoFocus: Boolean = false, searching: Boolean = false, answeredQuery: String? = null, answerVersion: Int = 0,
    onIdentify: (() -> Unit)? = null, identifyEnabled: Boolean = true, currentSpecies: String? = null, searchEnabled: Boolean = writable,
    blockedReason: String? = null, onBlocked: (String) -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    SideEffect { onDirty { query.isNotBlank() } }
    var focusPending by rememberSaveable { mutableStateOf(autoFocus) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun canSearch(text: String) = searchEnabled && !searching && text.length in 1..200
    fun closeKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    fun submit() {
        val trimmed = query.trim()
        if (canSearch(trimmed)) {
            closeKeyboard()
            onSearch(trimmed)
        }
    }
    val list = rememberLazyListState()
    var shownVersion by rememberSaveable { mutableIntStateOf(answerVersion) }
    LaunchedEffect(answerVersion) {
        if (answerVersion != shownVersion) {
            list.scrollToItem(0)
            shownVersion = answerVersion
        }
    }
    var selectedKey by rememberSaveable(answerVersion) { mutableStateOf<Long?>(null) }
    val candidates = result?.candidates.orEmpty()
    val counts = remember(candidates) { candidates.groupingBy { it.headline to it.canonicalName }.eachCount() }
    fun scientific(candidate: SpeciesCandidate) = if (counts.getValue(candidate.headline to candidate.canonicalName) > 1)
        candidate.scientificName else candidate.canonicalName
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("query") {
            OutlinedTextField(query, { query = it }, label = { Text("Species name") }, supportingText = { Text("Common or scientific name") },
                singleLine = true, enabled = searchEnabled, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }), trailingIcon = {
                    IconButton(onClick = ::submit, enabled = canSearch(query.trim()), modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Search, contentDescription = "Search species")
                    }
                }, modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) submit()
                    true
                })
            LaunchedEffect(focusPending, searchEnabled) {
                if (focusPending && searchEnabled) {
                    focus.requestFocus()
                    keyboard?.show()
                    focusPending = false
                }
            }
        }
        if (result != null && candidates.isEmpty() && answeredQuery != null) item("no-match") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Heading("No species called \u201c$answeredQuery\u201d", MaterialTheme.typography.titleMedium)
                Text("Check the spelling, try the scientific name, or identify it from photos.", color = muted)
            }
        }
        onIdentify?.let { identify -> item("identify") { IdentifyFromPhotos(identifyEnabled) { closeKeyboard(); identify() } } }
        if (result != null) items(candidates, key = { it.key }) { candidate ->
            SpeciesSearchCard(candidate, scientific(candidate), imageApi, writable && !searching, blockedReason = blockedReason.takeIf { !writable }, onBlocked = onBlocked) {
                closeKeyboard()
                if (candidate.requiresConfirmation) selectedKey = candidate.key else onSelect(candidate, result.id, false)
            }
        }
        currentSpecies?.let { item("current") { Text(it, color = muted) } }
        item("sources") { Text("Names from GBIF (CC BY 4.0) and Wikidata (CC0).", style = MaterialTheme.typography.bodySmall, color = muted) }
    }
    if (result != null) candidates.firstOrNull { it.key == selectedKey }?.let { candidate ->
        ConfirmSearchSpecies(candidate, scientific(candidate), imageApi, writable, onBack = { selectedKey = null }) {
            selectedKey = null
            onSelect(candidate, result.id, true)
        }
    }
}

@Composable internal fun IdentifyFromPhotos(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Identify from photos")
    }
}

@Composable internal fun SpeciesSearchCard(candidate: SpeciesCandidate, scientific: String, api: ApiClient?, enabled: Boolean,
    photo: androidx.compose.ui.unit.Dp = 80.dp, lines: List<String> = emptyList(), blockedReason: String? = null, onBlocked: (String) -> Unit = {}, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // A card that can't be chosen keeps its text readable, dims its photo and, when there is a reason, says it on tap and to TalkBack.
    val explains = !enabled && blockedReason != null
    Card(onClick = { if (enabled) onClick() else blockedReason?.let(onBlocked) }, enabled = enabled || explains, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics {
        contentDescription = (listOf("${candidate.headline}, ${endSentence(scientific)}") + lines.map(::endSentence) + "Choose this species.").joinToString(" ")
        if (explains) stateDescription = blockedReason!!
    }) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val credit = SearchSpeciesPhoto(api, candidate.key, Modifier.size(photo).alpha(if (enabled) 1f else 0.38f))
            Column(Modifier.weight(1f)) {
                Heading(candidate.headline, MaterialTheme.typography.titleMedium)
                Text(scientific, color = muted)
                candidate.matchNote?.let { Text(it, color = muted, style = MaterialTheme.typography.bodySmall) }
                if (candidate.matchType == "FUZZY" && candidate.score == null) Text("Close match", color = muted, style = MaterialTheme.typography.bodySmall)
                lines.forEach { Text(it, color = muted, style = MaterialTheme.typography.bodySmall) }
                Text(credit?.label.orEmpty(), color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable internal fun ConfirmSearchSpecies(candidate: SpeciesCandidate, scientific: String, api: ApiClient?, writable: Boolean, onBack: () -> Unit,
    lines: List<String> = emptyList(), yourPhoto: (@Composable (Modifier) -> Unit)? = null, onConfirm: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false), onDismissRequest = onBack, title = { DialogTitle("Is this your plant?") }, text = {
        DialogBody(keepOpeningHeight = true) {
            var credit: PhotoCredit? = null
            if (yourPhoto == null) credit = SearchSpeciesPhoto(api, candidate.key, Modifier.fillMaxWidth().aspectRatio(4f / 3f), "Reference photo of ${candidate.canonicalName}")
            // The user's photo and the reference sit side by side at the same size, so they compare at a glance.
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    yourPhoto(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                    Text("Your photo", style = MaterialTheme.typography.labelMedium)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    credit = SearchSpeciesPhoto(api, candidate.key, Modifier.fillMaxWidth().aspectRatio(1f), "Reference photo of ${candidate.canonicalName}")
                    Text("Reference photo", style = MaterialTheme.typography.labelMedium)
                }
            }
            Heading(candidate.headline, MaterialTheme.typography.titleMedium)
            if (scientific != candidate.headline) Text(scientific)
            if (candidate.matchType == "FUZZY" && candidate.score == null) Text("Close match")
            lines.forEach { Text(it) }
            credit?.let { shown ->
                TextButton(onClick = { uriHandler.openUri(shown.source) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(shown.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }, dismissButton = { TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Yes, this one") } })
}

// The add form's species: reference photo, names and Change, which returns to the search or Identify it came from.
@Composable internal fun ChosenSpecies(api: ApiClient?, key: Long, headline: String, scientific: String, onChange: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchSpeciesPhoto(api, key, Modifier.size(72.dp))
            Column(Modifier.weight(1f)) {
                Heading(headline, MaterialTheme.typography.titleMedium)
                if (scientific != headline) Text(scientific, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onChange, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Change species" }) { Text("Change") }
        }
    }
}

@Composable private fun SearchSpeciesPhoto(api: ApiClient?, species: Long, modifier: Modifier, description: String? = null): PhotoCredit? = key(api, species) {
    val creditResult = referencePhotoCredit(api, species)
    val credit = creditResult?.getOrNull()
    val photo = authenticatedBitmap(api, "/api/species/$species/photo".takeIf { credit != null })
    val image = photo?.getOrNull()
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (image != null) Image(image.asImageBitmap(), description, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        else if (photo != null && (api == null || creditResult != null)) {
            Icon(Icons.Outlined.Eco, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("reference-photo-unavailable"))
        }
    }
    credit.takeIf { image != null }
}

private val PhotoCredit.label get() = "Photo: $creator, $license"
