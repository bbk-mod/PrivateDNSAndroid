package ru.karasevm.privatednstoggle.ui

import android.Manifest
import android.app.AlarmManager
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.databinding.DialogOptionsBinding
import ru.karasevm.privatednstoggle.util.AutoRevertManager
import ru.karasevm.privatednstoggle.util.PreferenceHelper
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoMode
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertDelaySeconds
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertEnabled
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertTarget
import ru.karasevm.privatednstoggle.util.PreferenceHelper.requireUnlock
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils

class OptionsDialogFragment : DialogFragment() {
    private var _binding: DialogOptionsBinding? = null
    private val binding get() = _binding!!
    private val sharedPreferences by lazy { PreferenceHelper.defaultPreference(requireContext()) }

    private val autoRevertDelays = intArrayOf(10, 30, 60, 300, 900, 1800, 3600)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && context != null) {
                Toast.makeText(
                    context, R.string.auto_revert_notifications_disabled, Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreateDialog(
        savedInstanceState: Bundle?
    ): Dialog {
        return activity?.let {
            val builder = MaterialAlertDialogBuilder(it)
            val inflater = requireActivity().layoutInflater
            _binding = DialogOptionsBinding.inflate(inflater)

            val view = binding.root
            builder.setTitle(R.string.options)
                .setView(view)
                .setPositiveButton(R.string.ok, null)
            builder.create()

        } ?: throw IllegalStateException("Activity cannot be null")
    }

    override fun onStart() {
        super.onStart()
        val autoModeOption = sharedPreferences.autoMode
        when (autoModeOption) {
            PrivateDNSUtils.AUTO_MODE_OPTION_OFF -> binding.autoOptionRadioGroup.check(R.id.autoOptionOff)
            PrivateDNSUtils.AUTO_MODE_OPTION_AUTO -> binding.autoOptionRadioGroup.check(R.id.autoOptionAuto)
            PrivateDNSUtils.AUTO_MODE_OPTION_OFF_AUTO -> binding.autoOptionRadioGroup.check(R.id.autoOptionOffAuto)
            PrivateDNSUtils.AUTO_MODE_OPTION_PRIVATE -> binding.autoOptionRadioGroup.check(R.id.autoOptionPrivate)
        }
        binding.autoOptionRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.autoOptionOff -> sharedPreferences.autoMode = PrivateDNSUtils.AUTO_MODE_OPTION_OFF
                R.id.autoOptionAuto -> sharedPreferences.autoMode = PrivateDNSUtils.AUTO_MODE_OPTION_AUTO
                R.id.autoOptionOffAuto -> sharedPreferences.autoMode =
                    PrivateDNSUtils.AUTO_MODE_OPTION_OFF_AUTO

                R.id.autoOptionPrivate -> sharedPreferences.autoMode =
                    PrivateDNSUtils.AUTO_MODE_OPTION_PRIVATE
            }
        }

        val requireUnlock = sharedPreferences.requireUnlock
        binding.requireUnlockSwitch.isChecked = requireUnlock
        binding.requireUnlockSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.requireUnlock = isChecked
        }

        setUpAutoRevert()
    }

    private fun setUpAutoRevert() {
        setUpAutoRevertDelay()

        val autoRevertTarget = sharedPreferences.autoRevertTarget
        when (autoRevertTarget) {
            PrivateDNSUtils.AUTO_REVERT_TARGET_PREVIOUS -> binding.autoRevertTargetRadioGroup.check(R.id.autoRevertTargetPrevious)
            PrivateDNSUtils.AUTO_REVERT_TARGET_OFF -> binding.autoRevertTargetRadioGroup.check(R.id.autoRevertTargetOff)
            PrivateDNSUtils.AUTO_REVERT_TARGET_AUTO -> binding.autoRevertTargetRadioGroup.check(R.id.autoRevertTargetAuto)
            PrivateDNSUtils.AUTO_REVERT_TARGET_FIRST -> binding.autoRevertTargetRadioGroup.check(R.id.autoRevertTargetFirst)
        }
        binding.autoRevertTargetRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.autoRevertTargetPrevious -> sharedPreferences.autoRevertTarget =
                    PrivateDNSUtils.AUTO_REVERT_TARGET_PREVIOUS

                R.id.autoRevertTargetOff -> sharedPreferences.autoRevertTarget =
                    PrivateDNSUtils.AUTO_REVERT_TARGET_OFF

                R.id.autoRevertTargetAuto -> sharedPreferences.autoRevertTarget =
                    PrivateDNSUtils.AUTO_REVERT_TARGET_AUTO

                R.id.autoRevertTargetFirst -> sharedPreferences.autoRevertTarget =
                    PrivateDNSUtils.AUTO_REVERT_TARGET_FIRST
            }
        }

        val autoRevertEnabled = sharedPreferences.autoRevertEnabled
        binding.autoRevertSwitch.isChecked = autoRevertEnabled
        binding.autoRevertSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.autoRevertEnabled = isChecked
            updateAutoRevertEnabledState(isChecked)
            if (isChecked) {
                requestNotificationPermissionIfNeeded()
                requestExactAlarmPermissionIfNeeded()
            } else {
                AutoRevertManager.cancel(requireContext())
            }
        }
        updateAutoRevertEnabledState(autoRevertEnabled)
    }

    private fun setUpAutoRevertDelay() {
        val labels = autoRevertDelays.map { formatDelay(it) }
        binding.autoRevertDelay.setSimpleItems(labels.toTypedArray())

        var index = autoRevertDelays.indexOf(sharedPreferences.autoRevertDelaySeconds)
        if (index < 0) {
            index = autoRevertDelays.indexOf(PreferenceHelper.DEFAULT_AUTO_REVERT_DELAY_SECONDS)
                .coerceAtLeast(0)
        }
        binding.autoRevertDelay.setText(labels[index], false)
        binding.autoRevertDelay.setOnItemClickListener { _, _, position, _ ->
            sharedPreferences.autoRevertDelaySeconds = autoRevertDelays[position]
        }
    }

    private fun formatDelay(seconds: Int): String {
        return when {
            seconds < 60 -> resources.getQuantityString(
                R.plurals.auto_revert_delay_seconds, seconds, seconds
            )

            seconds % 3600 == 0 -> {
                val hours = seconds / 3600
                resources.getQuantityString(R.plurals.auto_revert_delay_hours, hours, hours)
            }

            else -> {
                val minutes = seconds / 60
                resources.getQuantityString(R.plurals.auto_revert_delay_minutes, minutes, minutes)
            }
        }
    }

    private fun updateAutoRevertEnabledState(enabled: Boolean) {
        binding.autoRevertDescription.isEnabled = enabled
        binding.autoRevertDelayLayout.isEnabled = enabled
        binding.autoRevertTargetHeader.isEnabled = enabled
        for (index in 0 until binding.autoRevertTargetRadioGroup.childCount) {
            binding.autoRevertTargetRadioGroup.getChildAt(index).isEnabled = enabled
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Without the exact alarm special access the restore is deferred while the app is not running,
     * so send the user to grant it.
     */
    private fun requestExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        val alarmManager = requireContext().getSystemService(AlarmManager::class.java)
        if (alarmManager.canScheduleExactAlarms()) {
            return
        }
        Toast.makeText(context, R.string.auto_revert_exact_alarm_hint, Toast.LENGTH_LONG).show()
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    "package:${requireContext().packageName}".toUri()
                )
            )
        }
    }
}
