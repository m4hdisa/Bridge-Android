package io.github.m4hdisa.bridge.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.m4hdisa.bridge.AppModel
import io.github.m4hdisa.bridge.R
import io.github.m4hdisa.bridge.UpdateState
import io.github.m4hdisa.bridge.Updates

/** A new version: what changed, then download, install or leave it for later. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(model: AppModel) {
    val state = model.update
    val info = state.info ?: return
    val context = LocalContext.current
    val persian = LocalConfiguration.current.locales[0].language == "fa"
    val notes = Updates.notes(info.notes, persian)
    ModalBottomSheet(onDismissRequest = { model.closeUpdateSheet() }, containerColor = Bridge.popover) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.update_version, info.version, Formatter.formatShortFileSize(context, info.size)), style = MaterialTheme.typography.bodySmall)
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (line in notes) Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (model.running) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.update_vpn_note), style = MaterialTheme.typography.bodySmall, color = Bridge.warning)
            }
            Spacer(Modifier.height(14.dp))
            when (state) {
                is UpdateState.Downloading -> {
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Bridge.muted)) {
                        Box(Modifier.fillMaxWidth(state.done.coerceIn(0f, 1f)).height(5.dp).clip(CircleShape).background(Bridge.brand))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.update_downloading, (state.done * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    FillButton(stringResource(R.string.cancel), Icons.X, { model.cancelUpdate() }, Modifier.fillMaxWidth())
                }
                is UpdateState.Ready -> {
                    if (model.updateNeedsPermission) {
                        Text(stringResource(R.string.update_allow), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                    }
                    FillButton(stringResource(R.string.update_install), Icons.Download, { model.installUpdate() }, Modifier.fillMaxWidth(), primary = true)
                }
                is UpdateState.Failed -> {
                    Text(stringResource(if (state.rejected) R.string.update_rejected else R.string.update_failed), style = MaterialTheme.typography.bodySmall, color = Bridge.destructive)
                    Spacer(Modifier.height(12.dp))
                    Actions(stringResource(R.string.update_retry), model)
                }
                else -> Actions(stringResource(R.string.update_now), model)
            }
        }
    }
}

@Composable
private fun Actions(main: String, model: AppModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FillButton(stringResource(R.string.update_later), null, { model.postponeUpdate() }, Modifier.weight(1f))
        FillButton(main, Icons.Download, { model.downloadUpdate() }, Modifier.weight(1f), primary = true)
    }
}
