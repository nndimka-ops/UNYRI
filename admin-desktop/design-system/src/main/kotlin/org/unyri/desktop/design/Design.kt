package org.unyri.desktop.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Ink {
    val background = Color(0xFF0A0A0A)
    val surface = Color(0xFF202020)
    val raised = Color(0xFF282828)
    val text = Color(0xFFF1F1F1)
    val secondary = Color(0xFFB0B0B0)
    val line = Color(0xFF494949)
    val danger = Color(0xFFFF4D5F)
}
@Composable fun DesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFDEDEDE), onPrimary = Ink.surface, secondary = Ink.secondary,
        background = Ink.background, surface = Ink.surface, surfaceVariant = Ink.raised,
        onBackground = Ink.text, onSurface = Ink.text, outline = Ink.line, error = Ink.danger
    ), typography = Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
        bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)), content = content)
}
@Composable fun GlassCard(modifier: Modifier = Modifier, glass: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.background(if (glass) Brush.verticalGradient(listOf(Color(0xFF303030), Color(0xFF181818)))
        else Brush.verticalGradient(listOf(Ink.surface, Ink.surface)), RoundedCornerShape(20.dp))
        .border(BorderStroke(1.dp, Color.White.copy(alpha = .10f)), RoundedCornerShape(20.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
@Composable fun PageHeader(title: String, subtitle: String, actions: @Composable RowScope.() -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Ink.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun Toolbar(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
@Composable fun SidebarItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(color = if (selected) Color(0xFF323232) else Color.Transparent, shape = RoundedCornerShape(16.dp)) {
        TextButton(onClick, Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            Text(text, Modifier.fillMaxWidth(), color = if (selected) Ink.text else Ink.secondary)
        }
    }
}
data class TableRow(val id: String, val cells: List<String>)
@Composable fun DataTable(headers: List<String>, rows: List<TableRow>, openLabel: String, empty: String,
    sortLabel: String, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    var sort by remember(headers) { mutableStateOf(-1) }; var ascending by remember { mutableStateOf(true) }
    val ordered = remember(rows, sort, ascending) { if (sort < 0) rows else rows.sortedWith(compareBy<TableRow> { it.cells.getOrElse(sort) { "" }.lowercase() }).let { if (ascending) it else it.reversed() } }
    Column(modifier) {
        Text(sortLabel, color = Ink.secondary, style = MaterialTheme.typography.labelSmall)
        Column(Modifier.weight(1f).horizontalScroll(rememberScrollState()).widthIn(min = 820.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().background(Ink.raised).padding(8.dp)) {
                headers.forEachIndexed { index, title ->
                    TextButton({ if (sort == index) ascending = !ascending else { sort = index; ascending = true } }, Modifier.width(210.dp)) {
                        Text(title + if (sort == index) if (ascending) " ↑" else " ↓" else "")
                    }
                }
                Spacer(Modifier.width(110.dp))
            }
            if (rows.isEmpty()) Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(empty, color = Ink.secondary) }
            else LazyColumn(Modifier.fillMaxSize()) {
                items(ordered, key = { it.id }) { row ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        row.cells.forEach { Text(it, Modifier.width(210.dp).padding(8.dp), maxLines = 3) }
                        TextButton({ onOpen(row.id) }, Modifier.width(110.dp)) { Text(openLabel) }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = .07f))
                }
            }
        }
    }
}
@Composable fun StateCard(text: String, action: String? = null, onAction: () -> Unit = {}) {
    GlassCard(Modifier.fillMaxWidth()) { Text(text); if (action != null) OutlinedButton(onAction) { Text(action) } }
}
@Composable fun Metric(label: String, value: String) {
    GlassCard(Modifier.widthIn(min = 180.dp).width(230.dp), glass = false) {
        Text(value, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Ink.secondary)
    }
}
@Composable fun TechnicalText(text: String) { Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Ink.secondary) }
