package com.epaflix.fytorium

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

// W5a-P30: setting writes run one at a time in tap order, and outlive the screen, so Back, a tab or rotation never drops a tap.
// A write for a member who has left is refused by its repository's own member and session checks.
internal object SettingWrites {
    private val channel = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init { CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { for (job in channel) runCatching { job() } } }
    fun enqueue(job: suspend () -> Unit) { channel.trySend(job) }
}
@Composable internal fun rememberWriteQueue(): (suspend () -> Unit) -> Unit = remember { SettingWrites::enqueue }

// What a control shows until its own latest write ends, so an earlier write's end never clears a newer tap.
internal class Pending<T> {
    private var next = 0
    var values by mutableStateOf(mapOf<String, Pair<T, Int>>())
        private set
    operator fun get(key: String): T? = values[key]?.first
    fun start(key: String, value: T): Int = (++next).also { values = values + (key to (value to it)) }
    fun end(key: String, ticket: Int) { if (values[key]?.second == ticket) values = values - key }
}
