package com.epaflix.fytorium.photos

import com.epaflix.fytorium.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class PhotoUpdate(val id: String, val plantId: String, val plantName: String, val note: String,
    val health: String?, val takenOn: String, val cover: Boolean)
data class PhotoState(val photos: List<PhotoUpdate> = emptyList(), val loading: Boolean = false,
    val saving: Boolean = false, val uploading: Boolean = false, val loaded: Boolean = false, val blocked: Boolean = false,
    val updateRequired: Boolean = false, val error: String? = null, val progress: Float? = null,
    val result: com.epaflix.fytorium.plants.SpeciesSearch? = null, val remaining: Int? = null,
    val today: String? = null, val healthChoices: List<String> = emptyList(), val original: ByteArray? = null, val uploadRevision: Int = 0)
private class PhotoFailure(val status: Int, val messageText: String) : Exception()

// One member and one gallery. Neither metadata nor original images are persisted here.
class PhotoRepository(private val api: ApiClient, private val allowed: () -> Boolean,
    private val connected: () -> Boolean, private val plantId: String? = null) {
    private val mutable = MutableStateFlow(PhotoState())
    val state = mutable.asStateFlow()
    private fun safe(id: String): String { require(id.matches(Regex("[A-Za-z0-9_-]+"))); return id }
    private val listPath get() = plantId?.let { "/api/plants/${safe(it)}/photos" } ?: "/api/journal"
    fun clearError() { mutable.value = mutable.value.copy(error = null) }
    private fun response(response: okhttp3.Response, fallback: String = "Could not change the photo update."): String = response.use {
        val text = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw PhotoFailure(it.code, runCatching { JSONObject(text).optString("message") }.getOrNull()
            ?.takeIf { message -> message.isNotBlank() } ?: fallback)
        text
    }
    private fun load() {
        val photos = JSONArray(response(api.request(listPath))).mapObjects {
            PhotoUpdate(it.getString("id"), it.getString("plant_id"), it.getString("plant_name"), it.getString("note"),
                it.nullable("health"), it.getString("taken_on"), it.getBoolean("is_cover"))
        }
        if (allowed()) mutable.value = mutable.value.copy(photos = photos, loaded = true, blocked = false)
    }
    @Synchronized fun refresh(): Boolean = run(false) { load() }
    @Synchronized fun options(): Boolean = run(false) {
        val meta = JSONObject(response(api.request("/api/meta")))
        val choices = com.epaflix.fytorium.plants.parsePlantOptions(response(api.request("/api/choices")))
        if (allowed()) mutable.value = mutable.value.copy(today = meta.getString("today"), healthChoices = choices.health, blocked = false)
    }
    @Synchronized fun quota(): Boolean = run(false) {
        val json = JSONObject(response(api.request("/api/identification")))
        if (allowed()) mutable.value = mutable.value.copy(remaining = if (json.isNull("remaining")) null else json.getInt("remaining"), blocked = false)
    }
    fun clearOriginal() { mutable.value = mutable.value.copy(original = null) }
    @Synchronized fun openOriginal(id: String): Boolean = run(false) {
        api.request("/api/photos/${safe(id)}/original").use { reply ->
            if (!reply.isSuccessful) response(reply)
            val bytes = reply.body?.byteStream()?.use { readPhotoBytes(it, 15 * 1024 * 1024) }
            require(bytes != null && bytes.size <= 15 * 1024 * 1024)
            if (allowed()) mutable.value = mutable.value.copy(original = bytes)
        }
    }
    fun clearResult() { mutable.value = mutable.value.copy(result = null) }
    @Synchronized fun identify(context: android.content.Context, uris: List<android.net.Uri>): Boolean = run(true) {
        if (uris.size !in 1..5) throw PhotoFailure(422, "Choose one to five images of the same plant.")
        mutable.value = mutable.value.copy(result = null, progress = null, uploading = true)
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            uris.forEachIndexed { index, uri -> addFormDataPart("images", "photo-$index.jpg", preparePhoto(context, uri).toRequestBody("image/jpeg".toMediaType())) }
        }.build()
        val text = response(api.request("/api/identification", method = "POST", requestBody = UploadBody(multipart) {
            if (allowed()) mutable.value = mutable.value.copy(progress = it)
        }), "Could not identify these photos. Try again.")
        val json = JSONObject(text)
        if (allowed()) mutable.value = mutable.value.copy(result = com.epaflix.fytorium.plants.parseSpeciesSearch(text),
            remaining = if (json.isNull("remaining")) null else json.getInt("remaining"))
    }
    @Synchronized fun upload(context: android.content.Context, uri: android.net.Uri, note: String, health: String, takenOn: String): Boolean {
        val key = plantId ?: return false
        val success = run(true) {
            mutable.value = mutable.value.copy(uploading = true)
            val image = preparePhoto(context, uri)
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("photo", "photo.jpg", image.toRequestBody("image/jpeg".toMediaType()))
                .addFormDataPart("note", note).addFormDataPart("health", health).addFormDataPart("taken_on", takenOn).build()
            response(api.request("/api/plants/${safe(key)}/photos", method = "POST", requestBody = UploadBody(multipart) {
                if (allowed()) mutable.value = mutable.value.copy(progress = it)
            }), "Could not upload the photo. Try again.")
            if (allowed()) mutable.value = mutable.value.copy(uploadRevision = state.value.uploadRevision + 1)
        }
        if (success) refresh()
        return success
    }
    @Synchronized fun change(id: String, action: String): Boolean {
        require(action in listOf("cover", "delete"))
        val success = run(true) { response(api.request("/api/photos/${safe(id)}" + if (action == "cover") "/cover" else "",
            method = if (action == "cover") "PUT" else "DELETE")) }
        if (success) refresh()
        return success
    }
    private fun run(write: Boolean, work: () -> Unit): Boolean {
        if (!allowed()) { mutable.value = PhotoState(); return false }
        if (write && (state.value.blocked || state.value.updateRequired || state.value.saving)) return false
        mutable.value = mutable.value.copy(loading = !write, saving = write, error = null, progress = if (write) null else state.value.progress)
        return try {
            if (!connected()) throw java.io.IOException()
            work(); allowed()
        } catch (error: Exception) {
            if (allowed()) mutable.value = mutable.value.copy(remaining = if (error is PhotoFailure && error.status == 429) 0 else state.value.remaining,
                error = if (error is PhotoFailure) error.messageText else if (error is PhotoInputException) error.message else if (error is IllegalArgumentException) "Choose a decodable still image." else "Could not reach Fytorium. Try again.",
                blocked = (error !is PhotoFailure && error !is IllegalArgumentException) || (error is PhotoFailure && (error.status >= 500 || error.status in listOf(401, 403, 426))),
                updateRequired = state.value.updateRequired || (error is PhotoFailure && error.status == 426))
            false
        } finally { mutable.value = if (allowed()) mutable.value.copy(loading = false, saving = false, uploading = false) else PhotoState() }
    }
}
