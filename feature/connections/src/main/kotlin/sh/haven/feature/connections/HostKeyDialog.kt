package sh.haven.feature.connections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import sh.haven.core.ssh.KnownHostEntry

@Composable
fun NewHostKeyDialog(
    entry: KnownHostEntry,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Verify Host Key") },
        text = {
            Column {
                val hostDisplay = if (entry.port == 22) entry.hostname
                    else "${entry.hostname}:${entry.port}"
                Text("Connecting to $hostDisplay for the first time.")
                Spacer(Modifier.height(12.dp))
                Text("Key type: ${entry.keyType}")
                Spacer(Modifier.height(8.dp))
                Text("Fingerprint:")
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.fingerprint(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Verify this fingerprint matches the server's key before trusting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) {
                Text("Trust")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun KeyChangedDialog(
    oldFingerprint: String,
    entry: KnownHostEntry,
    onAccept: () -> Unit,
    onDisconnect: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDisconnect,
        title = {
            Text(
                "Host Key Changed",
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Column {
                val hostDisplay = if (entry.port == 22) entry.hostname
                    else "${entry.hostname}:${entry.port}"
                Text(
                    "The host key for $hostDisplay has changed. " +
                        "This could indicate a server reinstall or a man-in-the-middle attack.",
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
                Text("Old fingerprint:", style = MaterialTheme.typography.bodySmall)
                Text(
                    oldFingerprint,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                Text("New fingerprint:", style = MaterialTheme.typography.bodySmall)
                Text(
                    entry.fingerprint(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text("Accept New Key")
            }
        },
        dismissButton = {
            TextButton(onClick = onDisconnect) {
                Text("Disconnect")
            }
        },
    )
}
