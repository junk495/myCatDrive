package com.maisonsmd.catdrive.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.preference.*
import com.maisonsmd.catdrive.MainActivity
import com.maisonsmd.catdrive.R
import com.maisonsmd.catdrive.SHARED_PREFERENCES_FILE
import com.maisonsmd.catdrive.ui.ActivityViewModel
import com.maisonsmd.catdrive.utils.PermissionCheck
import com.maisonsmd.catdrive.utils.ServiceManager
import com.maisonsmd.catdrive.utils.getParcelableExtraCompat

class SettingsFragment : PreferenceFragmentCompat() {
    private lateinit var mServiceEnableSwitch: SwitchPreference
    private lateinit var mAccessNotificationCheckbox: CheckBoxPreference
    private lateinit var mPostNotificationCheckbox: CheckBoxPreference
    private lateinit var mAccessLocationCheckbox: CheckBoxPreference
    private lateinit var mAccessBluetoothCheckbox: CheckBoxPreference
    private lateinit var mConnectDeviceButton: Preference
    private lateinit var mDisplayLightThemeSwitch: SwitchPreference
    private lateinit var mDisplayBrightnessSlider: SeekBarPreference
    private lateinit var mSpeedLimitEdit: EditTextPreference
    private lateinit var mClearIconCacheButton: Preference

    private lateinit var mSharedPref: SharedPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
    }

    override fun onResume() {
        super.onResume()
        refreshSettings()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val viewModel = ViewModelProvider(requireActivity())[ActivityViewModel::class.java]

        viewModel.permissionUpdatedTimestamp.observe(viewLifecycleOwner) {
            refreshSettings()
        }

        viewModel.connectedDevice.observe(viewLifecycleOwner) {
            if (PermissionCheck.checkBluetoothPermissions(requireContext())) {
                val deviceName = PermissionCheck.getDeviceNameSafe(requireContext(), it)
                mConnectDeviceButton.summary = if (it !== null) "Connected to $deviceName" else "No device connected"
            } else {
                mConnectDeviceButton.summary = "Please check bluetooth permissions!"
            }
        }

        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = activity as MainActivity

        mServiceEnableSwitch = preferenceScreen.findPreference("enable_service")!!
        mAccessNotificationCheckbox = preferenceScreen.findPreference("access_notification")!!
        mPostNotificationCheckbox = preferenceScreen.findPreference("post_notification")!!
        mAccessLocationCheckbox = preferenceScreen.findPreference("access_location")!!
        mAccessBluetoothCheckbox = preferenceScreen.findPreference("access_bluetooth")!!
        mConnectDeviceButton = preferenceScreen.findPreference("connect_device")!!
        mDisplayLightThemeSwitch = preferenceScreen.findPreference("display_light_theme")!!
        mDisplayBrightnessSlider = preferenceScreen.findPreference("display_brightness")!!
        mSpeedLimitEdit = preferenceScreen.findPreference("speed_warning_limit")!!
        mClearIconCacheButton = preferenceScreen.findPreference("clear_icon_cache")!!

        mSharedPref =
            mainActivity.getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE)

        refreshSettings()

        mServiceEnableSwitch.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) ServiceManager.startBroadcastService(mainActivity)
            else ServiceManager.stopBroadcastService(mainActivity)
            return@setOnPreferenceChangeListener true
        }

        mAccessNotificationCheckbox.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) PermissionCheck.requestNotificationAccessPermission(activity as MainActivity)
            return@setOnPreferenceChangeListener false
        }

        mAccessLocationCheckbox.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) PermissionCheck.requestLocationAccessPermission(activity as MainActivity)
            return@setOnPreferenceChangeListener false
        }

        mAccessBluetoothCheckbox.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) PermissionCheck.requestBluetoothAccessPermissions(activity as MainActivity)
            return@setOnPreferenceChangeListener false
        }

        mConnectDeviceButton.setOnPreferenceClickListener { _ ->
            (activity as MainActivity).openDeviceSelectionActivity()
            return@setOnPreferenceClickListener false
        }

        mClearIconCacheButton.setOnPreferenceClickListener { _ ->
            (activity as MainActivity).requestClearIconCache()
            Toast.makeText(context, "Clear cache request sent", Toast.LENGTH_SHORT).show()
            return@setOnPreferenceClickListener false
        }

        mDisplayLightThemeSwitch.setOnPreferenceChangeListener { _, newValue ->
            with(mSharedPref.edit()) {
                putBoolean("display_light_theme", (newValue as Boolean))
                apply()
            }
            return@setOnPreferenceChangeListener true
        }

        mDisplayBrightnessSlider.setOnPreferenceChangeListener { _, newValue ->
            with(mSharedPref.edit()) {
                putInt("display_brightness", newValue as Int)
                apply()
            }
            mDisplayBrightnessSlider.summary = newValue.toString()
            return@setOnPreferenceChangeListener true
        }

        mSpeedLimitEdit.setOnPreferenceChangeListener { _, newValue ->
            mSpeedLimitEdit.summary = (newValue as String) + " km/h"
            with(mSharedPref.edit()) {
                putInt("speed_limit", (newValue).toInt())
                apply()
            }
            return@setOnPreferenceChangeListener true
        }
    }

    private fun refreshSettings() {
        val context = requireContext()

        mServiceEnableSwitch.isEnabled = PermissionCheck.allPermissionsGranted(context)
        mServiceEnableSwitch.isChecked =
            ServiceManager.isBroadcastServiceRunningInBackground(activity as MainActivity)

        mDisplayLightThemeSwitch.isChecked = mSharedPref.getBoolean("display_light_theme", true)
        mDisplayBrightnessSlider.value = mSharedPref.getInt("display_brightness", 0)
        mDisplayBrightnessSlider.summary = mDisplayBrightnessSlider.value.toString()
        mSpeedLimitEdit.summary = mSharedPref.getInt("speed_limit", 0).toString() + " km/h"

        PermissionCheck.checkNotificationsAccessPermission(context).also {
            mAccessNotificationCheckbox.isEnabled = !it
            mAccessNotificationCheckbox.isChecked = it
        }
        PermissionCheck.checkNotificationPostingPermission(context).also {
            mPostNotificationCheckbox.isEnabled = !it
            mPostNotificationCheckbox.isChecked = it
        }
        PermissionCheck.checkLocationAccessPermission(context).also {
            mAccessLocationCheckbox.isEnabled = !it
            mAccessLocationCheckbox.isChecked = it
        }
        PermissionCheck.checkBluetoothPermissions(context).also {
            mAccessBluetoothCheckbox.isEnabled = !it
            mAccessBluetoothCheckbox.isChecked = it
        }
    }
}