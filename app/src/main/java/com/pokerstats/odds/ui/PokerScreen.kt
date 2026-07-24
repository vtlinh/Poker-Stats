package com.pokerstats.odds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pokerstats.odds.data.PreflopEquity
import com.pokerstats.odds.engine.HandCategory
import com.pokerstats.odds.engine.Rank
import com.pokerstats.odds.ui.theme.LoseRed
import com.pokerstats.odds.ui.theme.WinGreen
import kotlin.math.abs

@Composable
fun PokerScreen(viewModel: PokerViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.onStart() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.update.showBanner) {
                UpdateBanner(
                    state = state.update,
                    onUpdate = { viewModel.startUpdate(context) },
                    onDismiss = viewModel::dismissUpdate,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Header()

                SectionCard(title = "Your Hand") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        RankWheel(state.rank1, viewModel::setRank1, Modifier.weight(1f))
                        RankWheel(state.rank2, viewModel::setRank2, Modifier.weight(1f))
                    }

                    Spacer(Modifier.height(18.dp))

                    if (state.isPair) {
                        InfoPill("Pocket pair")
                    } else {
                        SuitedToggle(suited = state.suited, onChange = viewModel::setSuited)
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        state.handClass,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }

                SectionCard(title = "Total Players") {
                    PlayerStepper(state.totalPlayers, viewModel::setPlayers)
                }

                state.result?.let { ResultPanel(it) }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// --- rank wheel ------------------------------------------------------------

@Composable
private fun RankWheel(
    selected: Rank,
    onSelected: (Rank) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ranks = remember { Rank.entries.sortedByDescending { it.value } } // A..2
    val itemHeight = 48.dp
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = ranks.indexOf(selected).coerceAtLeast(0),
    )
    val fling = rememberSnapFlingBehavior(lazyListState = listState)

    val centerIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val items = info.visibleItemsInfo
            if (items.isEmpty()) {
                ranks.indexOf(selected).coerceAtLeast(0)
            } else {
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                items.minByOrNull { abs((it.offset + it.size / 2f) - center) }!!.index
            }
        }
    }

    // Report the centered rank once the wheel settles.
    LaunchedEffect(centerIndex, listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            ranks.getOrNull(centerIndex)?.let { if (it != selected) onSelected(it) }
        }
    }

    val surface = MaterialTheme.colorScheme.surface
    Box(modifier = modifier.height(itemHeight * 3), contentAlignment = Alignment.Center) {
        // Selection window highlight.
        Box(
            Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    RoundedCornerShape(10.dp),
                ),
        )
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = itemHeight),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(ranks) { index, rank ->
                val isCenter = index == centerIndex
                Box(
                    Modifier.height(itemHeight).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        rank.symbol,
                        fontSize = if (isCenter) 32.sp else 20.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCenter) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        },
                    )
                }
            }
        }
        // Top & bottom fades for the slot-machine look.
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(itemHeight)
                .background(Brush.verticalGradient(listOf(surface, surface.copy(alpha = 0f)))),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(itemHeight)
                .background(Brush.verticalGradient(listOf(surface.copy(alpha = 0f), surface))),
        )
    }
}

@Composable
private fun SuitedToggle(suited: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        TogglePill("Suited", selected = suited) { onChange(true) }
        Spacer(Modifier.width(10.dp))
        TogglePill("Offsuit", selected = !suited) { onChange(false) }
    }
}

@Composable
private fun TogglePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Text(label, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InfoPill(label: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                .padding(horizontal = 24.dp, vertical = 10.dp),
        ) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// --- update banner ---------------------------------------------------------

@Composable
private fun UpdateBanner(
    state: UpdateUiState,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primary) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !state.downloading, onClick = onUpdate)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.downloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Downloading update… ${(state.progress * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text("⬆", color = MaterialTheme.colorScheme.onPrimary, fontSize = 18.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Update available — v${state.available?.versionName}",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Tap to download & install",
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("✕", color = MaterialTheme.colorScheme.onPrimary, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            "Poker Odds",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Texas Hold'em win probability",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            content()
        }
    }
}

@Composable
private fun PlayerStepper(count: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(count - 1) }, enabled = count > MIN_PLAYERS) {
            Text("−", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            "$count",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { onChange(count + 1) }, enabled = count < MAX_PLAYERS) {
            Text("+", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "players (you + ${count - 1})",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun ResultPanel(result: PreflopEquity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Win Probability",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "%.1f%%".format(result.winCountingTiesPercent),
                style = MaterialTheme.typography.displaySmall,
                color = WinGreen,
            )
            if (result.tiePercent >= 0.05) {
                Text(
                    "Includes %.1f%% ties (counted as wins)".format(result.tiePercent),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }

            Spacer(Modifier.height(16.dp))
            OutcomeBar(result.winCountingTiesPercent, result.losePercent)

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend("Win", WinGreen, result.winCountingTiesPercent)
                Legend("Lose", LoseRed, result.losePercent)
            }

            if (result.categoryFrequency.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                Spacer(Modifier.height(16.dp))
                Text(
                    "Your hand makes",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                result.categoryFrequency.entries
                    .sortedByDescending { it.key.ordinal }
                    .filter { it.value > 0.0005 }
                    .forEach { (category, freq) -> CategoryRow(category, freq * 100.0) }
            }
        }
    }
}

@Composable
private fun OutcomeBar(win: Double, lose: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp)),
    ) {
        if (win > 0) Box(Modifier.weight(win.toFloat()).fillMaxSize().background(WinGreen))
        if (lose > 0) Box(Modifier.weight(lose.toFloat()).fillMaxSize().background(LoseRed))
    }
}

@Composable
private fun Legend(label: String, color: Color, percent: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            "$label %.1f%%".format(percent),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CategoryRow(category: HandCategory, percent: Double) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                category.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "%.1f%%".format(percent),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((percent / 100.0).toFloat().coerceIn(0f, 1f))
                    .fillMaxSize()
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
