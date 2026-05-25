package com.maisonsmd.catdrive.lib

class BleWriteQueue {
    data class QueueItem(val uuid: String, val data: ByteArray, val overwrite: Boolean = true) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as QueueItem

            if (uuid != other.uuid) return false
            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = uuid.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    private var mQueue: MutableList<QueueItem> = mutableListOf()

    fun add(newItem: QueueItem) {
        if (newItem.overwrite && mQueue.find { it.uuid == newItem.uuid } != null) {
            mQueue.removeAll { it.uuid == newItem.uuid }
        }

        mQueue.add(newItem)
    }

    fun pop(): QueueItem {
        return mQueue.removeAt(0)
    }

    val size get() = mQueue.size

    fun clear() {
        mQueue.clear()
    }
}