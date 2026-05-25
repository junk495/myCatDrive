package com.maisonsmd.catdrive.lib

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification

open class NavigationNotification(context: Context, statusBarNotification: StatusBarNotification) {
    protected val mNotification: Notification = statusBarNotification.notification
    protected val mContext = context
    protected var mAppSourceContext: Context = mContext.createPackageContext(
        statusBarNotification.packageName,
        Context.CONTEXT_IGNORE_SECURITY
    )

    private var mNavigationData: NavigationData = NavigationData()
    var navigationData
        get() = mNavigationData
        set(value) {
            if (value == mNavigationData)
                return
            mNavigationData = value
        }

    init {
        mNavigationData.postTime = NavigationTimestamp(statusBarNotification.postTime)
    }
}
