package com.riftlab.app.data

/**
 * Kotlin Sequence does not expose takeLast on the toolchain used by RiftLab.
 * Keep the call-site lazy until the final bounded tail is requested.
 */
internal fun <T> Sequence<T>.takeLast(count: Int): List<T> {
    if (count <= 0) return emptyList()
    val buffer = ArrayDeque<T>(count)
    for (item in this) {
        if (buffer.size == count) buffer.removeFirst()
        buffer.addLast(item)
    }
    return buffer.toList()
}
