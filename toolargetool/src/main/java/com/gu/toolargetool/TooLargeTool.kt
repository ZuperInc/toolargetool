package com.gu.toolargetool

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import java.util.*

/**
 * A collection of helper methods to assist you in debugging crashes due to
 * [android.os.TransactionTooLargeException].
 *
 *
 * The easiest way to use this class is to call [.startLogging] in your app's
 * [Application.onCreate] method.
 */
object TooLargeTool {

    private var activityLogger: ActivitySavedStateLogger? = null

    @JvmStatic
    val isLogging: Boolean
        get() = activityLogger!!.isLogging

    /**
     * Helper method to print the result of [.bundleBreakdown] to ADB.
     *
     *
     * Logged at DEBUG priority.
     *
     * @param bundle to log the breakdown of
     * @param tag to log with
     */
    @JvmStatic
    fun logBundleBreakdown(tag: String, bundle: Bundle) {
        Log.println(Log.DEBUG, tag, bundleBreakdown(bundle))
    }

    /**
     * Helper method to print the result of [.bundleBreakdown] to ADB.
     *
     * @param bundle to log the breakdown of
     * @param tag to log with
     * @param priority to log with
     */
    @JvmStatic
    fun logBundleBreakdown(tag: String, priority: Int, bundle: Bundle) {
        Log.println(priority, tag, bundleBreakdown(bundle))
    }

    /**
     * Return a formatted String containing a breakdown of the contents of a [Bundle].
     *
     * @param bundle to format
     * @return a nicely formatted string (multi-line)
     */
    @JvmStatic
    fun bundleBreakdown(bundle: Bundle, rootKeyName: String = "RootBundle"): String {
        val sizeTree = sizeTreeFromBundle(bundle, rootKeyName)

        fun formatSizeTree(tree: SizeTree, indentLevel: Int = 0): String {
            val indent = "  ".repeat(indentLevel) // Indentation for nested levels
            val subtreeMarker = "*".repeat(indentLevel + 1) // Dynamic subtree marker
            val valueRepresentation = tree.value?.toString() ?: "N/A" // Represent the value
            val header = String.format(
                Locale.UK,
                "%s%s %s contains %d keys, measures %,.1f KB, value: %s",
                indent, subtreeMarker, tree.key, tree.subTrees.size, KB(tree.totalSize), valueRepresentation
            )

            val children = tree.subTrees.joinToString(separator = "\n") { child ->
                formatSizeTree(child, indentLevel + 1) // Recursive call for nested subTrees
            }

            return if (children.isEmpty()) header else "$header\n$children"
        }

        // Format the root SizeTree
        return formatSizeTree(sizeTree)
    }

    private fun KB(bytes: Int): Float {
        return bytes.toFloat() / 1000f
    }

    /**
     * Start logging information about all of the state saved by Activities and Fragments.
     *
     * @param application to log
     * @param priority to write log messages at
     * @param tag for log messages
     */
    @JvmOverloads
    @JvmStatic
    fun startLogging(
        application: Application,
        priority: Int = Log.DEBUG,
        tag: String = "TooLargeTool"
    ) {
        startLogging(
            application = application,
            formatter = DefaultFormatter(),
            logger = LogcatLogger(priority, tag)
        )
    }

    @JvmStatic
    fun startLogging(application: Application, formatter: Formatter, logger: Logger) {
        if (activityLogger == null) {
            activityLogger = ActivitySavedStateLogger(formatter, logger, true)
        }

        if (activityLogger!!.isLogging) {
            return
        }

        activityLogger!!.startLogging()
        application.registerActivityLifecycleCallbacks(activityLogger)
    }

    /**
     * Stop all logging.
     *
     * @param application to stop logging
     */
    @JvmStatic
    fun stopLogging(application: Application) {
        if (!activityLogger!!.isLogging) {
            return
        }

        activityLogger!!.stopLogging()
        application.unregisterActivityLifecycleCallbacks(activityLogger)
    }
}

/**
 * Measure the sizes of all the values in a typed [Bundle] when written to a
 * [Parcel]. Returns a map from keys to the sizes, in bytes, of the associated values in
 * the Bundle.
 *
 * @param bundle to measure
 * @return a map from keys to value sizes in bytes
 */
fun sizeTreeFromBundle(bundle: Bundle, keyName: String = "root"): SizeTree {
    val results = ArrayList<SizeTree>(bundle.size())
    // We measure the totalSize of each value by measuring the total totalSize of the bundle before and
    // after removing that value and calculating the difference. We make a copy of the original
    // bundle so we can put all the original values back at the end. It's not possible to
    // carry out the measurements on the copy because of the way Android parcelables work
    // under the hood where certain objects are actually stored as references.
    val copy = Bundle(bundle)
    try {
        var bundleSize = sizeAsParcel(bundle)
        // Iterate over copy's keys because we're removing those of the original bundle
        for (key in copy.keySet()) {
            val value = copy[key] // Extract the value associated with the key
            bundle.remove(key)
            val newBundleSize = sizeAsParcel(bundle)
            val valueSize = bundleSize - newBundleSize
            bundleSize = newBundleSize
            // Check if the value is a nested bundle
            val subTree = if (value is Bundle) {
                sizeTreeFromBundle(value, key) // Pass the key of the nested bundle
            } else {
                SizeTree(key, valueSize, emptyList(), value) // Store the value
            }
            results.add(subTree)
        }
    } finally {
        // Put everything back into original bundle
        bundle.putAll(copy)
    }

    return SizeTree(
        keyName,
        sizeAsParcel(bundle),
        results,
        null // Root bundle itself doesn't have a single value
    )
}



/**
 * Measure the size of a typed [Bundle] when written to a [Parcel].
 *
 * @param bundle to measure
 * @return size when written to parcel in bytes
 */
fun sizeAsParcel(bundle: Bundle): Int {
    val parcel = Parcel.obtain()
    try {
        parcel.writeBundle(bundle)
        return parcel.dataSize()
    } finally {
        parcel.recycle()
    }
}

/**
 * Measure the size of a [Parcelable] when written to a [Parcel].
 *
 * @param parcelable to measure
 * @return size of parcel in bytes
 */
fun sizeAsParcel(parcelable: Parcelable): Int {
    val parcel = Parcel.obtain()
    try {
        parcel.writeParcelable(parcelable, 0)
        return parcel.dataSize()
    } finally {
        parcel.recycle()
    }
}
