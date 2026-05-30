package com.maisonsmd.catdrive.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Size
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.maisonsmd.catdrive.MainActivity
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import com.maisonsmd.catdrive.R
import com.maisonsmd.catdrive.SHARED_PREFERENCES_FILE
import com.maisonsmd.catdrive.lib.BitmapHelper
import com.maisonsmd.catdrive.lib.BleCharacteristics
import com.maisonsmd.catdrive.lib.BleWriteQueue
import com.maisonsmd.catdrive.lib.BleWriteQueue.QueueItem
import com.maisonsmd.catdrive.lib.Intents
import com.maisonsmd.catdrive.lib.NavigationData
import com.maisonsmd.catdrive.lib.NavigationIcon
import com.maisonsmd.catdrive.utils.PermissionCheck
import com.maisonsmd.catdrive.utils.getParcelableExtraCompat
import kotlinx.coroutines.*
import locus.api.android.ActionBasics
import locus.api.android.features.periodicUpdates.UpdateContainer
import locus.api.android.utils.LocusUtils
import locus.api.objects.Storable
import locus.api.objects.extra.PointRteAction
import locus.api.utils.DataReaderBigEndian
import timber.log.Timber
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import kotlin.math.ceil


@SuppressLint("MissingPermission")
class BleService : Service(), LocationListener {
    companion object {
        private const val NOTIFICATION_ID = 1201
    }

    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods
        fun getService(): BleService = this@BleService
    }


    private lateinit var mAdapter: BluetoothAdapter
    private var mNotificationBuilder: Notification.Builder? = null
    private var mRunInBackground: Boolean = false
    private var mReconnectTimer: Timer? = null
    private var mConnectionState = BluetoothProfile.STATE_DISCONNECTED
    private var mDevice: BluetoothDevice? = null
    private var mBluetoothGatt: BluetoothGatt? = null
    private val mBinder = LocalBinder()
    private var mLastNavigationData: NavigationData? = null
    private var mDataWriteQueue: BleWriteQueue = BleWriteQueue()
    private var mIsSending: Boolean = false
    private var mIconMap: MutableMap<String, ByteArray> = mutableMapOf()

    val connectedDevice: BluetoothDevice?
        get() = mDevice

    var runInBackground
        get() = mRunInBackground
        private set(value) {
            if (mRunInBackground == value)
                return
            mRunInBackground = value
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                Intent(Intents.BACKGROUND_SERVICE_STATUS).apply {
                    putExtra("service", this::class.java.simpleName)
                    putExtra("run_in_background", value)
                }
            )
        }

    inner class ReconnectTask : TimerTask() {
        override fun run() {
            Timber.d("reconnect timer elapsed")
            if (mConnectionState != BluetoothProfile.STATE_DISCONNECTED) {
                return
            }

            Timber.d("Trying to connect to last device...")
            if (PermissionCheck.isBluetoothEnabled(applicationContext)) {
                val sp = applicationContext.getSharedPreferences(
                    SHARED_PREFERENCES_FILE,
                    Context.MODE_PRIVATE
                )
                val address = sp.getString("last_device_address", null)
                if (address != null) {
                    val device = mAdapter.getRemoteDevice(address)
                    if (device != null) {
                        connect(device)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Bind by activity
        if (intent?.action == Intents.BIND_LOCAL_SERVICE) {
            return mBinder
        }
        // Bind by OS
        return null
    }

    private var mPollingJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        mAdapter =
            (applicationContext.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        super.onCreate()
        Timber.i("onCreate")

        // Starte die Locus-Abfrageschleife (Polling)
        startLocusPolling()
    }

    private fun startLocusPolling() {
        mPollingJob?.cancel()
        @OptIn(DelicateCoroutinesApi::class)
        mPollingJob = GlobalScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    // Frage Locus Map direkt ab (Weg B aus deinem Dokument)
                    val lv = LocusUtils.getActiveVersion(applicationContext)
                    if (lv != null) {
                        val container = ActionBasics.getUpdateContainer(applicationContext, lv)
                        if (container != null) {
                            processUpdateContainer(container)
                        }
                    }
                } catch (e: Exception) {
                    // Timber.e(e, "Fehler beim Polling von Locus")
                }
                delay(500) // 2x pro Sekunde abfragen
            }
        }
    }

    private fun processUpdateContainer(container: UpdateContainer) {
        // Extrahiere Navigationspunkt (nächste Abbiegung)
        val nextRoad = container.guideNavPoint1Name ?: ""
        val distance = container.guideNavPoint1Dist
        
        // Extrahiere Pfeil-Aktion
        val action = container.guideNavPoint1Action
        
        // Extrahiere Gesamtdistanz zum Ziel
        val totalDistance = container.guideDistToFinish

        // Distanz zur nächsten Abbiegung formatieren
        val distanceStr = when {
            distance < 0 -> ""
            distance >= 1000 -> String.format(java.util.Locale.US, "%.1f km", distance / 1000.0)
            else -> "${distance.toInt()} m"
        }
        
        // Gesamtdistanz formatieren
        val totalDistanceStr = when {
            totalDistance < 0 -> ""
            totalDistance >= 1000 -> String.format(java.util.Locale.US, "%.1f km", totalDistance / 1000.0)
            else -> "${totalDistance.toInt()} m"
        }

        // Ankunftszeit berechnen (ETA) und Fahrzeit (ETE) formatieren
        val eteMillis = container.guideTimeToFinish
        var etaStr = ""
        var eteStr = ""
        
        if (eteMillis > 0) {
            val arrivalTimestamp = System.currentTimeMillis() + eteMillis
            etaStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(arrivalTimestamp))
            
            val totalSeconds = eteMillis / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            eteStr = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        }

        val navData = com.maisonsmd.catdrive.lib.NavigationData().apply {
            nextDirection = com.maisonsmd.catdrive.lib.NavigationDirection(
                nextRoad = nextRoad, 
                distance = distanceStr
            )
            this.eta = com.maisonsmd.catdrive.lib.NavigationEta(
                eta = etaStr,
                ete = eteStr,
                distance = totalDistanceStr 
            )
            
            // Map Locus Action to Icon
            val iconRes = when (action) {
                PointRteAction.LEFT, PointRteAction.LEFT_SHARP -> R.drawable.turn_left
                PointRteAction.LEFT_SLIGHT, PointRteAction.STAY_LEFT -> R.drawable.turn_slight_left
                
                PointRteAction.RIGHT, PointRteAction.RIGHT_SHARP -> R.drawable.turn_right
                PointRteAction.RIGHT_SLIGHT, PointRteAction.STAY_RIGHT -> R.drawable.turn_slight_right
                
                PointRteAction.U_TURN_LEFT, PointRteAction.U_TURN -> R.drawable.u_turn_left
                PointRteAction.U_TURN_RIGHT -> R.drawable.u_turn_right
                
                PointRteAction.CONTINUE_STRAIGHT, PointRteAction.STAY_STRAIGHT -> android.R.drawable.ic_menu_upload
                
                PointRteAction.ROUNDABOUT_EXIT_1, PointRteAction.ROUNDABOUT_EXIT_2, 
                PointRteAction.ROUNDABOUT_EXIT_3, PointRteAction.ROUNDABOUT_EXIT_4,
                PointRteAction.ROUNDABOUT_EXIT_5, PointRteAction.ROUNDABOUT_EXIT_6,
                PointRteAction.ROUNDABOUT_EXIT_7, PointRteAction.ROUNDABOUT_EXIT_8 -> R.drawable.roundabout_left
                
                PointRteAction.ARRIVE_DEST, PointRteAction.ARRIVE_DEST_LEFT, PointRteAction.ARRIVE_DEST_RIGHT -> android.R.drawable.ic_menu_myplaces

                else -> android.R.drawable.ic_menu_compass
            }
            
            // Konvertiere Vector/Drawable zu Bitmap für das Bluetooth-System
            val drawable = ContextCompat.getDrawable(applicationContext, iconRes)
            drawable?.let {
                // Erzwinge weiße Farbe für das Bitmap
                androidx.core.graphics.drawable.DrawableCompat.setTint(it, android.graphics.Color.WHITE)

                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                it.setBounds(0, 0, canvas.width, canvas.height)
                it.draw(canvas)
                this.actionIcon = NavigationIcon(bitmap)
            }
        }

        // Verhindere Flackern: Nur senden, wenn sich die Daten geändert haben
        if (navData == mLastNavigationData) {
            return
        }
        mLastNavigationData = navData

        // Sende die Daten an die UI und an das Bluetooth-Gerät (via sendToDevice)
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.Main) {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                android.content.Intent(com.maisonsmd.catdrive.lib.Intents.NAVIGATION_UPDATE).apply {
                    putExtra("navigation_data", navData)
                }
            )
            // Auch direkt über Bluetooth senden (NUR HIER!)
            sendToDevice(navData)
        }
    }

    override fun onDestroy() {
        mPollingJob?.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.v("onStartCommand: $intent")

        if (intent?.action == Intents.ENABLE_SERVICES) {
            runInBackground = true
            startForeground(NOTIFICATION_ID, buildForegroundNotification())

            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CatDrive::BleServiceWakeLock")
            wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)

            subscribeToLocationUpdates()

            if (mConnectionState == BluetoothProfile.STATE_DISCONNECTED)
                startReconnectTimer()
        }

        if (intent?.action == Intents.DISABLE_SERVICES) {
            runInBackground = false

            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null

            disconnect()
            unsubscribeFromLocationUpdates()

            mNotificationBuilder = null

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            stopReconnectTimer()
        }

        if (intent?.action == Intents.CONNECT_DEVICE) {
            if (PermissionCheck.checkBluetoothPermissions(applicationContext)) {
                val device = intent.getParcelableExtra<BluetoothDevice>("device")!!
                connect(device)
            }

            if (!runInBackground)
                return START_NOT_STICKY
        }

        if (intent?.action == Intents.DISCONNECT_DEVICE) {
            if (PermissionCheck.checkBluetoothPermissions(applicationContext)) {
                disconnect()
            }

            if (!runInBackground)
                return START_NOT_STICKY
        }

        return START_STICKY
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Timber.d("onConnectionStateChange: $status, $newState")

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // successfully connected to the GATT Server
                // Attempts to discover services after successful connection.
                Timber.i("onConnectionStateChange: Connected! ${mDevice}, ${mBluetoothGatt}")
                mConnectionState = BluetoothProfile.STATE_CONNECTING
                gatt?.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // disconnected from the GATT Server
                Timber.i("onConnectionStateChange: Disconnected!")
                
                // Gedächtnis löschen, damit nach Reconnect sofort alles neu gesendet wird
                mLastNavigationData = null 

                if (mConnectionState != BluetoothProfile.STATE_DISCONNECTED) {
                    disconnect()
                }

                updateNotificationText("No device connected")
                startReconnectTimer()

                LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                    Intent(Intents.CONNECTION_UPDATE).apply {
                        putExtra("status", "disconnected")
                    }
                )
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            gatt?.discoverServices()
            mConnectionState = BluetoothProfile.STATE_CONNECTING
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            // Discovery finishes after onServicesDiscovered is called
            if (mConnectionState == BluetoothProfile.STATE_DISCONNECTED)
                return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Timber.i("onServicesDiscovered: Success!")

                mConnectionState = BluetoothProfile.STATE_CONNECTED

                mIsSending = false
                mDataWriteQueue.clear()
                mIconMap.clear()

                updateNotificationText("Connected to ${PermissionCheck.getDeviceNameSafe(applicationContext, mDevice)}")
                stopReconnectTimer()
                sendPreferencesToDevice()
                
                // Sofortiger Anstupser: Letzte bekannte Nav-Daten senden
                mLastNavigationData?.let { sendToDevice(it) }

                LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                    Intent(Intents.CONNECTION_UPDATE).apply {
                        putExtra("status", "connected")
                        putExtra("device_name", PermissionCheck.getDeviceNameSafe(applicationContext, mDevice))
                        putExtra("device_address", mDevice!!.address)
                    }
                )
            } else {
                Timber.w("onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)

            Timber.d("onCharacteristicWrite: $status (0 means success)")

            mIsSending = false
            if (mDataWriteQueue.size > 0) {
                write(mDataWriteQueue.pop())
            }
        }
    }

    private fun write(item: QueueItem) {
        Timber.d("writing ${item.uuid}=${item.data.toString(Charsets.UTF_8)}")
        if (mConnectionState != BluetoothProfile.STATE_CONNECTED) {
//            Timber.e("write: not connected")
            return
        }

        if (mIsSending) {
            Timber.d("Busy with ${mDataWriteQueue.size} requests, queueing")
            mDataWriteQueue.add(item)
            return
        }

        Timber.d("Ble free to write, writing")
        mIsSending = true
        mBluetoothGatt?.let {
            val ch = findCharacteristic(item.uuid)
            if (ch == null) {
                Timber.e("No characteristic found for ${item.uuid}")
                return
            }

            ch.value = item.data
            it.writeCharacteristic(ch)
        }
    }

    private fun findCharacteristic(uuid: String): BluetoothGattCharacteristic? {
        var characteristic: BluetoothGattCharacteristic? = null
        val service = mBluetoothGatt?.getService(UUID.fromString(BleCharacteristics.SERVICE_UUID))
        service?.characteristics?.forEach { ch ->
            if (ch.uuid.toString() == uuid)
                characteristic = ch
        }
        return characteristic
    }

    fun connectToLastDevice() {
        val sp = applicationContext.getSharedPreferences(
            SHARED_PREFERENCES_FILE,
            Context.MODE_PRIVATE
        )
        val name = sp.getString("last_device_name", null)
        val address = sp.getString("last_device_address", null)

        if (name != null && address != null) {
            Timber.i("trying connecting to $name address $address")
            mAdapter.getRemoteDevice(address)?.let {
                connect(it)
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        Timber.i("Connecting to device $device")
        mDevice = device
        mBluetoothGatt =
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE).also {
                it.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            }
    }

    fun disconnect() {
        stopReconnectTimer()

        if (mConnectionState == BluetoothProfile.STATE_CONNECTED) {
            Timber.i("Disconnecting from device")
            mIsSending = false
            mDataWriteQueue.clear()
            mConnectionState = BluetoothProfile.STATE_DISCONNECTED
            mDevice = null
            mBluetoothGatt?.let { gatt ->
                gatt.disconnect()
                gatt.close()
                mBluetoothGatt = null
            }

            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                Intent(Intents.CONNECTION_UPDATE).apply {
                    putExtra("status", "disconnected")
                }
            )
        }
    }

    fun sendPreferencesToDevice() {
        val sp =
            applicationContext.getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE)

        val map = mapOf(
            "lightTheme" to sp.getBoolean("display_light_theme", true).toString(),
            "brightness" to sp.getInt("display_brightness", 50).toString(),
            "speedLimit" to sp.getInt("speed_limit", 50).toString()
        )

        write(
            QueueItem(BleCharacteristics.CHA_SETTINGS, toKeyValString(map).toByteArray())
        )
    }

    fun sendToDevice(data: NavigationData?) {
        fun sanitize(str: String): String {
            // Remove non-breaking space
            return str.replace("\u00a0", " ").replace("\n", " ").replace("…", "...")
        }

        val bitmap = data?.actionIcon?.bitmap

        var compressed: ByteArray? = bitmap?.let {
            val helper = BitmapHelper()
            helper.toBlackAndWhiteBuffer(
                helper.compressBitmap(
                    bitmap,
                    Size(64, 62)
                )
            )
        }

        var iconHash = ""
        if (compressed != null) {
            iconHash = md5(compressed)
            iconHash = iconHash.substring(iconHash.length - 10, iconHash.length)
        }

        val map = mapOf(
            "nextRd" to sanitize(data?.nextDirection?.distance ?: ""), // Meter unter den Pfeil
            "nextRdDesc" to sanitize(data?.nextDirection?.nextRoadAdditionalInfo ?: ""),
            "distToNext" to sanitize(data?.nextDirection?.nextRoad ?: ""), // Text in die Mitte
            "totalDist" to sanitize(data?.eta?.distance ?: ""),
            "eta" to sanitize(data?.eta?.eta ?: ""),
            "ete" to sanitize(data?.eta?.ete ?: ""),
            "iconHash" to (iconHash)
        )

        write(QueueItem(BleCharacteristics.CHA_NAV, toKeyValString(map).toByteArray()))

        // Only send once
        if (iconHash != "" && compressed != null && !mIconMap.containsKey(iconHash)) {
            if (mConnectionState == BluetoothProfile.STATE_CONNECTED) {
                // Store the bitmap for later use
                mIconMap[iconHash] = compressed

                compressed.let {
                    val withIconHash = ("$iconHash;").toByteArray()
                    Timber.w("it size: ${it.size}")
                    write(QueueItem(BleCharacteristics.CHA_NAV_TBT_ICON, withIconHash + it, true))
                }
            }
        } else {
            Timber.i("Icon $iconHash already sent before")
        }
    }

    private fun md5(s: ByteArray): String {
        return try {
            // Create MD5 Hash
            val digest = MessageDigest.getInstance("MD5")
            digest.update(s)
            val messageDigest = digest.digest()

            // Create Hex String
            val hexString = StringBuilder()
            for (aMessageDigest in messageDigest) {
                var h = Integer.toHexString(0xFF and aMessageDigest.toInt())
                while (h.length < 2) {
                    h = "0$h"
                }
                hexString.append(h)
            }
            hexString.toString()
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
            ""
        }
    }


    private fun subscribeToLocationUpdates() {
        if (PermissionCheck.checkLocationAccessPermission(applicationContext)) {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0f, this)
        }
    }

    private fun unsubscribeFromLocationUpdates() {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        manager.removeUpdates(this)
    }

    private fun startReconnectTimer() {
        stopReconnectTimer()
        mReconnectTimer = Timer()
        // Häufigere Versuche (alle 5 Sek) statt alle 15 Sek
        mReconnectTimer!!.schedule(ReconnectTask(), 1000, 5000)
    }

    private fun stopReconnectTimer() {
        if (mReconnectTimer != null) {
            mReconnectTimer!!.cancel()
            mReconnectTimer!!.purge()
            mReconnectTimer = null
        }
    }

    override fun onLocationChanged(location: Location) {
        // Schwellenwert: Alles unter 0.5 m/s (~1.8 km/h) ist Stillstand (0 km/h)
        val speedKmh = location.speed * 3.6f
        val speed = if (speedKmh < 2.0f) 0 else ceil(speedKmh).toInt()

        Timber.d("Speed: $speed")

        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
            Intent(Intents.GPS_UPDATE).apply {
                putExtra("speed", speed)
            }
        )

        write(QueueItem(BleCharacteristics.CHA_GPS_SPEED, speed.toString().toByteArray()))
    }

    private fun updateNotificationText(text: String) {
        if (mNotificationBuilder == null)
            return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Missing POST_NOTIFICATIONS permission")
            return
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mNotificationBuilder!!.setContentText(text)
        notificationManager.notify(NOTIFICATION_ID, mNotificationBuilder!!.build())
    }

    private fun createNotificationChannel(channelId: String, channelName: String): String {
        val channel =
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
        channel.lightColor = Color.BLUE
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(channel)
        return channelId
    }

    private fun buildForegroundNotification(): Notification {
        val channelId = createNotificationChannel(
            this::class.java.simpleName,
            this::class.java.simpleName
        )
        val builder: Notification.Builder = Notification.Builder(this, channelId)
        val intent = Intent(this, MainActivity::class.java)
        intent.action = System.currentTimeMillis().toString()
        intent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mNotificationBuilder = builder.setContentTitle("LocusDrive")
            .setContentText("Service is running")
            .setSmallIcon(R.drawable.screen_share)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
        return mNotificationBuilder!!.build()
    }

    fun toKeyValString(map: Map<String, String>): String {
        var result = ""
        var count = 1

        for ((key, value) in map) {
            result += "$key=$value"
            if (count < map.size) {
                result += "\n"
            }
            count++
        }

        return result
    }
}

class LocusReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        val action = intent.action ?: return
        Timber.d("LocusReceiver empfängt Aktion: $action")

        // 1. Weg: Offizielle Locus API (UpdateContainer)
        if (action == "locus.api.android.ACTION_PERIODIC_UPDATE") {
            val extras = intent.extras
            var data = intent.getByteArrayExtra("DATA") ?: intent.getByteArrayExtra("INTENT_EXTRA_UPDATE_DATA")
            
            var nextRoad = ""
            var distance = -1.0
            var eteMillis = 0L

            if (data != null) {
                val container = try {
                    val dr = DataReaderBigEndian(data)
                    Storable.read(UpdateContainer::class.java, dr)
                } catch (e: Exception) {
                    null
                }
                
                if (container != null) {
                    nextRoad = container.guideNavPoint1Name ?: ""
                    distance = container.guideNavPoint1Dist
                    eteMillis = container.guideTimeToFinish
                }
            } else if (extras != null) {
                // Fallback: Locus sendet Einzel-Felder (Numeric IDs)
                // 1401 = NavPoint 1 Name, 1403 = NavPoint 1 Distance, 1503 = Time to finish
                nextRoad = extras.getString("1401") ?: ""
                distance = extras.getDouble("1403", -1.0)
                
                // Falls 1403 kein Double ist, versuche es als String
                if (distance < 0) {
                    val distStr = extras.getString("1403") ?: ""
                    distance = distStr.toDoubleOrNull() ?: -1.0
                }

                // Verbleibende Zeit (1503) - Flexibel als String oder Long lesen
                val eteValue = extras.get("1503")
                eteMillis = when (eteValue) {
                    is Long -> eteValue
                    is String -> eteValue.toLongOrNull() ?: 0L
                    is Int -> eteValue.toLong()
                    else -> 0L
                }
                
                // Falls Locus die Zeit in Sekunden sendet (oft bei Strings), in Millisekunden umrechnen
                if (eteMillis in 1..999999) eteMillis *= 1000
            }

            if (nextRoad.isNotEmpty() || distance >= 0) {
                processAndSend(context, nextRoad, distance, eteMillis)
            }
            return
        } 
        // 2. Weg: Einfache Nav-Daten (Fallback)
        else if (action == "com.locus.map.NEW_NAV_DATA") {
            val nextRoad = intent.getStringExtra("NAME") ?: ""
            val distance = intent.getDoubleExtra("DISTANCE", -1.0)
            val eta = intent.getLongExtra("ETA", 0L)
            val eteMillis = if (eta > System.currentTimeMillis()) eta - System.currentTimeMillis() else 0L
            
            processAndSend(context, nextRoad, distance, eteMillis)
        }
    }

    private fun processAndSend(context: android.content.Context, nextRoad: String, distance: Double, eteMillis: Long) {
        // Distanz formatieren
        val distanceStr = when {
            distance < 0 -> "---"
            distance >= 1000 -> String.format(java.util.Locale.US, "%.1f km", distance / 1000.0)
            else -> "${distance.toInt()} m"
        }

        // ETA (Ankunftszeit) berechnen
        val etaStr = if (eteMillis > 0) {
            val arrivalTimestamp = System.currentTimeMillis() + eteMillis
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(arrivalTimestamp))
        } else ""

        val navData = com.maisonsmd.catdrive.lib.NavigationData().apply {
            nextDirection = com.maisonsmd.catdrive.lib.NavigationDirection(
                nextRoad = nextRoad,
                distance = distanceStr
            )
            this.eta = com.maisonsmd.catdrive.lib.NavigationEta(
                eta = etaStr,
                distance = "" 
            )
        }

        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcast(
            android.content.Intent(com.maisonsmd.catdrive.lib.Intents.NAVIGATION_UPDATE).apply {
                putExtra("navigation_data", navData)
            }
        )
        
        Timber.i("Locus verarbeitet: $nextRoad | $distanceStr | ETA: $etaStr")
    }
}
