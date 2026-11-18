package com.hermes.client.data.auth

data class GatewayConfig(
    val baseUrl: String,
    val token: String = "",
    // Set for a network-exposed (gated) dashboard that requires a password provider. When a
    // username is present the app authenticates via POST /auth/password-login (session cookies)
    // plus a per-socket WS ticket, instead of the loopback session token.
    val username: String = "",
    val password: String = "",
    // KeyChain alias of a client certificate for mTLS. Only the alias is persisted — the key
    // material never leaves the system keystore. Blank = no client certificate.
    val clientCertAlias: String = "",
) {
    /** True when this targets a gated dashboard (basic-auth); false for a loopback/token setup. */
    val isGated: Boolean get() = username.isNotBlank()

    /** True when a client certificate should be presented during the TLS handshake. */
    val usesMtls: Boolean get() = clientCertAlias.isNotBlank()

    /** Base WS endpoint with no auth query — the auth param (?token / ?ticket) is appended later. */
    val wsBase: String
        get() {
            val ws = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
            return "${ws.trimEnd('/')}/api/ws"
        }

    /**
     * Loopback WS URL using the session token, e.g. ws://host:9119/api/ws?token=...
     * Only used in non-gated mode; gated mode appends a per-connect ?ticket= instead.
     */
    val wsUrl: String get() = if (token.isBlank()) wsBase else "$wsBase?token=$token"
}

interface CredentialStore {
    fun load(): GatewayConfig?
    fun save(config: GatewayConfig)
    fun clear()
}
