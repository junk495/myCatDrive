package com.maisonsmd.catdrive.lib

import android.app.Notification
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.service.notification.StatusBarNotification
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.view.children
import org.json.JSONObject
import timber.log.Timber

const val GMAPS_PACKAGE = "com.google.android.apps.maps"
const val WAZE_PACKAGE = "com.waze"
const val LOCUS_PACKAGE = "menion.android.locus"

val SUPPORTED_PACKAGES = listOf(GMAPS_PACKAGE, WAZE_PACKAGE, LOCUS_PACKAGE)

enum class ContentViewType {
    NORMAL,
    BIG,
    BEST,
}

internal class GMapsNotification(cx: Context, sbn: StatusBarNotification) : NavigationNotification(cx, sbn) {
    init {
        val normalContent = getContentView(ContentViewType.NORMAL)
        if (normalContent != null)
            parseRemoteView(getRemoteViewGroup(normalContent))

        val bestContentView = getContentView(ContentViewType.BEST)
        if (bestContentView != normalContent)
            parseRemoteView(getRemoteViewGroup(bestContentView))
    }

    private fun getContentView(type: ContentViewType = ContentViewType.BEST): RemoteViews? {
        if (type == ContentViewType.BIG || type == ContentViewType.BEST) {
            val remoteViews = Notification.Builder.recoverBuilder(mContext, mNotification).createBigContentView()

            if (remoteViews != null || type == ContentViewType.BIG)
                return remoteViews
        }

        return Notification.Builder.recoverBuilder(mContext, mNotification).createContentView()
    }

    private fun getRemoteViewGroup(remoteViews: RemoteViews?): ViewGroup {
        if (remoteViews == null) {
            throw Exception("Impossible to create notification view")
        }

        val layoutInflater = mAppSourceContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val viewGroup = layoutInflater.inflate(remoteViews.layoutId, null) as ViewGroup?
            ?: throw Exception("Impossible to inflate viewGroup")

        remoteViews.reapply(mAppSourceContext, viewGroup)

        return viewGroup
    }

    private fun getEntryName(item: View): String {
        val entryName: String = try {
            if (item.id > 0)
                mAppSourceContext.resources.getResourceEntryName(item.id)
            else ""
        } catch (e: Exception) {
            ""
        }

        return entryName
    }

    private fun getEntryJsonKey(item: View): String {
        return "${item.javaClass.simpleName}:${getEntryName(item)}"
    }

    private fun findChildByName(group: ViewGroup, name: CharSequence): View? {
        for (child in group.children) {
            val entryName = getEntryName(child)

            if (entryName == name)
                return child

            if (child is ViewGroup) {
                val c = findChildByName(child, name)
                if (c != null)
                    return c
            }
        }

        return null
    }

