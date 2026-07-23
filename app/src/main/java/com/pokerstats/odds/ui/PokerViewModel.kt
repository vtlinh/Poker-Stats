package com.pokerstats.odds.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pokerstats.odds.engine.Card
import com.pokerstats.odds.engine.EquityCalculator
import com.pokerstats.odds.engine.EquityResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which group of cards the picker is currently filling. */
enum class CardSlot { HERO, BOARD }

/** Immutable snapshot of the calculator screen. */
data class PokerUiState(
    val hole: List<Card> = emptyList(),
    val board: List<Card> = emptyList(),
    val opponents: Int = 1,
    val isCalculating: Boolean = false,
    val progress: Float = 0f,
    val result: EquityResult? = null,
) {
    val usedCards: Set<Card> get() = (hole + board).toSet()
    val canCalculate: Boolean get() = hole.size == 2 && !isCalculating
    val heroComplete: Boolean get() = hole.size == 2
}

class PokerViewModel(
    private val calculator: EquityCalculator = EquityCalculator(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(PokerUiState())
    val uiState: StateFlow<PokerUiState> = _uiState.asStateFlow()

    private var simulationJob: Job? = null

    /** Add [card] to the given [slot] if there is room, otherwise ignore. */
    fun addCard(card: Card, slot: CardSlot) {
        _uiState.update { state ->
            if (state.usedCards.contains(card)) return@update state
            when (slot) {
                CardSlot.HERO -> if (state.hole.size < 2) {
                    state.copy(hole = state.hole + card, result = null)
                } else state
                CardSlot.BOARD -> if (state.board.size < 5) {
                    state.copy(board = state.board + card, result = null)
                } else state
            }
        }
    }

    fun removeCard(card: Card) {
        _uiState.update { state ->
            state.copy(
                hole = state.hole - card,
                board = state.board - card,
                result = null,
            )
        }
    }

    fun setOpponents(count: Int) {
        _uiState.update { it.copy(opponents = count.coerceIn(1, 9), result = null) }
    }

    fun reset() {
        simulationJob?.cancel()
        _uiState.value = PokerUiState()
    }

    fun calculate() {
        val state = _uiState.value
        if (!state.canCalculate) return
        simulationJob?.cancel()
        simulationJob = viewModelScope.launch {
            _uiState.update { it.copy(isCalculating = true, progress = 0f, result = null) }
            val result = withContext(Dispatchers.Default) {
                calculator.simulate(
                    hole = state.hole,
                    board = state.board,
                    opponents = state.opponents,
                    onProgress = { p ->
                        _uiState.update { it.copy(progress = p) }
                    },
                )
            }
            _uiState.update { it.copy(isCalculating = false, progress = 1f, result = result) }
        }
    }
}
