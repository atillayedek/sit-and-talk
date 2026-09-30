package com.sitandtalk.core.network

import android.util.Log

/**
 * Developer logging that never prints tokens, e-mails or message bodies: only codes and exception
 * class names. Silent in release builds.
 */
object SafeLog {
    @Volatile
    var enabled: Boolean = false

    fun error(tag: String, code: String, throwable: Throwable? = null) {
        if (!enabled) return
        Log.w("SitAndTalk/$tag", "error=$code type=${throwable?.javaClass?.simpleName}")
    }

    fun info(tag: String, message: String) {
        if (!enabled) return
        Log.i("SitAndTalk/$tag", message)
    }
}
