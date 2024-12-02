package com.gu.toolargetool

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import java.io.Serializable
import java.util.*
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

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
fun sizeTreeFromBundle(bundle: Bundle, keyName: String = "Bundle"): SizeTree {
    val results = ArrayList<SizeTree>()
    val copy = Bundle(bundle)

    try {
        var bundleSize = sizeAsParcel(bundle)

        for (key in copy.keySet()) {
            val value = copy[key]
            bundle.remove(key)
            val newBundleSize = sizeAsParcel(bundle)
            val valueSize = bundleSize - newBundleSize
            bundleSize = newBundleSize

            val subTree = if (value is Bundle) {
                // Pass the current bundle to the recursive call
                sizeTreeFromBundle(value, key)
            } else {
                SizeTree(key, valueSize, emptyList(), value)
            }
            results.add(subTree)
        }
    } finally {
        bundle.putAll(copy)
    }

    // Check for Mavericks saved state within the current bundle
    if (copy.containsKey("mvrx:saved_instance_state") && copy.containsKey("mvrx:saved_state_class")) {
        return handleMvrxSavedInstanceState(
            keyName,
            copy,
            sizeAsParcel(bundle)
        )
    } else {
        return SizeTree(
            keyName,
            sizeAsParcel(bundle),
            results,
            null
        )
    }
}

fun handleMvrxSavedInstanceState(
    key: String,
    mvrxBundle: Bundle,
    valueSize: Int
): SizeTree {
    val mvrxStateClassValue = mvrxBundle.get("mvrx:saved_state_class")
    val mvrxStateClassName = when (mvrxStateClassValue) {
        is Class<*> -> mvrxStateClassValue.name
        is String -> mvrxStateClassValue.removePrefix("class ")
        else -> mvrxStateClassValue?.toString()?.removePrefix("class ")
    }

    if (mvrxStateClassName != null) {
        try {
            // Load the Mavericks state class using reflection
            val mvrxStateClass = Class.forName(mvrxStateClassName).kotlin

            // Get the primary constructor
            val primaryConstructor = mvrxStateClass.primaryConstructor
            if (primaryConstructor == null) {
                Log.e("TooLargeTool", "No primary constructor found for $mvrxStateClassName")
                return SizeTree(
                    key,
                    valueSize,
                    listOf(SizeTree("No primary constructor found for $mvrxStateClassName", 0, emptyList(), null)),
                    null
                )
            }

            // Get the parameter names in order
            primaryConstructor.isAccessible = true // Ensure we can access it
            val parameterNames = primaryConstructor.parameters.map { it.name ?: "unknown" }

            // Build the mapping from index to parameter name
            val indexToPropertyNameMap = parameterNames.mapIndexed { index, name ->
                index.toString() to name
            }.toMap()

            val mvrxSavedStateBundle = mvrxBundle.getBundle("mvrx:saved_instance_state")
            val newMvrxSavedStateBundle = Bundle()
            if (mvrxSavedStateBundle != null) {
                for (k in mvrxSavedStateBundle.keySet()) {
                    val propertyName = indexToPropertyNameMap[k] ?: k // If no match, keep original key
                    val v = mvrxSavedStateBundle.get(k)
                    when (v) {
                        is Bundle -> newMvrxSavedStateBundle.putBundle(propertyName, v)
                        is Parcelable -> newMvrxSavedStateBundle.putParcelable(propertyName, v)
                        is String -> newMvrxSavedStateBundle.putString(propertyName, v)
                        is Int -> newMvrxSavedStateBundle.putInt(propertyName, v)
                        is Long -> newMvrxSavedStateBundle.putLong(propertyName, v)
                        is Double -> newMvrxSavedStateBundle.putDouble(propertyName, v)
                        is Float -> newMvrxSavedStateBundle.putFloat(propertyName, v)
                        is Serializable -> newMvrxSavedStateBundle.putSerializable(propertyName, v)
                        else -> newMvrxSavedStateBundle.putString(propertyName, v.toString())
                    }
                }
            }

            val subTree = sizeTreeFromBundle(newMvrxSavedStateBundle, keyName = key)
            return SizeTree(
                key,
                valueSize,
                subTree.subTrees,
                null
            )
        } catch (e: Exception) {
            Log.e("TooLargeTool", "Error processing mvrx:saved_instance_state: ${e.message}", e)
            return SizeTree(
                key,
                valueSize,
                listOf(SizeTree("Error processing mvrx:saved_instance_state: ${e.message}", 0, emptyList(), null)),
                null
            )
        }
    } else {
        Log.w("TooLargeTool", "mvrx:saved_state_class not found or invalid")
        return SizeTree(
            key,
            valueSize,
            listOf(SizeTree("mvrx:saved_state_class not found", 0, emptyList(), null)),
            null
        )
    }
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
