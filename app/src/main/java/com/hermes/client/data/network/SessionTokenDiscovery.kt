package com.hermes.client.data.network

import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers the dashboard session token the same way the browser and the Desktop shell do:
 * the loopback server injects `window.__HERMES_SESSION_TOKEN__="<token>"` into the served
 * index.html, and the page feeds it to `/api/ws` as `?token=`. When the user leaves the
 * Token field blank we scrape the same marker so unauthenticated (e.g. reverse-proxy/mTLS
 * fronted) loopback deployments connect without manual entry.
 *
 * Gated deployments withhold the marker, and servers running with an unset
 * `HERMES_DASHBOARD_SESSION_TOKEN` rotate the token on every restart — so callers should
 * prefer a fresh fetch per connection attempt over trusting the cache across reconnects.
 */
class SessionTokenDiscovery(private val okHttp: OkHttpClient) {
    /** baseUrl (trailing-slash normalised) -> last scraped token. */
    private val cache = ConcurrentHashMap<String, String>()

    /** Last token found for [baseUrl], or null when never scraped / not injected. */
    fun cached(baseUrl: String): String? = cache[baseUrl.trimEnd('/')]

    /**
     * GET {baseUrl}/ and extract the injected session token; caches and returns it on success,
     * null when the page is unreachable, non-2xx, or carries no token (gated/withheld).
     */
    suspend fun fetch(baseUrl: String): String? = withContext(Dispatchers.IO) {
        val key = baseUrl.trimEnd('/')
        val token = runCatching {
            okHttp.newCall(Request.Builder().url("$key/").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                TOKEN_REGEX.find(resp.body?.string().orEmpty())?.groupValues?.get(1)
            }
        }.getOrNull()
        if (token != null) {
            cache[key] = token
            DebugLog.log("ws", "session token scraped from index.html for $key")
        } else {
            DebugLog.log("ws", "no session token in index.html for $key")
        }
        token
    }

    private companion object {
        val TOKEN_REGEX = Regex("window\\.__HERMES_SESSION_TOKEN__\\s*=\\s*\"([^\"]+)\"")
    }
}
