package com.hermes.client.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.network.ClientCertManager
import com.hermes.client.data.network.GatedAuth
import com.hermes.client.data.network.HermesRestApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SetupUiState(
    val url: String = "",
    val token: String = "",
    val username: String = "",
    val password: String = "",
    val testResult: String? = null,
    val saved: Boolean = false,
    val scanError: String? = null,
    // KeyChain alias of the picked client certificate (mTLS); blank = none.
    val clientCertAlias: String = "",
    val clientCertSummary: String? = null,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val store: CredentialStore,
    private val rest: HermesRestApi,
    private val gatedAuth: GatedAuth,
    private val certs: ClientCertManager,
) : ViewModel() {
    private val _state = MutableStateFlow(
        store.load()?.let {
            SetupUiState(
                url = it.baseUrl,
                token = it.token,
                username = it.username,
                password = it.password,
                clientCertAlias = it.clientCertAlias,
            )
        } ?: SetupUiState(),
    )
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    init {
        _state.value.clientCertAlias.takeIf { it.isNotBlank() }?.let(::loadCertSummary)
    }

    fun onUrlChange(v: String) { _state.value = _state.value.copy(url = v.trim()) }
    fun onTokenChange(v: String) { _state.value = _state.value.copy(token = v.trim()) }
    fun onUsernameChange(v: String) { _state.value = _state.value.copy(username = v.trim()) }
    fun onPasswordChange(v: String) { _state.value = _state.value.copy(password = v) }

    /** Result of the system cert picker: null = user cancelled (keep current selection). */
    fun onCertAliasPicked(alias: String?) {
        if (alias == null) return
        _state.value = _state.value.copy(clientCertAlias = alias, testResult = null)
        loadCertSummary(alias)
    }

    fun clearCertAlias() {
        _state.value = _state.value.copy(clientCertAlias = "", clientCertSummary = null, testResult = null)
    }

    // KeyChain resolution is a binder call — keep it off the main thread.
    private fun loadCertSummary(alias: String) = viewModelScope.launch {
        val summary = withContext(Dispatchers.IO) { certs.certSummary(alias) }
        _state.value = _state.value.copy(clientCertSummary = summary)
    }

    // T10b: test() must NOT persist unverified credentials — probe with transient values.
    fun test() = viewModelScope.launch {
        val s = _state.value
        val ok = if (s.username.isNotBlank()) {
            withContext(Dispatchers.IO) {
                gatedAuth.probeLogin(s.url, s.username, s.password, s.clientCertAlias)
            }
        } else {
            rest.statusFor(s.url, s.token, s.clientCertAlias)
        }
        _state.value = _state.value.copy(testResult = if (ok) "Connected" else "Unreachable")
    }

    fun save() {
        val s = _state.value
        store.save(
            GatewayConfig(
                baseUrl = s.url,
                token = s.token,
                username = s.username.trim(),
                password = s.password,
                clientCertAlias = s.clientCertAlias,
            ),
        )
        gatedAuth.cookieJar.clear()
        _state.value = _state.value.copy(saved = true)
    }

    /** Apply a scanned pairing QR: prefill the fields and auto-run the existing probe. */
    fun applyPairing(raw: String) {
        val p = parsePairingPayload(raw)
        if (p == null) {
            _state.value = _state.value.copy(scanError = "Not a Hermes pairing code")
            return
        }
        _state.value = _state.value.copy(
            url = p.url, token = p.token, username = p.username, password = p.password,
            scanError = null, testResult = null,
        )
        test()
    }

    fun clearScanError() {
        if (_state.value.scanError != null) _state.value = _state.value.copy(scanError = null)
    }
}
