package com.pokerstats.odds.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pokerstats.odds.data.EquityDatabase
import com.pokerstats.odds.data.PreflopEquity
import com.pokerstats.odds.update.UpdateInfo
import com.pokerstats.odds.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

const val MIN_PLAYERS = 2
const val MAX_PLAYERS = 6

/** State of the in-app updater. */
data class UpdateUiState(
    val available: UpdateInfo? = null,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val dismissed: Boolean = false,
    val readyToInstall: File? = null,
) {
    val showBanner: Boolean get() = available != null && !dismissed
}

/** Immutable snapshot of the calculator screen. */
data class PokerUiState(
    val hole: List<com.pokerstats.odds.engine.Card> = emptyList(),
    val totalPlayers: Int = MIN_PLAYERS,
    val result: PreflopEquity? = null,
    val update: UpdateUiState = UpdateUiState(),
) {
    val usedCards: Set<com.pokerstats.odds.engine.Card> get() = hole.toSet()
    val heroComplete: Boolean get() = hole.size == 2
}

class PokerViewModel(app: Application) : AndroidViewModel(app) {

    private val equityDb = EquityDatabase(app)

    private val _uiState = MutableStateFlow(PokerUiState())
    val uiState: StateFlow<PokerUiState> = _uiState.asStateFlow()

    // --- calculator --------------------------------------------------------

    fun addCard(card: com.pokerstats.odds.engine.Card) {
        _uiState.update { state ->
            if (state.hole.size >= 2 || state.usedCards.contains(card)) state
            else state.copy(hole = state.hole + card)
        }
        refreshResult()
    }

    fun removeCard(card: com.pokerstats.odds.engine.Card) {
        _uiState.update { it.copy(hole = it.hole - card) }
        refreshResult()
    }

    fun setPlayers(count: Int) {
        _uiState.update { it.copy(totalPlayers = count.coerceIn(MIN_PLAYERS, MAX_PLAYERS)) }
        refreshResult()
    }

    fun reset() {
        _uiState.update { it.copy(hole = emptyList(), result = null) }
    }

    private fun refreshResult() {
        val state = _uiState.value
        if (state.hole.size != 2) {
            if (state.result != null) _uiState.update { it.copy(result = null) }
            return
        }
        val a = state.hole[0]
        val b = state.hole[1]
        val players = state.totalPlayers
        viewModelScope.launch {
            val equity = withContext(Dispatchers.IO) { equityDb.lookup(a, b, players) }
            // Ignore stale lookups if the hand changed while querying.
            _uiState.update { cur ->
                if (cur.hole == listOf(a, b) && cur.totalPlayers == players) cur.copy(result = equity)
                else cur
            }
        }
    }

    // --- updater -----------------------------------------------------------

    /** Clean up stale downloads and look for a newer release on launch. */
    fun onStart() {
        UpdateManager.cleanupOldDownloads(getApplication())
        viewModelScope.launch {
            val info = UpdateManager.checkForUpdate() ?: return@launch
            _uiState.update { it.copy(update = it.update.copy(available = info)) }
        }
    }

    fun dismissUpdate() {
        _uiState.update { it.copy(update = it.update.copy(dismissed = true)) }
    }

    fun startUpdate(context: Context) {
        val info = _uiState.value.update.available ?: return
        if (_uiState.value.update.downloading) return
        viewModelScope.launch {
            _uiState.update { it.copy(update = it.update.copy(downloading = true, progress = 0f)) }
            val apk = try {
                UpdateManager.downloadApk(getApplication(), info) { p ->
                    _uiState.update { it.copy(update = it.update.copy(progress = p)) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(update = it.update.copy(downloading = false)) }
                return@launch
            }
            _uiState.update {
                it.copy(update = it.update.copy(downloading = false, readyToInstall = apk))
            }
            if (UpdateManager.canInstall(context)) {
                UpdateManager.installApk(context, apk)
            } else {
                UpdateManager.requestInstallPermission(context)
            }
        }
    }
}
