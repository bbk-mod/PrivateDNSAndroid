package ru.karasevm.privatednstoggle.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.karasevm.privatednstoggle.util.AutoRevertManager

/**
 * Receives the auto-revert alarm and the auto-revert notification actions.
 */
class AutoRevertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                when (intent.action) {
                    AutoRevertManager.ACTION_AUTO_REVERT,
                    AutoRevertManager.ACTION_RESTORE_NOW ->
                        AutoRevertManager.performRevert(context, intent)

                    AutoRevertManager.ACTION_KEEP ->
                        AutoRevertManager.cancel(context)
                }
            } catch (exception: Exception) {
                Log.e(TAG, "auto revert failed", exception)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AutoRevertReceiver"
    }
}
