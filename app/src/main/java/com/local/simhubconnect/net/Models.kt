package com.local.simhubconnect.net

/** A device on the LAN that answered on SimHub's dashboard port. */
data class FoundServer(
    val host: String,
    val port: Int,
    val latencyMs: Long?,
    /** True when the HTTP response came from SimHub's dashboard web server. */
    val simhub: Boolean
) {
    val address: String get() = "$host:$port"
}
