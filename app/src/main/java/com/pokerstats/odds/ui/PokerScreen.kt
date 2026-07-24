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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

// Ranks high→low, matching the poker-matrix layout (A across the top/left).
private val MATRIX_RANKS: List<String> =
    Rank.entries.sortedByDescending { it.value }.map { it.symbol }

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

    var tab by rememberSaveable { mutableStateOf(Tab.HANDS) }

    Box(modifier = Modifier.fillMaxSize().background(ScreenBackground)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            if (state.update.showBanner) {
                UpdateBanner(
                    state = state.update,
                    onUpdate = { viewModel.startUpdate(context) },
                    onDismiss = viewModel::dismissUpdate,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.HANDS -> HandsTab(state, viewModel)
                    Tab.TABLE -> TableTab(state, viewModel)
                }
            }
            TabBar(selected = tab, onSelect = { tab = it })
        }
    }
}

/** The two bottom-bar destinations. */
private enum class Tab(val label: String, val glyph: String) {
    HANDS("Hands", "♠"),
    TABLE("Table", "▦"),
}

/** The original single-hand calculator. */
@Composable
private fun HandsTab(state: PokerUiState, viewModel: PokerViewModel) {
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

/**
 * The whole 13×13 starting-hand matrix, once per probability. Each cell is
 * color-graded from red (weak) to green (strong) within its own grid; a hand
 * that folds (0%) is shown black.
 */
@Composable
private fun TableTab(state: PokerUiState, viewModel: PokerViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SectionCard(title = "Total Players") {
            PlayerSlider(state.totalPlayers, viewModel::setPlayers)
        }

        if (state.table.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            val breakEven = 1.0 / state.totalPlayers
            HandMatrix(
                title = "Win Probability",
                caption = "Chance to win (ties split), all players to showdown",
                values = state.table.associate { it.handClass to it.winCountingTiesPercent },
            )
            HandMatrix(
                title = "Pre-flop fold",
                caption = "Weak hands fold pre-flop (below 1 in ${state.totalPlayers})",
                values = state.table.associate { it.handClass to it.winFoldCountingTiesPercent },
            )
            HandMatrix(
                title = "Post-flop fold",
                caption = "Weak hands also fold weak flops (below 1 in ${state.totalPlayers})",
                values = state.table.associate { it.handClass to it.postFoldCountingTiesPercent },
            )
            Text(
                "Break-even ${"%.1f".format(breakEven * 100)}% — black cells are folds",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }

        Spacer(Modifier.height(12.dp))
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

/** A single switch flipping between Offsuit (off) and Suited (on). */
@Composable
private fun SuitedToggle(suited: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SwitchLabel("Offsuit", active = !suited)
        Spacer(Modifier.width(14.dp))
        Switch(checked = suited, onCheckedChange = onChange)
        Spacer(Modifier.width(14.dp))
        SwitchLabel("Suited", active = suited)
    }
}

@Composable
private fun SwitchLabel(label: String, active: Boolean) {
    Text(
        label,
        color = if (active) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        },
        fontWeight = FontWeight.SemiBold,
    )
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

            Spacer(Modifier.height(18.dp))
            PostFoldSplit(result)

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

/**
 * The post-flop-fold win/lose split, shown below the pre-flop-fold split.
 * Survivors of the pre-flop round fold weak flops too (equity below the shrunk
 * break-even 1/remaining), the hero included, averaged over every flop. A hand
 * the hero folds pre-flop never reaches the flop, so it shows "Fold".
 */
@Composable
private fun PostFoldSplit(result: PreflopEquity) {
    val heroFolds = result.winCountingTies < 1.0 / result.players
    if (heroFolds) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Post-flop fold",
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
            title = "Post-flop fold",
            caption = "if weak hands also fold weak flops (averaged over all flops)",
            winPercent = result.postFoldCountingTiesPercent,
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

// --- hand matrix (Table tab) ----------------------------------------------

/**
 * A 13×13 grid of the 169 starting hands for one probability. Cells are
 * color-graded from red (weakest playable) to green (strongest) relative to the
 * min/max of *this* grid, so each metric uses its full range; a folded hand
 * (0%) is black. Row/column headers run A→2, suited hands above the diagonal,
 * offsuit below (the standard poker matrix).
 */
@Composable
private fun HandMatrix(title: String, caption: String, values: Map<String, Double>) {
    val playing = values.values.filter { it > 0.01 }
    val lo = playing.minOrNull() ?: 0.0
    val hi = playing.maxOrNull() ?: 1.0
    val span = (hi - lo).takeIf { it > 1e-6 } ?: 1.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(10.dp))

            val headerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            // Column headers: a spacer for the row-header gutter, then A→2.
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.width(14.dp))
                MATRIX_RANKS.forEach { r ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(r, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = headerColor)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            MATRIX_RANKS.forEachIndexed { row, rowRank ->
                Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.width(14.dp).height(34.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            rowRank,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = headerColor,
                        )
                    }
                    MATRIX_RANKS.forEachIndexed { col, colRank ->
                        val handClass = when {
                            row == col -> "$rowRank$colRank"
                            row < col -> "${MATRIX_RANKS[row]}${MATRIX_RANKS[col]}s"
                            else -> "${MATRIX_RANKS[col]}${MATRIX_RANKS[row]}o"
                        }
                        val pct = values[handClass] ?: 0.0
                        val folded = pct <= 0.01
                        val t = ((pct - lo) / span).toFloat().coerceIn(0f, 1f)
                        MatrixCell(handClass, pct, t, folded)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.MatrixCell(label: String, pct: Double, t: Float, folded: Boolean) {
    val bg = if (folded) Color.Black else gradeColor(t)
    val fg = if (folded) Color(0xFF6A6A6A) else Color.White
    Box(
        modifier = Modifier
            .weight(1f)
            .height(34.dp)
            .padding(1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = fg,
                maxLines = 1,
            )
            Text(
                if (folded) "fold" else "%.0f%%".format(pct),
                fontSize = 7.5.sp,
                color = fg.copy(alpha = 0.9f),
                maxLines = 1,
            )
        }
    }
}

/**
 * Map a normalized strength `t` (0 weakest, 1 strongest) onto the felt-style
 * red→olive→green ramp used by the grid.
 */
private fun gradeColor(t: Float): Color {
    val stops = listOf(
        0.00f to Color(0xFF6E1E12), // dark red
        0.25f to Color(0xFF8A3A16), // red-brown
        0.50f to Color(0xFF8C7A1E), // olive
        0.75f to Color(0xFF5E8A22), // yellow-green
        1.00f to Color(0xFF2E8B34), // green
    )
    val x = t.coerceIn(0f, 1f)
    for (i in 0 until stops.size - 1) {
        val (t0, c0) = stops[i]
        val (t1, c1) = stops[i + 1]
        if (x <= t1) {
            val f = if (t1 > t0) (x - t0) / (t1 - t0) else 0f
            return Color(
                red = c0.red + (c1.red - c0.red) * f,
                green = c0.green + (c1.green - c0.green) * f,
                blue = c0.blue + (c1.blue - c0.blue) * f,
            )
        }
    }
    return stops.last().second
}

// --- sticky footer tabs ----------------------------------------------------

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Tab.entries.forEach { tab ->
                TabItem(tab, selected == tab, onSelect, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: Tab,
    active: Boolean,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    }
    Column(
        modifier = modifier
            .clickable { onSelect(tab) }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(28.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Spacer(Modifier.height(6.dp))
        Text(tab.glyph, fontSize = 18.sp, color = color)
        Text(tab.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}
