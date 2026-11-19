package com.epaflix.fytorium.species

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import kotlinx.coroutines.flow.first

@Composable internal fun CarePageColumn(modifier: Modifier, unmatched: Boolean, content: @Composable () -> Unit) {
    val page = rememberSaveable(unmatched, saver = CarePageScroll.Saver) { CarePageScroll() }
    LaunchedEffect(page) { page.restore() }
    Layout(content, modifier.verticalScroll(page.scroll)) { measurables, constraints ->
        val padding = 16.dp.roundToPx()
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0).offset(horizontal = -2 * padding)
        val children = measurables.map { it.measure(childConstraints) }
        val spacing = 12.dp.roundToPx()
        var y = padding
        val items = children.mapIndexed { index, child ->
            val key = checkNotNull(measurables[index].layoutId as? String) { "Care defaults row needs a layoutId" }
            val item = CarePageScroll.Item(key, y, child.height)
            y += child.height + spacing
            item
        }
        val height = y + padding - if (items.isEmpty()) 0 else spacing
        layout(constraints.constrainWidth((children.maxOfOrNull { it.width } ?: 0) + 2 * padding), constraints.constrainHeight(height)) {
            page.items = items
            children.forEachIndexed { index, child -> child.placeRelative(padding, items[index].top) }
        }
    }
}

private class CarePageScroll(private var pending: Place? = null) {
    data class Place(val key: String, val offset: Int, val index: Int)
    data class Item(val key: String, val top: Int, val height: Int)

    val scroll = ScrollState(0)
    var items by mutableStateOf(emptyList<Item>())

    suspend fun restore() {
        val place = pending ?: return
        val measured = snapshotFlow { items }.first { it.isNotEmpty() }
        val item = measured.firstOrNull { it.key == place.key } ?: measured[place.index.coerceAtMost(measured.lastIndex)]
        scroll.scrollTo((item.top + place.offset.coerceAtMost(item.height - 1)).coerceAtLeast(0))
        pending = null
    }

    private fun capture(): Place? {
        pending?.let { return it }
        val index = items.indexOfFirst { it.top + it.height > scroll.value }
        val item = items.getOrNull(index) ?: return null
        return Place(item.key, scroll.value - item.top, index)
    }

    companion object {
        val Saver = listSaver<CarePageScroll, Any>(save = { page ->
            page.capture()?.let { listOf(it.key, it.offset, it.index) }.orEmpty()
        }, restore = { CarePageScroll(Place(it[0] as String, it[1] as Int, it[2] as Int)) })
    }
}
