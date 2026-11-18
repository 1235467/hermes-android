package com.hermes.client.data.network

import io.mockk.every
import io.mockk.mockk
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientCertManagerTest {

    private fun cert(cn: String): X509Certificate = mockk {
        every { subjectX500Principal } returns X500Principal("CN=$cn")
        every { notAfter } returns Date(999_999_999_000L)
    }

    /** Fake KeyChain: serves a fixed chain for aliases in [known], null otherwise. */
    private class FakeSource(private val known: Set<String>, private val leaf: X509Certificate) : ClientCertSource {
        var chainCalls = 0
        override fun privateKey(alias: String): PrivateKey? =
            if (alias in known) mockk() else null
        override fun certificateChain(alias: String): Array<X509Certificate>? {
            chainCalls++
            return if (alias in known) arrayOf(leaf) else null
        }
    }

    @Test fun socketFactoryFor_blank_alias_returns_default() {
        val m = ClientCertManager(FakeSource(emptySet(), cert("x"))) { null }
        // Blank/null alias → the same shared default factory, never touches the source.
        assertSame(m.socketFactoryFor(null), m.socketFactoryFor(""))
    }

    @Test fun socketFactoryFor_known_alias_returns_cert_aware_factory_and_caches() {
        val src = FakeSource(setOf("phone"), cert("phone"))
        val m = ClientCertManager(src) { "phone" }
        val f1 = m.socketFactoryFor("phone")
        assertNotSame(m.socketFactoryFor(null), f1)
        m.socketFactoryFor("phone")
        assertEquals(1, src.chainCalls) // cached SSLContext — chain only resolved once
    }

    @Test fun unknown_alias_fails_fast_with_readable_error() {
        val m = ClientCertManager(FakeSource(emptySet(), cert("x"))) { "gone" }
        val e = assertThrows(ClientCertUnavailableException::class.java) {
            m.socketFactoryFor("gone")
        }
        assertTrue(e.message.orEmpty().contains("gone"))
    }

    @Test fun dynamic_factory_only_resolves_cert_when_alias_configured() {
        val src = FakeSource(setOf("phone"), cert("phone"))
        var alias: String? = null
        val m = ClientCertManager(src) { alias }

        m.socketFactory.defaultCipherSuites // no alias → default path, no KeyChain hit
        assertEquals(0, src.chainCalls)

        alias = "phone"
        m.socketFactory.defaultCipherSuites
        assertEquals(1, src.chainCalls)

        alias = null // cleared → back to default without touching KeyChain
        m.socketFactory.defaultCipherSuites
        assertEquals(1, src.chainCalls)
    }

    @Test fun certSummary_null_when_unresolvable_subject_otherwise() {
        val m = ClientCertManager(FakeSource(setOf("phone"), cert("phone")), { null })
        assertNull(m.certSummary("missing"))
        assertTrue(m.certSummary("phone")!!.contains("CN=phone"))
    }
}
