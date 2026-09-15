package ru.karasevm.privatednstoggle.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object PreferenceHelper {

    const val DNS_SERVERS = "dns_servers"
    const val AUTO_MODE = "auto_mode"
    const val REQUIRE_UNLOCK = "require_unlock"
    const val AUTO_REVERT_ENABLED = "auto_revert_enabled"
    const val AUTO_REVERT_DELAY_SECONDS = "auto_revert_delay_seconds"
    const val AUTO_REVERT_TARGET = "auto_revert_target"
    const val AUTO_REVERT_PENDING_MODE = "auto_revert_pending_mode"
    const val AUTO_REVERT_PENDING_PROVIDER = "auto_revert_pending_provider"

    /**
     * Default auto-revert delay in seconds (5 minutes)
     */
    const val DEFAULT_AUTO_REVERT_DELAY_SECONDS = 300

    fun defaultPreference(context: Context): SharedPreferences =
        context.getSharedPreferences("app_prefs", 0)

    private inline fun SharedPreferences.editMe(operation: (SharedPreferences.Editor) -> Unit) {
        edit {
            operation(this)
        }
    }

    private fun SharedPreferences.Editor.put(pair: Pair<String, Any>) {
        val key = pair.first
        when (val value = pair.second) {
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Boolean -> putBoolean(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            else -> error("Only primitive types can be stored in SharedPreferences, got ${value.javaClass}")
        }
    }

    var SharedPreferences.dns_servers
        get() = getString(DNS_SERVERS, "")!!.split(",").toMutableList()
        set(items) {
            editMe {
                it.put(DNS_SERVERS to items.joinToString(separator = ","))
            }
        }


    var SharedPreferences.autoMode
        get() = getInt(AUTO_MODE, PrivateDNSUtils.AUTO_MODE_OPTION_OFF)
        set(value) {
            editMe {
                it.put(AUTO_MODE to value)
            }
        }

    var SharedPreferences.requireUnlock
        get() = getBoolean(REQUIRE_UNLOCK, false)
        set(value) {
            editMe {
                it.put(REQUIRE_UNLOCK to value)
            }
        }

    var SharedPreferences.autoRevertEnabled
        get() = getBoolean(AUTO_REVERT_ENABLED, false)
        set(value) {
            editMe {
                it.put(AUTO_REVERT_ENABLED to value)
            }
        }

    /**
     * Delay in seconds after which the dns setting is restored
     */
    var SharedPreferences.autoRevertDelaySeconds
        get() = getInt(AUTO_REVERT_DELAY_SECONDS, DEFAULT_AUTO_REVERT_DELAY_SECONDS)
        set(value) {
            editMe {
                it.put(AUTO_REVERT_DELAY_SECONDS to value)
            }
        }

    /**
     * One of [PrivateDNSUtils.AUTO_REVERT_TARGET_PREVIOUS],
     * [PrivateDNSUtils.AUTO_REVERT_TARGET_OFF], [PrivateDNSUtils.AUTO_REVERT_TARGET_AUTO]
     * or [PrivateDNSUtils.AUTO_REVERT_TARGET_FIRST]
     */
    var SharedPreferences.autoRevertTarget
        get() = getInt(AUTO_REVERT_TARGET, PrivateDNSUtils.AUTO_REVERT_TARGET_PREVIOUS)
        set(value) {
            editMe {
                it.put(AUTO_REVERT_TARGET to value)
            }
        }

    /**
     * Dns mode a pending restore will apply, or an empty string when no restore is pending.
     */
    var SharedPreferences.autoRevertPendingMode
        get() = getString(AUTO_REVERT_PENDING_MODE, "")!!
        set(value) {
            editMe {
                it.put(AUTO_REVERT_PENDING_MODE to value)
            }
        }

    /**
     * Dns provider a pending restore will apply, or an empty string when there is none. Only
     * meaningful together with [PrivateDNSUtils.DNS_MODE_PRIVATE].
     */
    var SharedPreferences.autoRevertPendingProvider
        get() = getString(AUTO_REVERT_PENDING_PROVIDER, "")!!
        set(value) {
            editMe {
                it.put(AUTO_REVERT_PENDING_PROVIDER to value)
            }
        }
}
