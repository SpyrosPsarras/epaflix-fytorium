package com.epaflix.fytorium

internal data class NetworkFailureState(val offline: Boolean, val serverUnavailable: Boolean)

// A validated network separates device connectivity from server/DNS/response failures.
internal fun classifyNetworkFailure(validated: Boolean, status: Int?): NetworkFailureState =
    NetworkFailureState(offline = !validated, serverUnavailable = validated && (status == null || status >= 500))
