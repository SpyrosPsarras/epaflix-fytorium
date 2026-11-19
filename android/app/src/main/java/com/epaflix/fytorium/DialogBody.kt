package com.epaflix.fytorium

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

@Composable internal fun DialogTitle(text: String) {
    Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
}

@Composable internal fun DialogBody(keepOpeningHeight: Boolean = false, reserveOpening: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val opened = remember { IntArray(1) { -1 } }
    val scroll = rememberScrollState()
    val edge = MaterialTheme.colorScheme.outline
    Box {
        Layout(content = {
            Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            if (reserveOpening != null) Box { reserveOpening() }
        }, modifier = Modifier.padding(vertical = 2.dp)) { children, constraints ->
            val held = opened[0].takeIf { keepOpeningHeight && it >= 0 }?.let(constraints::constrainHeight)
            val body = children[0].measure(constraints.copy(minHeight = 0, maxHeight = held ?: constraints.maxHeight))
            val reserve = if (held == null) children.getOrNull(1)?.measure(constraints.copy(minHeight = 0)) else null
            val height = held ?: constraints.constrainHeight(body.height + (reserve?.let { it.height + 8.dp.roundToPx() } ?: 0))
            // Intrinsic measurements also run this block, so record the opening height only on placement.
            layout(body.width, height) {
                if (opened[0] < 0) opened[0] = height
                body.placeRelative(0, 0)
            }
        }
        if (scroll.canScrollBackward) HorizontalDivider(Modifier.align(Alignment.TopCenter).fillMaxWidth().testTag("dialog-more-above"), color = edge)
        if (scroll.canScrollForward) HorizontalDivider(Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag("dialog-more-below"), color = edge)
    }
}
