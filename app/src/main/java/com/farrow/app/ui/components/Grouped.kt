package com.farrow.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Settings-style grouping: related rows sit in one rounded card, and rows alternate between two close surface tones
 * (surfaceContainerLow / surfaceContainer) so long lists don't read as a wall of text. Theme roles only.
 */
object Zebra {
    @Composable
    fun color(index: Int): Color =
        if (index % 2 == 0) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer

    private val R = 16.dp

    /** Shape of row [index] of [count] inside a group: rounded on the group's outer corners only. */
    fun shape(index: Int, count: Int) = RoundedCornerShape(
        topStart = if (index == 0) R else 0.dp, topEnd = if (index == 0) R else 0.dp,
        bottomStart = if (index == count - 1) R else 0.dp, bottomEnd = if (index == count - 1) R else 0.dp,
    )
}

/** For LazyColumn items: row [index] of a group of [count] — horizontal inset, group corners, zebra background. */
@Composable
fun Modifier.groupedRow(index: Int, count: Int): Modifier =
    this.fillMaxWidth().padding(horizontal = 12.dp).clip(Zebra.shape(index, count)).background(Zebra.color(index))

/** Section title above a group. */
@Composable
fun GroupHeader(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 28.dp, end = 16.dp, top = 18.dp, bottom = 6.dp))
}

/** Non-lazy group: optional title + a rounded card whose children are given their zebra index. */
@Composable
fun SettingsGroup(title: String?, rows: List<@Composable (index: Int) -> Unit>) {
    if (title != null) GroupHeader(title)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp))) {
        rows.forEachIndexed { i, row -> Column(Modifier.fillMaxWidth().background(Zebra.color(i))) { row(i) } }
    }
}

/** Plain rounded card on surfaceContainerLow for free-form content. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp), content = content)
}
