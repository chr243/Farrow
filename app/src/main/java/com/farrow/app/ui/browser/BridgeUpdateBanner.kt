package com.farrow.app.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.farrow.app.data.browser.BridgeUpdateState

/** "Bridge outdated (1.6.0 → 1.8.0), tap to update" — hidden when the bridge is current and nothing to report. */
@Composable
fun BridgeUpdateBanner(state: BridgeUpdateState?, onUpdate: () -> Unit, modifier: Modifier = Modifier) {
    if (state == null || (!state.outdated && !state.updating && state.message == null)) return
    val outdated = state.outdated
    Card(
        modifier.fillMaxWidth().clickable(enabled = outdated && !state.updating, onClick = onUpdate),
        colors = CardDefaults.cardColors(containerColor = if (outdated) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.updating) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when {
                        state.updating -> "Updating the bridge ${state.running ?: "?"} → ${state.bundled}…"
                        outdated -> "Bridge outdated (${state.running ?: "unknown"} → ${state.bundled}), tap to update"
                        else -> "Bridge ${state.running} is up to date"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                state.message?.takeIf { !state.updating }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (outdated && !state.updating) Text("Rewrites ~/.farrow/tbp_bridge.py and restarts the bridge in the background. Firefox keeps running.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
