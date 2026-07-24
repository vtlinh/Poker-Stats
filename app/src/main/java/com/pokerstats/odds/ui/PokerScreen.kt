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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

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

        PlayersCard(state.totalPlayers, viewModel::setPlayers)

        SectionCard(title = "Your Hand") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RankWheel(state.rank1, viewModel::setRank1, Modifier.width(72.dp))
                Spacer(Modifier.width(16.dp))
                RankWheel(state.rank2, viewModel::setRank2, Modifier.width(72.dp))
                Spacer(Modifier.width(20.dp))
                SuitedControl(
                    isPair = state.isPair,
                    suited = state.suited,
                    onChange = viewModel::setSuited,
                )
            }
        }

        state.result?.let { ResultPanel(it) }

        Spacer(Modifier.height(12.dp))
    }
}

/** One metric the Table grid can show, selected by the mode slider. */
private class TableMode(
    val tick: String,   // short label under the slider
    val title: String,  // grid title
    val caption: String,
    val breakEvenColoring: Boolean, // true → break-even ramp; false → rank ramp
    val value: (PreflopEquity) -> Double,
)

private fun tableModes(players: Int) = listOf(
    TableMode(
        "Default", "Win Probability",
        "Chance to win (ties split), all players to showdown", true,
    ) { it.winCountingTiesPercent },
    TableMode(
        "Pre-flop", "Pre-flop fold",
        "Weak hands fold pre-flop (below 1 in $players)", false,
    ) { it.winFoldCountingTiesPercent },
    TableMode(
        "Post-flop", "Post-flop fold",
        "Weak hands also fold weak flops (below 1 in $players)", false,
    ) { it.postFoldCountingTiesPercent },
)

/**
 * The Table tab: one 13×13 grid that fills the screen (no scrolling). A slider
 * picks which metric it shows (Default = Win Probability, then the fold modes).
 */
@Composable
private fun TableTab(state: PokerUiState, viewModel: PokerViewModel) {
    var modeIndex by rememberSaveable { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlayersCard(state.totalPlayers, viewModel::setPlayers)

        if (state.table.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            val modes = tableModes(state.totalPlayers)
            val mode = modes[modeIndex.coerceIn(0, modes.lastIndex)]
            ModeSelector(modeIndex, modes.map { it.tick }) { modeIndex = it }
            HandMatrix(
                modifier = Modifier.weight(1f),
                title = mode.title,
                caption = mode.caption,
                breakEven = if (mode.breakEvenColoring) 100.0 / state.totalPlayers else null,
                values = state.table.associate { it.handClass to mode.value(it) },
            )
        }
    }
}

