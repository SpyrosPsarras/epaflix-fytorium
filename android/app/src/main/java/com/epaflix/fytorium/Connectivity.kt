package com.epaflix.fytorium

internal data class NetworkFailureState(val offline: Boolean, val serverUnavailable: Boolean)

// Transport is usable only after Android validates it; an API failure is not network loss.
internal fun classifyNetworkFailure(validated: Boolean, status: Int?): NetworkFailureState =
    NetworkFailureState(offline = !validated, serverUnavailable = validated && (status == null || status >= 500))
