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
import android.bluetooth.BluetoothStatusCodes
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
    private var mLastSendTime: Long = 0
    private var mDataWriteQueue: BleWriteQueue = BleWriteQueue()
    private var mIsSending: Boolean = false
    private var mIconMap: MutableMap<String, ByteArray> = mutableMapOf()
    private var mCurrentSpeed: Int = 0

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
        // 1. Nächste Abbiegung
        val nextRoad = container.guideNavPoint1Name ?: ""
        val distance = container.guideNavPoint1Dist
        val action = container.guideNavPoint1Action
        
        // 2. Übernächste Abbiegung & Hindernisse
        val nextRoad2 = container.guideNavPoint2Name ?: ""
        val distance2 = container.guideNavPoint2Dist
        val action2 = container.guideNavPoint2Action
        val viaName = container.guideNextViaName ?: ""
        val viaDist = container.guideNextViaDist

        // Distanz zur nächsten Abbiegung formatieren
        val distanceStr = when {
            distance < 0 -> ""
            distance >= 1000 -> String.format(java.util.Locale.US, "%.1f km", distance / 1000.0)
            else -> "${distance.toInt()} m"
        }

        // Übernächste Distanz
        val distance2Str = when {
            distance2 <= 0 -> ""
            distance2 >= 1000 -> String.format(java.util.Locale.US, "%.1f km", distance2 / 1000.0)
            else -> "${distance2.toInt()} m"
        }

        // Hindernis Distanz
        val viaDistStr = when {
            viaDist <= 0 -> ""
            viaDist >= 1000 -> String.format(java.util.Locale.US, "%.1f km", viaDist / 1000.0)
            else -> "${viaDist.toInt()} m"
        }
        
        // Gesamtdistanz formatieren
        val totalDistance = container.guideDistToFinish
        val totalDistanceStr = when {
            totalDistance < 0 -> ""
            totalDistance >= 1000 -> String.format(java.util.Locale.US, "%.1f km", totalDistance / 1000.0)
            else -> "${totalDistance.toInt()} m"
        }

        // 4. Ankunftszeit berechnen (ETA) und Fahrzeit (ETE) formatieren
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
            // Map Locus Actions to Icon Names for ESP32
            fun getIconName(act: PointRteAction): String {
                return when (act) {
                    PointRteAction.LEFT, PointRteAction.LEFT_SHARP -> "turn_left"
                    PointRteAction.LEFT_SLIGHT, PointRteAction.STAY_LEFT -> "turn_slight_left"
                    PointRteAction.RIGHT, PointRteAction.RIGHT_SHARP -> "turn_right"
                    PointRteAction.RIGHT_SLIGHT, PointRteAction.STAY_RIGHT -> "turn_slight_right"
                    PointRteAction.U_TURN_LEFT, PointRteAction.U_TURN -> "u_turn_left"
                    PointRteAction.U_TURN_RIGHT -> "u_turn_right"
                    PointRteAction.CONTINUE_STRAIGHT, PointRteAction.STAY_STRAIGHT,
                    PointRteAction.NO_MANEUVER, PointRteAction.NO_MANEUVER_NAME_CHANGE -> "straight"
                    PointRteAction.ROUNDABOUT_EXIT_1, PointRteAction.ROUNDABOUT_EXIT_2, 
                    PointRteAction.ROUNDABOUT_EXIT_3, PointRteAction.ROUNDABOUT_EXIT_4,
                    PointRteAction.ROUNDABOUT_EXIT_5, PointRteAction.ROUNDABOUT_EXIT_6,
                    PointRteAction.ROUNDABOUT_EXIT_7, PointRteAction.ROUNDABOUT_EXIT_8 -> "roundabout_left"
                    PointRteAction.ARRIVE_DEST, PointRteAction.ARRIVE_DEST_LEFT, PointRteAction.ARRIVE_DEST_RIGHT -> "destination"
                    else -> "compass"
                }
            }

            nextDirection = com.maisonsmd.catdrive.lib.NavigationDirection(
                nextRoad = nextRoad, 
                nextTurn2 = distance2Str,
                nextRoadAdditionalInfo = nextRoad2,
                hazard = viaDistStr,
                hazardText = viaName,
                distance = distanceStr,
                iconName = getIconName(action),
                iconName2 = if (distance2 > 0) getIconName(action2) else "",
                hazardIconName = if (viaDist > 0) "alert" else ""
            )
            this.eta = com.maisonsmd.catdrive.lib.NavigationEta(
                eta = etaStr,
                ete = eteStr,
                distance = totalDistanceStr 
            )
            
            this.phoneBattery = container.deviceBatteryValue
            this.gpsAccuracy = if (container.isGpsLocValid) container.locMyLocation.accuracyHor ?: -1f else -1f
            this.speed = mCurrentSpeed

            // Handy-App UI braucht Bitmaps
            fun bitmapFromResName(name: String): Bitmap? {
                if (name == "") return null
                val resId = when(name) {
                    "turn_left" -> R.drawable.turn_left
                    "turn_slight_left" -> R.drawable.turn_slight_left
                    "turn_right" -> R.drawable.turn_right
                    "turn_slight_right" -> R.drawable.turn_slight_right
                    "u_turn_left" -> R.drawable.u_turn_left
                    "u_turn_right" -> R.drawable.u_turn_right
                    "straight" -> R.drawable.straight
                    "roundabout_left" -> R.drawable.roundabout_left
                    "destination" -> android.R.drawable.ic_menu_myplaces
                    "alert" -> android.R.drawable.ic_dialog_alert
                    else -> android.R.drawable.ic_menu_compass
                }
                val drawable = try { ContextCompat.getDrawable(applicationContext, resId) } catch (e: Exception) { null }
                return drawable?.let {
                    androidx.core.graphics.drawable.DrawableCompat.setTint(it, android.graphics.Color.WHITE)
                    // Auf 64x64 geändert für perfekte Symmetrie und 512-Byte Puffer
                    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    it.setBounds(0, 0, canvas.width, canvas.height)
                    it.draw(canvas)
                    bitmap
                }
            }

            this.actionIcon = NavigationIcon(bitmapFromResName(nextDirection.iconName ?: ""))
            this.actionIcon2 = NavigationIcon(bitmapFromResName(nextDirection.iconName2 ?: ""))
            this.hazardIcon = NavigationIcon(bitmapFromResName(nextDirection.hazardIconName ?: ""))
        }

        // Verhindere Flackern: Nur senden, wenn sich die Daten geändert haben
        // Heartbeat: Mindestens alle 2 Sekunden senden
        val timeSinceLastSend = System.currentTimeMillis() - mLastSendTime
        if (navData == mLastNavigationData && timeSinceLastSend < 2000) {
            return
        }

        mLastNavigationData = navData
        mLastSendTime = System.currentTimeMillis()

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
            Timber.d("onCharacteristicWrite: $status")

            synchronized(this@BleService) {
                mIsSending = false
                processNextInQueue()
            }
        }
    }

    private fun write(item: QueueItem) {
        // BLE-Operationen müssen auf dem Main-Thread und synchronisiert laufen
        GlobalScope.launch(Dispatchers.Main) {
            synchronized(this@BleService) {
                if (mConnectionState != BluetoothProfile.STATE_CONNECTED) return@launch

                if (mIsSending) {
                    // Verhindere das Überlaufen der Queue mit NAV-Daten
                    if (item.overwrite) {
                        mDataWriteQueue.mQueue.removeAll { it.uuid == item.uuid }
                    }
                    mDataWriteQueue.add(item)
                    return@launch
                }

                mIsSending = true
                executeWrite(item)
            }
        }
    }

    private fun executeWrite(item: QueueItem) {
        mBluetoothGatt?.let { gatt ->
            val ch = findCharacteristic(item.uuid)
            if (ch == null) {
                mIsSending = false
                return
            }

            try {
                Timber.d("BLE Write: ${item.uuid} (Größe: ${item.data.size} Bytes)")
                
                val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(ch, item.data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    ch.value = item.data
                    gatt.writeCharacteristic(ch)
                }

                if (!success) {
                    Timber.e("BLE Schreibvorgang vom System abgelehnt!")
                    mIsSending = false
                    processNextInQueue()
                } else {
                    // Notfall-Timeout: Falls kein Callback kommt, nach 1,5s weitermachen
                    GlobalScope.launch(Dispatchers.Main) {
                        delay(1500)
                        if (mIsSending) {
                            Timber.w("BLE Write Timeout für ${item.uuid}")
                            mIsSending = false
                            processNextInQueue()
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Kritischer Fehler beim BLE Schreiben")
                mIsSending = false
                processNextInQueue()
            }
        } ?: run { mIsSending = false }
    }

    private fun processNextInQueue() {
        if (mDataWriteQueue.size > 0) {
            write(mDataWriteQueue.pop())
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

    fun sendPreferencesToDevice(resetCache: Boolean = false) {
        val sp =
            applicationContext.getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE)

        val map = mutableMapOf(
            "l" to (if (sp.getBoolean("display_light_theme", false)) "1" else "0"),
            "br" to sp.getInt("display_brightness", 20).toString(),
            "sl" to sp.getInt("speed_limit", 50).toString()
        )

        if (resetCache) {
            map["removeAllFiles"] = "true"
        }

        write(
            QueueItem(BleCharacteristics.CHA_SETTINGS, toKeyValString(map).toByteArray())
        )
    }

    fun sendToDevice(data: NavigationData?) {
        fun sanitize(str: String, maxLen: Int = 20): String {
            // Radikale Kürzung, um die 237-Byte-MTU-Grenze absolut sicher einzuhalten
            val s = str.replace("\u00a0", " ").replace("\n", " ").replace("…", "...")
            return if (s.length > maxLen) s.substring(0, maxLen - 3) + "..." else s
        }

        val sp = applicationContext.getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE)
        val isLight = sp.getBoolean("display_light_theme", false)
        val brightness = sp.getInt("display_brightness", 20)
        val speedLimit = sp.getInt("speed_limit", 50)

        // Wir nutzen extrem kurze Keys und kurze Texte, um unter 237 Bytes zu bleiben
        val map = mapOf(
            "nTD" to sanitize(data?.nextDirection?.distance ?: "", 10),
            "nTT" to sanitize(data?.nextDirection?.nextRoad ?: "", 25),
            "nTI" to (data?.nextDirection?.iconName ?: ""), // Sendet jetzt den NAMEN (z.B. "turn_left")
            "n2D" to sanitize(data?.nextDirection?.nextTurn2 ?: "", 10),
            "n2T" to sanitize(data?.nextDirection?.nextRoadAdditionalInfo ?: "", 25),
            "n2I" to (data?.nextDirection?.iconName2 ?: ""), // Sendet jetzt den NAMEN
            "hD" to sanitize(data?.nextDirection?.hazard ?: "", 10),
            "hT" to sanitize(data?.nextDirection?.hazardText ?: "", 25),
            "hI" to (data?.nextDirection?.hazardIconName ?: ""), // Sendet jetzt den NAMEN
            "eta" to (data?.eta?.eta ?: ""),
            "ete" to (data?.eta?.ete ?: ""),
            "tD" to sanitize(data?.eta?.distance ?: "", 10),
            "s" to (data?.speed ?: 0).toString(),
            "b" to (data?.phoneBattery ?: -1).toString(),
            "a" to String.format(java.util.Locale.US, "%.1f", data?.gpsAccuracy ?: -1f),
            "l" to (if (isLight) "1" else "0"),
            "br" to brightness.toString(),
            "sl" to speedLimit.toString()
        )

        val payload = toKeyValString(map).toByteArray()

        // Sende Nav-Daten mit 'overwrite = true', damit die Queue nicht verstopft
        write(QueueItem(BleCharacteristics.CHA_NAV, payload, true))

        // BITMAP-TRANSFER DEAKTIVIERT: Der ESP32 nutzt seine eigenen festen Bilder
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
        mCurrentSpeed = if (speedKmh < 2.0f) 0 else ceil(speedKmh).toInt()

        Timber.d("Speed: $mCurrentSpeed")

        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
            Intent(Intents.GPS_UPDATE).apply {
                putExtra("speed", mCurrentSpeed)
            }
        )
        
        // Wir senden keine separate CHA_GPS_SPEED mehr, da speed jetzt im NAV-Paket enthalten ist
        // write(QueueItem(BleCharacteristics.CHA_GPS_SPEED, mCurrentSpeed.toString().toByteArray()))
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
