package io.github.vvb2060.ims.tiles

import android.service.quicksettings.Tile
import android.util.Log
import android.widget.Toast
import io.github.vvb2060.ims.R
import io.github.vvb2060.ims.ShizukuProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

abstract class BaseNetworkModeTileService : BaseSimTileService() {
    companion object {
        private const val TAG = "TurboIMS-NetworkTile"
        // Shared by instances so a recreated TileService cannot queue a second toggle.
        private val slotLocks = arrayOf(Mutex(), Mutex())
    }

    private val operationLock get() = slotLocks[simSlotIndex]

    override fun onStartListening() {
        super.onStartListening()
        launch { operationLock.withLock { refreshTile() } }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { startToggle() } else startToggle()
    }

    private fun startToggle() {
        if (!isShizukuReady()) {
            openMainActivity()
            return
        }
        if (!operationLock.tryLock()) return
        launch {
            val subId = resolveSubId()
            if (subId == null) {
                updateTileState(Tile.STATE_UNAVAILABLE, getString(R.string.qs_network_no_sim))
                return@launch
            }
            updateTileState(Tile.STATE_UNAVAILABLE, getString(R.string.qs_network_switching))
            val result = ShizukuProvider.networkMode(applicationContext, subId, simSlotIndex, toggle = true)
            if (result.errorMessage != null) {
                Log.w(TAG, "SIM slot $simSlotIndex toggle failed: ${result.errorMessage}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, R.string.qs_network_switch_failed, Toast.LENGTH_LONG).show()
                }
                // Read the actual preference even if the write's result was uncertain.
                refreshTile()
            } else {
                showResult(result)
            }
        }.invokeOnCompletion { operationLock.unlock() }
    }

    private suspend fun refreshTile() {
        if (!isShizukuReady()) {
            updateTileState(Tile.STATE_UNAVAILABLE, getString(R.string.qs_network_needs_shizuku))
            return
        }
        val subId = resolveSubId()
        if (subId == null) {
            updateTileState(Tile.STATE_UNAVAILABLE, getString(R.string.qs_network_no_sim))
            return
        }
        showResult(ShizukuProvider.networkMode(applicationContext, subId, simSlotIndex))
    }

    private suspend fun showResult(result: ShizukuProvider.Companion.NetworkModeResult) {
        result.errorMessage?.let { Log.w(TAG, "SIM slot $simSlotIndex read failed: $it") }
        when (result.is5gAllowed) {
            true -> updateTileState(Tile.STATE_ACTIVE, getString(R.string.qs_network_5g_allowed))
            false -> updateTileState(Tile.STATE_INACTIVE, getString(R.string.qs_network_5g_disabled))
            null -> updateTileState(Tile.STATE_UNAVAILABLE, getString(
                if (result.unsupported) R.string.qs_network_unsupported else R.string.qs_network_read_failed
            ))
        }
    }
}

class SIM1NetworkModeTileService : BaseNetworkModeTileService() {
    override val simSlotIndex = 0
}

class SIM2NetworkModeTileService : BaseNetworkModeTileService() {
    override val simSlotIndex = 1
}
