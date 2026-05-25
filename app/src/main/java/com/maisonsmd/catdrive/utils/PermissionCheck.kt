package com.maisonsmd.catdrive.utils

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.maisonsmd.catdrive.GoogleMapNotificationListener
import com.maisonsmd.catdrive.lib.Intents
import timber.log.Timber

class PermissionCheck {
    companion object {
        fun checkBluetoothConnectPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        fun checkNotificationsAccessPermission(context: Context): Boolean {
            val listeners = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            )
            return listeners != null && GoogleMapNotificationListener::class.qualifiedName.toString() in listeners
        }

        fun checkNotificationPostingPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        fun checkLocationAccessPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun checkBluetoothPermissions(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            }
        }

        fun allPermissionsGranted(context: Context): Boolean {
            return checkNotificationsAccessPermission(context)
                    && checkNotificationPostingPermission(context)
                    && checkLocationAccessPermission(context)
                    && checkBluetoothPermissions(context)
        }

        fun requestLocationAccessPermission(activity: AppCompatActivity) {
            if (checkLocationAccessPermission(activity)) return
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                100
            )
        }

        fun requestNotificationAccessPermission(activity: AppCompatActivity) {
            @Suppress("DEPRECATION") activity.startActivityForResult(
                Intent(Intents.OPEN_NOTIFICATION_LISTENER_SETTINGS),
                0
            )
        }

        fun requestBluetoothAccessPermissions(activity: AppCompatActivity) {
            if (checkBluetoothPermissions(activity)) return

            // Fordert auf Android 12+ (API 31+) die neuen Berechtigungen an
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    ),
                    101
                )
            } else {
                // Fallback für ältere Android Versionen (bis Android 11)
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN
                    ),
                    101
                )
            }
        }

        @SuppressLint("MissingPermission")
        fun getDeviceNameSafe(context: Context, device: BluetoothDevice?): String {
            if (device == null) return "No device"
            return if (checkBluetoothConnectPermission(context)) {
                device.name ?: "Unknown Device"
            } else {
                "Permission Required"
            }
        }

        @SuppressLint("MissingPermission")
        fun isBluetoothEnabled(context: Context): Boolean {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter ?: return false
            return adapter.isEnabled
        }

        @SuppressLint("MissingPermission")
        fun requestEnableBluetooth(activity: AppCompatActivity) {
            if (!checkBluetoothPermissions(activity)) {
                Timber.e("No bluetooth permission!!!")
                return
            }

            if (isBluetoothEnabled(activity.applicationContext))
                return

            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            @Suppress("DEPRECATION")
            activity.startActivityForResult(enableBtIntent, 102)
        }
    }
}