/** Discrete slider that picks the Table grid's metric, with labels beneath. */
@Composable
private fun ModeSelector(index: Int, labels: List<String>, onChange: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Slider(
                value = index.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = 0f..labels.lastIndex.toFloat(),
                steps = (labels.size - 2).coerceAtLeast(0),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                labels.forEachIndexed { i, label ->
                    Text(
                        label,
                        fontSize = 10.sp,
                        fontWeight = if (i == index) FontWeight.Bold else FontWeight.Normal,
                        color = if (i == index) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        },
                    )
                }
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

/**
 * Suited control shown to the right of the hand wheels: a "Suited" label above a
 * switch (on = suited, off = offsuit). Pairs can't be suited, so they show a
 * muted "Pair" instead. Fixed width so the wheels don't shift between states.
 */
@Composable
private fun SuitedControl(isPair: Boolean, suited: Boolean, onChange: (Boolean) -> Unit) {
    Column(
        modifier = Modifier.width(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isPair) {
            Text(
                "Pair",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        } else {
            Text(
                "Suited",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Switch(checked = suited, onCheckedChange = onChange)
        }
    }
}

/** Compact players card: title + count + info on one row, slider below. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayersCard(count: Int, onChange: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Total Players",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "(Break even: ${"%.1f".format(100.0 / count)}%)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Slider(
                value = count.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = MIN_PLAYERS.toFloat()..MAX_PLAYERS.toFloat(),
                steps = MAX_PLAYERS - MIN_PLAYERS - 1,
                thumb = {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$count",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
            )
        }
    }
}

/** A small circular (?) badge that reveals `text` in a dismissable popup on tap. */
@Composable
private fun InfoTooltip(text: String) {
    var open by remember { mutableStateOf(false) }
    val yOffset = with(LocalDensity.current) { 22.dp.roundToPx() }
    Box {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f))
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "?",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, yOffset),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shadowElevation = 6.dp,
                    modifier = Modifier.widthIn(max = 240.dp),
                ) {
                    Text(
                        text,
                        modifier = Modifier
                            .clickable { open = false }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
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
        Column(Modifier.padding(16.dp)) {
            SplitMetric(
                title = "Win Probability",
                info = "Your chance to win at showdown against random hands, " +
                    "with ties counted as wins.",
                winPercent = result.winCountingTiesPercent,
            )

            Spacer(Modifier.height(12.dp))
            FoldSplit(result)

            Spacer(Modifier.height(12.dp))
            PostFoldSplit(result)

            if (result.categoryFrequency.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                Spacer(Modifier.height(12.dp))
                Text(
                    "Your hand makes",
                    style = MaterialTheme.typography.titleMedium,
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

/** A titled win/lose split: a compact header row (title + optional info) + bar. */
@Composable
private fun SplitMetric(title: String, info: String?, winPercent: Double) {
    val losePercent = (100.0 - winPercent).coerceIn(0.0, 100.0)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (info != null) {
                Spacer(Modifier.width(6.dp))
                InfoTooltip(info)
            }
        }
        Spacer(Modifier.height(6.dp))
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
        FoldedRow(
            "Pre-flop fold",
            "This hand is below break-even (1 in ${result.players}), so you fold it pre-flop.",
        )
    } else {
        SplitMetric(
            title = "Pre-flop fold",
            info = "Chance to win if every hand below break-even (1 in ${result.players}) " +
                "folds pre-flop, thinning the field before the flop.",
            winPercent = result.winFoldCountingTiesPercent,
        )
    }
}

/** Compact "<name> — Fold" row for a hand the hero folds. */
@Composable
private fun FoldedRow(title: String, info: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(6.dp))
            InfoTooltip(info)
        }
        Text(
            "Fold",
            style = MaterialTheme.typography.titleMedium,
            color = LoseRed,
            fontWeight = FontWeight.Bold,
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
        FoldedRow(
            "Post-flop fold",
            "This hand folds pre-flop (below 1 in ${result.players}), so it never sees a flop.",
        )
    } else {
        SplitMetric(
            title = "Post-flop fold",
            info = "Pre-flop survivors also fold weak flops (below 1 in remaining), " +
                "and you fold yours too — averaged over every flop.",
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
 * A 13×13 grid of the 169 starting hands for one probability. Two color modes:
 * when `breakEven` is given (Win Probability), cells use the break-even-anchored
 * eased-HSL ramp (yellow at break-even, green above, red below); otherwise (the
 * fold grids, whose survivors all sit well above break-even) cells use a red→green
 * ramp keyed on each hand's **rank** among the non-folded values (median = yellow),
 * so color spreads evenly even when the values cluster. A folded (0%) hand is
 * black. Row/column headers run A→2, suited above the diagonal, offsuit below.
 */
@Composable
private fun HandMatrix(
    title: String,
    caption: String,
    values: Map<String, Double>,
    breakEven: Double? = null,
    modifier: Modifier = Modifier,
) {
    val sorted = values.values.filter { it > 0.01 }.sorted()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))

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
            // The 13 rows share the remaining height so the grid fills the card.
            MATRIX_RANKS.forEachIndexed { row, rowRank ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    Box(
                        Modifier.width(14.dp).fillMaxHeight(),
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
                        val bg = when {
                            folded -> Color.Black
                            // Win Probability: break-even-anchored (yellow = 1/N).
                            breakEven != null ->
                                oddsColor(log2(pct / breakEven).toFloat().coerceIn(-1f, 1f))
                            // Fold grids: red→green by rank (median = yellow).
                            else -> {
                                val rank = sorted.count { it < pct }
                                val t = if (sorted.size > 1) {
                                    rank.toFloat() / (sorted.size - 1)
                                } else {
                                    0.5f
                                }
                                rampColor(t)
                            }
                        }
                        MatrixCell(handClass, pct, bg, folded)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.MatrixCell(label: String, pct: Double, bg: Color, folded: Boolean) {
    val fg = if (folded) Color(0xFF6A6A6A) else Color.White
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
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
 * Break-even-anchored ramp (after group_iou): yellow (hue 60) at break-even,
 * easing toward green (120) above and red (0) below. `t` is the signed, clamped
 * distance from break-even in [-1, 1]; the eased magnitude `|t|^0.6` also drives
 * saturation up and lightness down, so cells far from break-even read as more
 * vivid and darker.
 */
private fun oddsColor(t: Float): Color {
    val m = abs(t).pow(0.6f)
    val hue = 60f + 60f * sign(t) * m
    return Color.hsl(hue, (62f + 22f * m) / 100f, (38f - 15f * m) / 100f)
}

/** Uniform red→green ramp for `t` in [0, 1]: 0 red, 0.5 yellow-green, 1 green. */
private fun rampColor(t: Float): Color =
    Color.hsl(120f * t.coerceIn(0f, 1f), 0.68f, 0.40f)

// --- sticky footer tabs ----------------------------------------------------

// A lighter slate panel so the footer reads as a raised bar, clearly distinct
// from both the near-black screen and the card green. A light hairline tops it
// off (gold is reserved for the selected tab's indicator).
private val FooterBackground = Color(0xFF2E5B43)

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(FooterBackground)
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
        )
        Row(Modifier.fillMaxWidth()) {
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
