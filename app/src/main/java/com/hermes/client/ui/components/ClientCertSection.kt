package com.hermes.client.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.security.KeyChain
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Launch the system certificate picker (KeyChain). The returned alias can later be resolved to
 * key material by the app — but only for aliases the user granted here. null = user cancelled.
 */
fun launchClientCertPicker(context: Context, currentAlias: String, onResult: (String?) -> Unit) {
    val activity = context.findActivity() ?: return
    KeyChain.choosePrivateKeyAlias(
        activity,
        { alias -> onResult(alias) },
        arrayOf("RSA", "EC"),
        null, // issuers — accept certs from any issuer
        null, // uri — no host/port filtering
        currentAlias.ifBlank { null },
    )
}

/** Launch the system flow for importing a .p12/.pem into the Android credential store. */
fun launchCertInstall(context: Context) {
    runCatching { context.startActivity(KeyChain.createInstallIntent()) }
}

/**
 * mTLS client-certificate row shared by first-run setup and connection settings: pick a
 * certificate from the system keystore, see what was picked, or install one into it.
 */
@Composable
fun ClientCertSection(
    alias: String,
    summary: String?,
    onAliasPicked: (String?) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = { launchClientCertPicker(context, alias, onAliasPicked) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (alias.isBlank()) "Choose client certificate (mTLS — optional)"
                else "Client certificate: ${summary ?: alias}",
            )
        }
        if (alias.isNotBlank()) {
            TextButton(onClick = onClear) { Text("Remove client certificate") }
        } else {
            Text(
                "Required only if your gateway demands mutual TLS. The key stays in the " +
                    "Android keystore; Hermes never sees a key file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = { launchCertInstall(context) }) {
                    Text("Install a certificate first")
                }
            }
        }
    }
}
