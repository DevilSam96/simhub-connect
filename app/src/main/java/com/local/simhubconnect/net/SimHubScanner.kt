package com.local.simhubconnect.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scans a list of IPv4 hosts for SimHub's dashboard server.
 *
 * Probes run 32 at a time with a short TCP timeout, so a whole /24 is covered
 * in a couple of seconds on a normal home or sim Wi-Fi network.
 */
class SimHubScanner(private val probe: SimHubProbe = SimHubProbe()) {

    suspend fun scan(
        hosts: List<String>,
        port: Int,
        onProgress: (scanned: Int, total: Int) -> Unit,
        onFound: (FoundServer) -> Unit
    ) {
        val total = hosts.size
        if (total == 0) return
        val counter = AtomicInteger(0)
        val gate = Semaphore(CONCURRENCY)

        coroutineScope {
            for (host in hosts) {
                launch(Dispatchers.IO) {
                    gate.withPermit {
                        probe.probe(host, port)?.let(onFound)
                    }
                    val scanned = counter.incrementAndGet()
                    if (scanned % 8 == 0 || scanned == total) onProgress(scanned, total)
                }
            }
        }
    }

    private companion object {
        const val CONCURRENCY = 32
    }
}
