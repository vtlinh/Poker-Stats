package com.pokerstats.odds.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pokerstats.odds.data.PreflopEquity
import com.pokerstats.odds.engine.Card as PlayingCard
import com.pokerstats.odds.engine.HandCategory
import com.pokerstats.odds.engine.Suit
import com.pokerstats.odds.ui.theme.CardWhite
import com.pokerstats.odds.ui.theme.ChipRed
import com.pokerstats.odds.ui.theme.InkBlack
import com.pokerstats.odds.ui.theme.LoseRed
import com.pokerstats.odds.ui.theme.TieAmber
import com.pokerstats.odds.ui.theme.WinGreen

@Composable
fun PokerScreen(viewModel: PokerViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }

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
                    CardRow(
                        cards = state.hole,
                        onAdd = { showPicker = true },
                        onRemove = viewModel::removeCard,
                    )
                }

                SectionCard(title = "Total Players") {
                    PlayerStepper(state.totalPlayers, viewModel::setPlayers)
                }

                if (!state.heroComplete) {
                    Text(
                        "Pick your two hole cards to see your odds.",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                } else {
                    OutlinedButton(onClick = viewModel::reset) { Text("Clear hand") }
                }

                state.result?.let { ResultPanel(it) }

                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showPicker) {
        CardPickerDialog(
            used = state.usedCards,
            onPick = { card ->
                viewModel.addCard(card)
                if (state.hole.size + 1 >= 2) showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

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
private fun CardRow(
    cards: List<PlayingCard>,
    onAdd: () -> Unit,
    onRemove: (PlayingCard) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cards.forEach { card ->
            PlayingCardView(
                card = card,
                modifier = Modifier.width(84.dp).clickable { onRemove(card) },
            )
        }
        if (cards.size < 2) {
            EmptySlot(modifier = Modifier.width(84.dp).clickable { onAdd() })
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun PlayingCardView(card: PlayingCard, modifier: Modifier = Modifier) {
    val suitColor = if (card.suit.isRed) ChipRed else InkBlack
    Box(
        modifier = modifier
            .aspectRatio(0.7f)
            .clip(RoundedCornerShape(8.dp))
            .background(CardWhite)
            .border(1.dp, Color(0x33000000), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(card.rank.symbol, color = suitColor, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Text(card.suit.glyph, color = suitColor, fontSize = 24.sp)
        }
    }
}

@Composable
private fun EmptySlot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(0.7f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
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
                "%.1f%%".format(result.winPercent),
                style = MaterialTheme.typography.displaySmall,
                color = WinGreen,
            )
            Text(
                "Equity (incl. split pots): %.1f%%".format(result.equityPercent),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )

            Spacer(Modifier.height(16.dp))
            OutcomeBar(result.winPercent, result.tiePercent, result.losePercent)

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend("Win", WinGreen, result.winPercent)
                Legend("Tie", TieAmber, result.tiePercent)
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
private fun OutcomeBar(win: Double, tie: Double, lose: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp)),
    ) {
        if (win > 0) Box(Modifier.weight(win.toFloat()).fillMaxSize().background(WinGreen))
        if (tie > 0) Box(Modifier.weight(tie.toFloat()).fillMaxSize().background(TieAmber))
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

@Composable
private fun CardPickerDialog(
    used: Set<PlayingCard>,
    onPick: (PlayingCard) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Select a card",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(Suit.entries.size),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.height(420.dp),
                ) {
                    items(PlayingCard.FULL_DECK) { card ->
                        val disabled = used.contains(card)
                        PickerCell(card, disabled) { if (!disabled) onPick(card) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun PickerCell(card: PlayingCard, disabled: Boolean, onClick: () -> Unit) {
    val suitColor = if (card.suit.isRed) ChipRed else InkBlack
    Box(
        modifier = Modifier
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(6.dp))
            .background(if (disabled) Color(0xFFBDBDBD) else CardWhite)
            .border(1.dp, Color(0x22000000), RoundedCornerShape(6.dp))
            .clickable(enabled = !disabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                card.rank.symbol,
                color = if (disabled) Color(0x55000000) else suitColor,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Text(
                card.suit.glyph,
                color = if (disabled) Color(0x55000000) else suitColor,
                fontSize = 14.sp,
            )
        }
    }
}
