package com.pokerstats.odds.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pokerstats.odds.data.EquityDatabase
import com.pokerstats.odds.data.PreflopEquity
import com.pokerstats.odds.engine.Rank
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

/**
 * Immutable snapshot of the calculator screen.
 *
 * Preflop equity depends only on the two ranks and whether they are suited, so
 * the input is two ranks plus a suited flag rather than concrete cards.
 */
data class PokerUiState(
    val rank1: Rank = Rank.ACE,
    val rank2: Rank = Rank.ACE,
    val suited: Boolean = false,
    val totalPlayers: Int = MIN_PLAYERS,
    val result: PreflopEquity? = null,
    /** Every hand class at the current player count, for the matrix view. */
    val table: List<PreflopEquity> = emptyList(),
    val update: UpdateUiState = UpdateUiState(),
) {
    val isPair: Boolean get() = rank1 == rank2

    /** Canonical starting-hand key, e.g. "AA", "AKs", "AKo" (higher rank first). */
    val handClass: String
        get() {
            if (rank1 == rank2) return "${rank1.symbol}${rank2.symbol}"
            val hi = if (rank1.value >= rank2.value) rank1 else rank2
            val lo = if (rank1.value >= rank2.value) rank2 else rank1
            return "${hi.symbol}${lo.symbol}${if (suited) "s" else "o"}"
        }
}

class PokerViewModel(app: Application) : AndroidViewModel(app) {

    private val equityDb = EquityDatabase(app)

    private val _uiState = MutableStateFlow(PokerUiState())
    val uiState: StateFlow<PokerUiState> = _uiState.asStateFlow()

    init {
        refreshResult()
        refreshTable()
    }

    // --- calculator --------------------------------------------------------

    fun setRank1(rank: Rank) {
        _uiState.update { it.copy(rank1 = rank) }
        refreshResult()
    }

    fun setRank2(rank: Rank) {
        _uiState.update { it.copy(rank2 = rank) }
        refreshResult()
    }

    fun setSuited(suited: Boolean) {
        _uiState.update { it.copy(suited = suited) }
        refreshResult()
    }

    fun setPlayers(count: Int) {
        _uiState.update { it.copy(totalPlayers = count.coerceIn(MIN_PLAYERS, MAX_PLAYERS)) }
        refreshResult()
        refreshTable()
    }

    private fun refreshResult() {
        val handClass = _uiState.value.handClass
        val players = _uiState.value.totalPlayers
        viewModelScope.launch {
            val equity = withContext(Dispatchers.IO) { equityDb.lookup(handClass, players) }
            // Ignore stale lookups if the hand changed while querying.
            _uiState.update { cur ->
                if (cur.handClass == handClass && cur.totalPlayers == players) cur.copy(result = equity)
                else cur
            }
        }
    }

    private fun refreshTable() {
        val players = _uiState.value.totalPlayers
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { equityDb.lookupAll(players) }
            // Ignore stale results if the player count changed while querying.
            _uiState.update { cur ->
                if (cur.totalPlayers == players) cur.copy(table = rows) else cur
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
