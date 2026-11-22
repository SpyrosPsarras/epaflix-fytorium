package com.epaflix.fytorium

import androidx.compose.runtime.*
import kotlinx.coroutines.channels.Channel

// W5a-P30: a screen's writes run one at a time in the order they were tapped.
@Composable internal fun rememberWriteQueue(): (suspend () -> Unit) -> Unit {
    val channel = remember { Channel<suspend () -> Unit>(Channel.UNLIMITED) }
    LaunchedEffect(channel) { for (job in channel) job() }
    return remember(channel) { { job -> channel.trySend(job) } }
}

// What a control shows until its own latest write ends, so an earlier write's end never clears a newer tap.
internal class Pending<T> {
    private var next = 0
    var values by mutableStateOf(mapOf<String, Pair<T, Int>>())
        private set
    operator fun get(key: String): T? = values[key]?.first
    fun start(key: String, value: T): Int = (++next).also { values = values + (key to (value to it)) }
    fun end(key: String, ticket: Int) { if (values[key]?.second == ticket) values = values - key }
}
