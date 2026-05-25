package com.maisonsmd.catdrive.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.maisonsmd.catdrive.lib.GMAPS_PACKAGE
import com.maisonsmd.catdrive.lib.GMapsNotification
import com.maisonsmd.catdrive.lib.NavigationData
import com.maisonsmd.catdrive.lib.NavigationNotification
import kotlinx.coroutines.*
import timber.log.Timber

@OptIn(DelicateCoroutinesApi::class)
open class NavigationListener : NotificationListenerService() {
    private var mNotificationParserCoroutine: Job? = null
    private lateinit var mLastNotification: StatusBarNotification

    private var mCurrentNotification: NavigationNotification? = null
    private var mEnabled = false

    protected var enabled: Boolean
        get() = mEnabled
        set(value) {
            if (value == mEnabled)
                return
            if (value.also { mEnabled = it })
                checkActiveNotifications()
            else {
                mCurrentNotification = null
            }
        }

    val lastNavigationData: NavigationData? get() = mCurrentNotification?.navigationData

    override fun onListenerConnected() {
        super.onListenerConnected()
        checkActiveNotifications()
    }

    private fun checkActiveNotifications() {
        try {
            Timber.d("Checking for active Navigation notifications")
            this.activeNotifications.forEach { statusBarNotification ->
                // Timber.v(statusBarNotification.toString())
                onNotificationPosted(
                    statusBarNotification
                )
            }
        } catch (e: Throwable) {
            Timber.e("Failed to check for active notifications: $e")
        }
    }

    private fun isGoogleMapsNotification(sbn: StatusBarNotification?): Boolean {
        // Timber.v("enabled ${mEnabled}, isOngoing: ${sbn!!.isOngoing}, id: ${sbn.id}")
        if (!enabled || sbn == null)
            return false

        if (!sbn.isOngoing || GMAPS_PACKAGE !in sbn.packageName)
            return false

        return (sbn.id == 1)
    }

    protected open fun onNavigationNotificationAdded(navNotification: NavigationNotification) {
    }

    protected open fun onNavigationNotificationUpdated(navNotification: NavigationNotification) {
    }

    protected open fun onNavigationNotificationRemoved(navNotification: NavigationNotification) {
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Timber.v("onNotificationPosted ${sbn?.packageName}")

        if (isGoogleMapsNotification(sbn))
            handleGoogleNotification(sbn!!)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Timber.v("onNotificationRemoved ${sbn?.packageName}")

        if (isGoogleMapsNotification(sbn)) {
            mNotificationParserCoroutine?.cancel()

            onNavigationNotificationRemoved(
                if (mCurrentNotification != null) mCurrentNotification!!
                else NavigationNotification(applicationContext, sbn!!)
            )

            mCurrentNotification = null
        }
    }

    private fun handleGoogleNotification(statusBarNotification: StatusBarNotification) {
        mLastNotification = statusBarNotification
        if (mNotificationParserCoroutine != null && mNotificationParserCoroutine!!.isActive)
            return

        mNotificationParserCoroutine = GlobalScope.launch(Dispatchers.Main) {
            val worker = GlobalScope.async(Dispatchers.Default) {
                return@async GMapsNotification(
                    this@NavigationListener.applicationContext,
                    mLastNotification
                )
            }

            try {
                val mapNotification = worker.await()
                val lastNotification = mCurrentNotification

                val updated: Boolean = if (lastNotification == null) {
                    onNavigationNotificationAdded(mapNotification)
                    true
                } else {
                    lastNotification.navigationData != mapNotification.navigationData
                    // Timber.v("Notification is different than previous: $updated")
                }

                if (updated) {
                    mCurrentNotification = mapNotification
                    onNavigationNotificationUpdated(mCurrentNotification!!)
                }
            } catch (error: Exception) {
                if (!mNotificationParserCoroutine!!.isCancelled)
                    Timber.e("Got an error while parsing: $error")
            }
        }
    }

}
