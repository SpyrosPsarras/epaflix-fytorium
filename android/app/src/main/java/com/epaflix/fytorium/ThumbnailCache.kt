package com.epaflix.fytorium

import java.io.File
import java.security.MessageDigest

class ThumbnailCache(private val directory: File, private val capBytes: Long = 50L * 1024 * 1024) {
    private var accessTime = System.currentTimeMillis()
    init {
        require(capBytes > 0); directory.mkdirs(); trim()
        accessTime = maxOf(accessTime, directory.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0)
    }
    private fun file(key: String): File = File(directory, MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) })
    @Synchronized fun get(key: String): ByteArray? {
        val file = file(key)
        if (!file.isFile) return null
        file.setLastModified(++accessTime)
        return file.readBytes()
    }
    @Synchronized fun put(key: String, bytes: ByteArray) {
        if (bytes.size > capBytes) return
        val file = file(key)
        val temp = File(directory, file.name + ".tmp")
        temp.writeBytes(bytes)
        check(temp.renameTo(file))
        file.setLastModified(++accessTime)
        trim()
    }
    @Synchronized fun sizeBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0
    private fun trim() {
        var total = sizeBytes()
        directory.listFiles()?.sortedBy { it.lastModified() }?.forEach {
            if (total > capBytes || it.name.endsWith(".tmp")) { val bytes = it.length(); if (it.delete()) total -= bytes }
        }
    }
    @Synchronized fun wipe() { directory.listFiles()?.forEach { check(it.delete()) } }
}

class Thumbnails(private val api: ApiClient, private val disk: ThumbnailCache, private val connected: () -> Boolean) {
    private var generation = 0
    @Synchronized fun wipe() { generation++; disk.wipe() }
    fun load(photoId: String): ByteArray? {
        require(photoId.matches(Regex("[A-Za-z0-9_-]+")))
        val epoch: Int
        synchronized(this) {
            disk.get(photoId)?.let { return it }
            epoch = generation
        }
        if (!connected()) return null
        return runCatching {
            api.request("/api/photos/$photoId/thumb").use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.byteStream()?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 1_048_576) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 1_048_577 - output.size()))
                        if (count == -1) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: return null
                if (bytes.size > 1_048_576) return null
                synchronized(this) { if (epoch != generation) return null; disk.put(photoId, bytes) }
                bytes
            }
        }.getOrNull()
    }
}
