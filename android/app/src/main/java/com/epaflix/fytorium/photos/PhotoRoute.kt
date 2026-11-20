package com.epaflix.fytorium.photos

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.epaflix.fytorium.*
import com.epaflix.fytorium.plants.SpeciesCandidate
import com.epaflix.fytorium.plants.SpeciesSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun PhotosRoute(api: ApiClient, care: CareState, connected: () -> Boolean, thumbnails: Thumbnails?,
    plantId: String? = null, plantName: String? = null, identifying: Boolean = false, formOnly: Boolean = false,
    onBack: () -> Unit, onPlant: (String) -> Unit = {}, onChanged: () -> Unit = {},
    onSelect: (SpeciesCandidate, String, Boolean) -> Unit = { _, _, _ -> }, onUpdateRequired: () -> Unit = {},
    onFormDirty: (() -> String?) -> Unit = {}, onReceipt: (String) -> Unit = {}, onAdd: (() -> Unit)? = null, onUploaded: () -> Unit = onBack,
    cached: (String) -> String? = { null }, keep: (String, String) -> Unit = { _, _ -> },
    grid: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()) {
    val currentCare by rememberUpdatedState(care)
    val currentConnected by rememberUpdatedState(connected)
    val member = care.member
    val repository = remember(api, member, plantId, identifying) { PhotoRepository(api,
        { member != null && member.role != Role.sitter && currentCare.member == member },
        { currentConnected() && !currentCare.updateRequired }, plantId, cached, keep) }
    val state by repository.state.collectAsState()
    LaunchedEffect(state.updateRequired) { if (state.updateRequired) onUpdateRequired() }
    var selected by key(member?.id, plantId, identifying) { rememberSaveable { mutableStateOf<List<String>>(emptyList()) } }
    var result by key(member?.id, plantId, identifying) { rememberSaveable { mutableStateOf<SpeciesSearch?>(null) } }
    var originalId by key(member?.id, plantId, identifying) { rememberSaveable { mutableStateOf<String?>(null) } }
    var localError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val allowed = member != null && member.role != Role.sitter && !care.updateRequired
    val online = allowed && connected()
    val shown = state.copy(saving = state.saving || submitting, error = localError ?: state.error, result = state.result ?: result)
    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            if (identifying) repository.quota() else {
                repository.refresh()
                if (plantId != null && !repository.state.value.blocked) repository.options()
            }
        }
    }
    fun launch(write: Boolean = false, action: suspend () -> Unit) {
        if (submitting || !allowed || (write && (!online || state.blocked || state.updateRequired))) return
        localError = null
        if (write) submitting = true
        scope.launch { try { action() } finally { if (write) submitting = false } }
    }
    LaunchedEffect(repository) { refresh() }
    var wasOnline by remember { mutableStateOf(online) }
    LaunchedEffect(online) { if (online && !wasOnline) refresh(); wasOnline = online }
    LaunchedEffect(repository, originalId, online) {
        originalId?.takeIf { online }?.let { id -> withContext(Dispatchers.IO) { repository.openOriginal(id) } }
    }
    if (member == null || member.role == Role.sitter) return
    val sources = rememberPhotoSources(identifying, selected, onPhotos = { photos ->
            if (identifying && selected.size + photos.size > 5) localError = "Choose at most five photos. Remove a photo before adding more."
            else {
                selected = if (identifying) selected + photos.map { it.toString() } else listOf(photos.first().toString())
                repository.clearResult(); result = null; localError = null
            }
        }, onError = { localError = it })
    if (identifying) IdentificationScreen(shown, online, selected, onBack, onRefresh = { launch { refresh() } }, onIdentify = { launch(true) {
            result = null
            if (withContext(Dispatchers.IO) { repository.identify(context, selected.map(Uri::parse)) }) result = repository.state.value.result
        } }, onRemove = { index -> selected = selected.filterIndexed { position, _ -> position != index }; repository.clearResult(); result = null },
        onSelect = onSelect, onTake = sources.take, onChoose = sources.choose, onExplain = onReceipt,
        onDiscard = { selected = emptyList(); result = null; repository.clearResult() }, care = care, api = api)
    else PhotoScreen(shown, online, plantName, selected.size, thumbnails, onBack,
        onRefresh = { launch { pulling = true; try { refresh() } finally { pulling = false } } }, onUpload = { note, health, date -> launch(true) {
            val uri = selected.firstOrNull()?.let(Uri::parse) ?: return@launch
            if (withContext(Dispatchers.IO) { repository.upload(context, uri, note, health, date) }) {
                selected = emptyList(); onChanged(); onReceipt("Added a photo of ${plantName ?: "this plant"}")
            }
        } }, onChange = { id, action -> launch(true) {
            val photo = state.photos.firstOrNull { it.id == id }
            if (withContext(Dispatchers.IO) { repository.change(id, action) }) {
                onChanged()
                photo?.let { onReceipt(if (action == "cover") "${it.plantName}'s cover photo changed" else "Deleted the ${dayMonthYear(it.takenOn)} photo of ${it.plantName}") }
            }
        } }, onOriginal = { originalId = it },
        onCloseOriginal = { originalId = null; repository.clearOriginal() }, onPlant = onPlant,
        onFormDirty = onFormDirty, onDiscard = { selected = emptyList(); localError = null; repository.clearError() },
        originalOpen = originalId != null, care = care, refreshing = pulling, onExplain = onReceipt, openPhotoId = originalId,
        formOnly = formOnly, onAdd = onAdd, onUploaded = onUploaded, grid = grid, selectedUri = selected.firstOrNull(), onTake = sources.take, onChoose = sources.choose,
        onRemovePhoto = { selected = emptyList(); localError = null; repository.clearError() })
}
