package com.maisonsmd.catdrive.lib

import com.maisonsmd.catdrive.BuildConfig

class Intents {
    companion object {
        private const val APP_ID = BuildConfig.APPLICATION_ID

        const val ENABLE_SERVICES = "${APP_ID}.intent.ENABLE_SERVICES"
        const val DISABLE_SERVICES = "${APP_ID}.intent.DISABLE_SERVICES"
        const val BIND_LOCAL_SERVICE = "${APP_ID}.intent.LOCAL_BIND"
        const val BACKGROUND_SERVICE_STATUS = "${APP_ID}.intent.SERVICE_RUNNING"

        const val DISCONNECT_DEVICE = "${APP_ID}.intent.DISCONNECT_DEVICE"
        const val CONNECT_DEVICE = "${APP_ID}.intent.CONNECT_DEVICE"
        const val CONNECTION_UPDATE = "${APP_ID}.intent.CONNECTION_UPDATE"

        const val NAVIGATION_UPDATE = "${APP_ID}.intent.NAVIGATION_UPDATE"
        const val GPS_UPDATE = "${APP_ID}.intent.GPS_UPDATE"

        const val OPEN_NOTIFICATION_LISTENER_SETTINGS =
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"

        const val ACTION_GATT_CONNECTED = "com.maisonsmd.bluetooth.le.ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED = "com.maisonsmd.bluetooth.le.ACTION_GATT_DISCONNECTED"
        const val ACTION_GATT_SERVICES_DISCOVERED =
            "com.maisonsmd.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED"

    }
}
