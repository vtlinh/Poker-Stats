package com.pokerstats.odds.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pokerstats.odds.data.PreflopEquity
import com.pokerstats.odds.engine.HandCategory
import com.pokerstats.odds.engine.Rank
import com.pokerstats.odds.ui.theme.LoseRed
import com.pokerstats.odds.ui.theme.WinGreen
import kotlin.math.abs
import kotlin.math.roundToInt

// Deep, near-black slate-green with a subtle top glow.
private val ScreenBackground = Brush.verticalGradient(
    0f to Color(0xFF16291D),
    0.42f to Color(0xFF0B0F0D),
    1f to Color(0xFF080A09),
)

@Composable
fun PokerScreen(viewModel: PokerViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Check for updates every time the app comes to the foreground (initial
    // launch and each return from background), not just once.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.onStart()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize().background(ScreenBackground)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
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

                SectionCard(title = "Total Players") {
                    PlayerSlider(state.totalPlayers, viewModel::setPlayers)
                }

                SectionCard(title = "Your Hand") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RankWheel(state.rank1, viewModel::setRank1, Modifier.width(78.dp))
                        Spacer(Modifier.width(28.dp))
                        RankWheel(state.rank2, viewModel::setRank2, Modifier.width(78.dp))
                    }

                    Spacer(Modifier.height(18.dp))

                    if (state.isPair) {
                        InfoPill("Pocket pair")
                    } else {
                        SuitedToggle(suited = state.suited, onChange = viewModel::setSuited)
                    }
                }

                state.result?.let { ResultPanel(it) }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// --- infinite rank wheel ---------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RankWheel(
    selected: Rank,
    onSelected: (Rank) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ranks = remember { Rank.entries.sortedByDescending { it.value } } // A..2
    val n = ranks.size
    val itemHeight = 48.dp

    // Start deep in a virtually-infinite list, aligned so `selected` is centered.
    val start = remember {
        val half = Int.MAX_VALUE / 2
        half - (half % n) + ranks.indexOf(selected).coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val fling = rememberSnapFlingBehavior(lazyListState = listState)

    fun rankAt(index: Int): Rank = ranks[((index % n) + n) % n]

    val centerIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val items = info.visibleItemsInfo
            if (items.isEmpty()) {
                start
            } else {
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                items.minByOrNull { abs((it.offset + it.size / 2f) - center) }!!.index
            }
        }
    }

    // Report the centered rank once the wheel settles.
    LaunchedEffect(centerIndex, listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val r = rankAt(centerIndex)
            if (r != selected) onSelected(r)
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
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
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
            items(count = Int.MAX_VALUE) { index ->
                val isCenter = index == centerIndex
                Box(
                    Modifier.height(itemHeight).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        rankAt(index).symbol,
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

@Composable
private fun PlayerSlider(count: Int, onChange: (Int) -> Unit) {
    Column {
        Text(
            "$count",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Slider(
            value = count.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = MIN_PLAYERS.toFloat()..MAX_PLAYERS.toFloat(),
            steps = MAX_PLAYERS - MIN_PLAYERS - 1,
        )
        Text(
            "Break-even ${"%.1f".format(100.0 / count)}% (1 in $count)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
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
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "Poker like a",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "PRO!",
                fontFamily = FontFamily.Cursive,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.rotate(-14f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Win big, play like a PRO!",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
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
private fun ResultPanel(result: PreflopEquity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            SplitMetric(
                title = "Win Probability",
                caption = null,
                winPercent = result.winCountingTiesPercent,
            )

            Spacer(Modifier.height(18.dp))
            FoldSplit(result)

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

/** A titled win/lose split: a header row, the outcome bar, and the legend. */
@Composable
private fun SplitMetric(title: String, caption: String?, winPercent: Double) {
    val losePercent = (100.0 - winPercent).coerceIn(0.0, 100.0)
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(10.dp))
        OutcomeBar(winPercent, losePercent)
    }
}

/**
 * The pre-flop-fold win/lose split, shown below the normal split. Everyone
 * folds hands below break-even (1/N), the hero included — so a below-break-even
 * hand is a fold rather than a playable split.
 */
@Composable
private fun FoldSplit(result: PreflopEquity) {
    val heroFolds = result.winCountingTies < 1.0 / result.players
    if (heroFolds) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Pre-flop fold",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "below break-even (1 in ${result.players}) — fold this hand",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "Fold",
                style = MaterialTheme.typography.headlineSmall,
                color = LoseRed,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        SplitMetric(
            title = "Pre-flop fold",
            caption = "if weak hands fold below break-even (1 in ${result.players})",
            winPercent = result.winFoldCountingTiesPercent,
        )
    }
}

@Composable
private fun OutcomeBar(win: Double, lose: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp)),
    ) {
        BarSegment("Win", win, WinGreen)
        BarSegment("Lose", lose, LoseRed)
    }
}

/** A weighted colored bar segment with its percentage labelled inside it. */
@Composable
private fun RowScope.BarSegment(label: String, percent: Double, color: Color) {
    if (percent <= 0) return
    Box(
        modifier = Modifier
            .weight(percent.toFloat())
            .fillMaxSize()
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        // Word + number when the segment is wide enough, else just the number,
        // else nothing (the colour still carries the meaning).
        val text = when {
            percent >= 25 -> "%s %.1f%%".format(label, percent)
            percent >= 9 -> "%.0f%%".format(percent)
            else -> ""
        }
        if (text.isNotEmpty()) {
            Text(
                text,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
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
