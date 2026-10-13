package com.epaflix.fytorium.photos

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.epaflix.fytorium.*
import com.epaflix.fytorium.plants.SpeciesCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun PhotosRoute(api: ApiClient, care: CareState, connected: () -> Boolean, thumbnails: Thumbnails?,
    plantId: String? = null, plantName: String? = null, identifying: Boolean = false,
    onBack: () -> Unit, onPlant: (String) -> Unit = {}, onChanged: () -> Unit = {},
    onSelect: (SpeciesCandidate, String, Boolean) -> Unit = { _, _, _ -> }) {
    val currentCare by rememberUpdatedState(care)
    val currentConnected by rememberUpdatedState(connected)
    val member = care.member
    val repository = remember(api, member, plantId, identifying) { PhotoRepository(api,
        { member != null && member.role != Role.sitter && currentCare.member == member },
        { currentConnected() && !currentCare.updateRequired }, plantId) }
    val state by repository.state.collectAsState()
    var selected by rememberSaveable(member, plantId, identifying) { mutableStateOf<List<String>>(emptyList()) }
    var localError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val allowed = member != null && member.role != Role.sitter && !care.updateRequired
    val online = allowed && connected()
    val shown = state.copy(saving = state.saving || submitting, error = localError ?: state.error)
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
    if (member == null || member.role == Role.sitter) return
    val sources = rememberPhotoSources(identifying, selected, onPhotos = { photos ->
            if (identifying && selected.size + photos.size > 5) localError = "Choose at most five photos. Remove a photo before adding more."
            else {
                selected = if (identifying) selected + photos.map { it.toString() } else listOf(photos.first().toString())
                repository.clearResult(); localError = null
            }
        }, onError = { localError = it })
    val controls: @Composable () -> Unit = {
        PhotoSourceButtons(online && !shown.saving && !state.blocked && !state.updateRequired, sources.take, sources.choose)
    }
    if (identifying) IdentificationScreen(shown, online, selected.size, controls, onBack,
        onRefresh = { launch { refresh() } }, onIdentify = { launch(true) {
            withContext(Dispatchers.IO) { repository.identify(context, selected.map(Uri::parse)) }
        } }, onRemove = { index -> selected = selected.filterIndexed { position, _ -> position != index }; repository.clearResult() }, onSelect = onSelect)
    else PhotoScreen(shown, online, plantName, selected.size, controls, thumbnails, onBack,
        onRefresh = { launch { refresh() } }, onUpload = { note, health, date -> launch(true) {
            val uri = selected.firstOrNull()?.let(Uri::parse) ?: return@launch
            if (withContext(Dispatchers.IO) { repository.upload(context, uri, note, health, date) }) { selected = emptyList(); onChanged() }
        } }, onChange = { id, action -> launch(true) {
            if (withContext(Dispatchers.IO) { repository.change(id, action) }) onChanged()
        } }, onOriginal = { id -> launch { withContext(Dispatchers.IO) { repository.openOriginal(id) } } },
        onCloseOriginal = repository::clearOriginal, onPlant = onPlant)
}
