package com.maisonsmd.catdrive.ui

import android.bluetooth.BluetoothDevice
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.maisonsmd.catdrive.lib.NavigationData

class ActivityViewModel : ViewModel() {
    val permissionUpdatedTimestamp = MutableLiveData<Long>().apply { value = 0 }
    val navigationData = MutableLiveData<NavigationData>().apply { value = NavigationData() }
    val speed = MutableLiveData<Int>().apply { value = 0 }
    val connectedDevice = MutableLiveData<BluetoothDevice?>().apply { value = null }
    val serviceRunInBackground = MutableLiveData<Boolean>().apply { value = false }
}