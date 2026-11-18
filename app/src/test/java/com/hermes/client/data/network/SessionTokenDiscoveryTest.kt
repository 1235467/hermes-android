package com.hermes.client.data.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTokenDiscoveryTest {
    private fun server(body: String, code: Int = 200): MockWebServer =
        MockWebServer().apply {
            enqueue(MockResponse.Builder().code(code).body(body).build())
            start()
        }

    @Test fun `extracts injected session token from index_html`() = runTest {
        server(
            "<html><script>window.__HERMES_SESSION_TOKEN__=\"tok-abc-123\";window.x=1;</script></html>",
        ).use { srv ->
            val discovery = SessionTokenDiscovery(OkHttpClient())
            val base = srv.url("/").toString().trimEnd('/')
            assertEquals("tok-abc-123", discovery.fetch(base))
            assertEquals("tok-abc-123", discovery.cached(base))
        }
    }

    @Test fun `returns null when no token injected`() = runTest {
        server("<html><script>window.__HERMES_AUTH_REQUIRED__=true;</script></html>").use { srv ->
            val discovery = SessionTokenDiscovery(OkHttpClient())
            assertNull(discovery.fetch(srv.url("/").toString().trimEnd('/')))
        }
    }

    @Test fun `returns null on http error`() = runTest {
        server("", code = 404).use { srv ->
            val discovery = SessionTokenDiscovery(OkHttpClient())
            assertNull(discovery.fetch(srv.url("/").toString().trimEnd('/')))
        }
    }

    @Test fun `returns null when server unreachable`() = runTest {
        val srv = MockWebServer()
        srv.start()
        val base = srv.url("/").toString().trimEnd('/')
        srv.close()
        val discovery = SessionTokenDiscovery(OkHttpClient())
        assertNull(discovery.fetch(base))
    }
}