    private fun parseRemoteView(group: ViewGroup): NavigationData {
        val data = navigationData

        val allTexts = mutableListOf<String>()
        findAllTexts(group, allTexts)
        
        // Füge System-Extras hinzu, falls vorhanden
        mNotification.extras.apply {
            getCharSequence(Notification.EXTRA_TITLE)?.let { allTexts.add(it.toString()) }
            getCharSequence(Notification.EXTRA_TEXT)?.let { allTexts.add(it.toString()) }
            getCharSequence(Notification.EXTRA_SUB_TEXT)?.let { allTexts.add(it.toString()) }
        }

        val titleText = findChildByName(group, "title") as TextView?
        val directionText = findChildByName(group, "text") as TextView?
        val rightIcon = findChildByName(group, "right_icon") as ImageView?

        var ete: String? = data.eta.ete
        var totalDistance: String? = data.eta.distance
        var arrivalTime: String? = data.eta.eta

        // Wir filtern Texte, die zur aktuellen Abbiegeanweisung gehören (z. B. "270 m")
        val turnInfo = listOfNotNull(titleText?.text?.toString(), directionText?.text?.toString())

        for (text in allTexts) {
            // Teile an allen gängigen Trennern
            val parts = text.split(Regex("[·•|\\n]")).map { it.trim() }.filter { it.isNotEmpty() }
            
            for (part in parts) {
                // 1. Fahrzeit (ETE)
                if (part.contains(Regex("\\d+\\s*(h|min|Std|Min)"))) {
                    if (ete == null || ete == "---") ete = part
                }
                // 2. Ankunftszeit (ETA)
                else if (part.contains(Regex("\\d{1,2}:\\d{2}"))) {
                    if (arrivalTime == null || arrivalTime == "---") {
                        arrivalTime = part.replace(Regex("Ankunft um|Ankunft|ETA"), "").trim()
                    }
                }
                // 3. Gesamtstrecke (Distance)
                // Wir nehmen nur Distanzen, die NICHT Teil der aktuellen Abbiege-Info sind
                else if (part.contains(Regex("\\d+\\s*(km|m)"))) {
                    if (!turnInfo.any { it.contains(part) }) {
                        if (totalDistance == null || totalDistance == "---") totalDistance = part
                    }
                }
            }
        }

        data.eta = NavigationEta(arrivalTime, ete, totalDistance)

        var nextDistance = ""
        if (titleText != null && titleText.text.trim().isNotEmpty()) {
            nextDistance = titleText.text.trim().toString()
        }
        var nextRoad = ""
        var nextRoadDesc = ""
        if (directionText?.text !is Spanned) {
            // must be the text "Rerouting..."
            Timber.w("Direction Text is not Spanned, text: ${directionText?.text}")
            nextRoad = directionText?.text as String
        } else {
            // Road names are in Typeface.BOLD, sub texts are in Typeface.NORMAL
            var directionList = ParserHelper.splitByStyleSpan(directionText?.text as Spanned, Typeface.NORMAL, 2)
            if (directionList.isNotEmpty()) {
                val nextRoadList = mutableListOf(directionList.first())
                val nextRoadDescList = mutableListOf<ParserHelper.SpanSplitResult>()

                val rest = directionList.drop(1)
                val index = rest.indexOfFirst { it.isKeySpan && it.text.trim() != "/" }
                if (index == -1) {
                    nextRoadList.addAll(rest)
                } else {
                    nextRoadList.addAll(rest.subList(0, index))
                    nextRoadDescList.addAll(rest.subList(index, rest.size))
                }
                nextRoad = nextRoadList.joinToString(" ") { it.text }
                nextRoadDesc = nextRoadDescList.joinToString(" ") { it.text }
            }
        }
        data.nextDirection = NavigationDirection(nextRoad, nextRoadDesc, nextDistance)

        (rightIcon?.drawable as BitmapDrawable?)?.bitmap?.also {
            data.actionIcon = NavigationIcon(it.copy(it.config, false))
        }

        // Timber.v("$data")

        return data
    }

    private fun findAllTexts(group: ViewGroup, result: MutableList<String>) {
        for (child in group.children) {
            if (child is TextView && child.text.isNotEmpty()) {
                result.add(child.text.toString())
            } else if (child is ViewGroup) {
                findAllTexts(child, result)
            }
        }
    }

    // for debugging
    private fun parseRemoteViewToJson(group: ViewGroup, json: JSONObject? = null): JSONObject {
        var rawJson = json ?: JSONObject()

        val parentGroupKey = getEntryJsonKey(group)
        rawJson.apply {
            put(parentGroupKey, JSONObject())
        }

        for (child in group.children) {
            val entryName = getEntryName(child)
            when (child) {
                is ImageView -> {
                    rawJson.getJSONObject(parentGroupKey).apply { put(getEntryJsonKey(child), entryName) }
                }
                is Button -> {
                    // result.getJSONObject(parentGroupKey).apply { put(getEntryJsonKey(child), child.text) }
                }
                is TextView -> {
                    rawJson.getJSONObject(parentGroupKey).apply {
                        put(getEntryJsonKey(child), JSONObject().apply {
                            put(
                                "rawText",
                                child.text
                            )
                        })
                    }
                }
                is ViewGroup -> {
                    rawJson = parseRemoteViewToJson(child, rawJson)
                }
            }
        }

        return rawJson
    }
}
