package com.local.simhubconnect.net

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Probes one host: a TCP connect tells us the device is reachable, then the
 * HTTP response tells us whether it is SimHub's dashboard web server.
 *
 * SimHub serves its dashboard list on `/` and exposes `/DashTemplates/List`
 * (JSON), used as a second signal when the first page does not mention SimHub.
 */
class SimHubProbe(
    private val connectTimeoutMs: Int = 600,
    private val readTimeoutMs: Int = 2500
) {

    fun probe(host: String, port: Int): FoundServer? {
        val started = System.nanoTime()
        try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            }
        } catch (_: Exception) {
            return null
        }
        val latency = (System.nanoTime() - started) / 1_000_000

        val base = "http://$host:$port"
        val listPageLooksRight = fetch("$base/")?.let(::looksLikeSimHub) == true
        val simhub = listPageLooksRight ||
            fetch("$base/DashTemplates/List")?.let(::looksLikeDashboardList) == true

        return FoundServer(host, port, latency, simhub)
    }

    private fun looksLikeSimHub(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("simhub") || lower.contains("dashstudio") || lower.contains("dash studio")
    }

    private fun looksLikeDashboardList(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.length > 2 && (trimmed.startsWith("[") || trimmed.startsWith("{"))
    }

    private fun fetch(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SimHubConnect/1.0 (Android)")
                setRequestProperty("Accept", "text/html,application/json")
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { reader ->
                val buffer = CharArray(16 * 1024)
                val read = reader.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read)
            } ?: ""
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
