package com.epaflix.fytorium

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

// A control that cannot act now keeps its place and still says why on tap, but looks off, as Add plant does (W5b-P27).
@Composable internal fun offButtonColors(off: Boolean) = if (off) ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    else ButtonDefaults.buttonColors()
@Composable internal fun offTextColors(off: Boolean) = if (off) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    else ButtonDefaults.textButtonColors()
@Composable internal fun offIconColors(off: Boolean) = if (off) IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    else IconButtonDefaults.iconButtonColors()
internal fun Modifier.offReason(reason: String?) = semantics { reason?.let { disabled(); stateDescription = it } }
