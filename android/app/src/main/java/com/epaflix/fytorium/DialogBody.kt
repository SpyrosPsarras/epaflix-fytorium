package com.epaflix.fytorium

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

@Composable internal fun DialogTitle(text: String) {
    Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
}

@Composable internal fun DialogBody(keepOpeningHeight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val opened = remember { IntArray(1) { -1 } }
    Column(Modifier.layout { measurable, constraints ->
        val held = opened[0].takeIf { keepOpeningHeight && it >= 0 }?.coerceAtMost(constraints.maxHeight)
        val body = measurable.measure(held?.let { constraints.copy(minHeight = it, maxHeight = it) } ?: constraints)
        // Recorded when placed, because the dialog's intrinsic measurements also run this block.
        layout(body.width, body.height) { if (opened[0] < 0) opened[0] = body.height; body.place(0, 0) }
    }.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
