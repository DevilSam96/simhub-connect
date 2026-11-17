package com.local.simhubconnect.net

import android.content.Context

/** Last used server, SimHub port and last dashboard opened on that server. */
class ServerStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("simhub_connect", Context.MODE_PRIVATE)

    var lastPort: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    val lastHost: String? get() = prefs.getString(KEY_HOST, null)

    val lastDashboardUrl: String? get() = prefs.getString(KEY_DASH_URL, null)

    /** Remembers the server and forgets the dashboard when the server changes. */
    fun rememberServer(host: String, port: Int) {
        val sameServer = prefs.getString(KEY_HOST, null) == host &&
            prefs.getInt(KEY_PORT, DEFAULT_PORT) == port
        val editor = prefs.edit().putString(KEY_HOST, host).putInt(KEY_PORT, port)
        if (!sameServer) editor.remove(KEY_DASH_URL)
        editor.apply()
    }

    fun saveDashboardUrl(url: String) {
        prefs.edit().putString(KEY_DASH_URL, url).apply()
    }

    companion object {
        const val DEFAULT_PORT = 8888

        private const val KEY_PORT = "port"
        private const val KEY_HOST = "last_host"
        private const val KEY_DASH_URL = "last_dash_url"
    }
}
