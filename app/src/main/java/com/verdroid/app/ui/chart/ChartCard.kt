package com.verdroid.app.ui.chart

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.verdroid.app.agent.tools.ChartSpec
import com.verdroid.app.ui.components.MarkdownTableView
import com.verdroid.app.ui.components.MdAlign
import com.verdroid.app.ui.components.MdTable
import java.io.File

/** Theme-aware colors: Material primary/tertiary/secondary first, then the fixed palette. */
@Composable
private fun themeChartColors(): ChartColors {
    val cs = MaterialTheme.colorScheme
    return ChartColors(cs.surfaceContainerLow.toArgb(), cs.onSurface.toArgb(), cs.outlineVariant.toArgb(),
        intArrayOf(cs.primary.toArgb(), cs.tertiary.toArgb(), cs.secondary.toArgb()) + ChartColors.PALETTE.drop(3))
}

private fun ChartSpec.asTable(): MdTable {
    val (header, rows) = toRows()
    return MdTable(header, listOf(MdAlign.START) + List(header.size - 1) { MdAlign.END }, rows)
}

/** The chart itself (Canvas via the shared [ChartPainter]; type=table as a Markdown-style table). */
@Composable
fun ChartView(spec: ChartSpec, height: Dp, modifier: Modifier = Modifier) {
    if (spec.type == "table") {
        Column(modifier) {
            if (spec.title.isNotBlank()) Text(spec.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
            MarkdownTableView(spec.asTable(), MaterialTheme.colorScheme.onSurface)
        }
        return
    }
    val colors = themeChartColors()
    val density = LocalDensity.current.density
    val painter = remember(spec, colors, density) { ChartPainter(spec, colors, density) }
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = "Chart: ${spec.title.ifBlank { spec.type }}" }) {
        drawIntoCanvas { painter.draw(it.nativeCanvas, size.width, size.height, drawBackground = false) }
    }
}

/** Inline chart in the chat (tool card of `chart`): tap = fullscreen with Share. */
@Composable
fun ChartCard(spec: ChartSpec, imagePath: String?) {
    var full by remember { mutableStateOf(false) }
    Box(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow).clickable { full = true }.padding(6.dp)) {
        ChartView(spec, 200.dp)
    }
    if (full) ChartFullscreen(spec, imagePath) { full = false }
}

@Composable
private fun ChartFullscreen(spec: ChartSpec, imagePath: String?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    ChartView(spec, 420.dp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val file = imagePath?.let(::File)?.takeIf { it.exists() }
                    if (file != null) TextButton(onClick = {
                        runCatching {
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
                            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_SUBJECT, spec.title.ifBlank { "Chart" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            ctx.startActivity(Intent.createChooser(send, "Share chart").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Text("Share") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}
