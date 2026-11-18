package com.hermes.client.data.network

import android.content.Context
import android.security.KeyChain
import com.hermes.client.data.diagnostics.DebugLog
import java.net.InetAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** The stored KeyChain alias no longer resolves to a usable certificate chain. */
class ClientCertUnavailableException(alias: String) :
    Exception("client certificate \"$alias\" unavailable — reselect it in connection settings")

/**
 * Testable seam over android.security.KeyChain so the manager and its key manager run in plain
 * JVM tests. Only the alias ever leaves this layer — key material stays in the system keystore.
 */
interface ClientCertSource {
    fun privateKey(alias: String): PrivateKey?
    fun certificateChain(alias: String): Array<X509Certificate>?
}

class KeyChainCertSource(private val context: Context) : ClientCertSource {
    override fun privateKey(alias: String): PrivateKey? =
        KeyChain.getPrivateKey(context, alias)

    override fun certificateChain(alias: String): Array<X509Certificate>? =
        KeyChain.getCertificateChain(context, alias)
}

/**
 * Answers "which client cert to present" with the configured KeyChain alias and serves the
 * key material from [source] on demand. Because the alias was granted through the system picker
 * (KeyChain.choosePrivateKeyAlias), this works for hardware-backed (TEE/StrongBox) keys that
 * can never be exported as a file.
 */
private class AliasKeyManager(
    private val alias: String,
    private val source: ClientCertSource,
) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String = alias

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String = alias

    override fun getPrivateKey(alias: String?): PrivateKey? =
        alias?.let(source::privateKey)

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        alias?.let(source::certificateChain)

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
}

/**
 * Resolves an SSLSocketFactory per connection attempt from the *current* config: no alias → the
 * platform default; alias set → an SSLContext that presents that client cert. Socket factories
 * are resolved lazily (per createSocket call) so changing or clearing the alias takes effect on
 * the next connect without rebuilding the shared OkHttpClient.
 */
private class DynamicCertSocketFactory(
    private val delegate: () -> SSLSocketFactory,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate().defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate().supportedCipherSuites
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        delegate().createSocket(s, host, port, autoClose)
    override fun createSocket(host: String, port: Int): Socket = delegate().createSocket(host, port)
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        delegate().createSocket(host, port, localHost, localPort)
    override fun createSocket(address: InetAddress, port: Int): Socket =
        delegate().createSocket(address, port)
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        delegate().createSocket(address, port, localAddress, localPort)
}

class ClientCertManager(
    private val source: ClientCertSource,
    private val aliasProvider: () -> String?,
) {
    /** Server-side validation is unchanged: always the platform trust manager. */
    val trustManager: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as java.security.KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Install on any OkHttpClient that must honour the configured client certificate. */
    val socketFactory: SSLSocketFactory = DynamicCertSocketFactory(::resolveSocketFactory)

    private val defaultFactory by lazy { SSLSocketFactory.getDefault() as SSLSocketFactory }

    // alias -> SSLContext. Invalidated whenever the configured alias changes.
    private var cached: Pair<String, SSLContext>? = null

    @Synchronized
    private fun resolveSocketFactory(): SSLSocketFactory {
        val alias = aliasProvider()?.takeIf { it.isNotBlank() } ?: return defaultFactory
        return contextFor(alias).socketFactory
    }

    /**
     * Socket factory for an explicitly named alias — used by the setup "Test" probes, which
     * must honour a cert the user just picked but hasn't saved yet. Blank alias = no cert.
     */
    fun socketFactoryFor(alias: String?): SSLSocketFactory =
        if (alias.isNullOrBlank()) defaultFactory else contextFor(alias).socketFactory

    @Synchronized
    private fun contextFor(alias: String): SSLContext {
        cached?.let { (a, ctx) -> if (a == alias) return ctx }
        // Fail fast with a readable error when the cert was uninstalled or the keystore is
        // locked — otherwise the failure surfaces deep inside the TLS handshake.
        val chain = runCatching { source.certificateChain(alias) }.getOrNull()
        if (chain.isNullOrEmpty()) {
            DebugLog.log("tls", "client cert alias \"$alias\" unusable")
            throw ClientCertUnavailableException(alias)
        }
        val ctx = SSLContext.getInstance("TLS").apply {
            init(arrayOf(AliasKeyManager(alias, source)), null, null)
        }
        cached = alias to ctx
        DebugLog.log("tls", "client cert ready alias=\"$alias\" subject=${chain[0].subjectX500Principal.name}")
        return ctx
    }

    /** One-line human-readable summary (subject + expiry) for the settings UI. */
    fun certSummary(alias: String): String? =
        runCatching { source.certificateChain(alias)?.firstOrNull() }.getOrNull()?.let {
            "${it.subjectX500Principal.name} — expires ${it.notAfter}"
        }
}
