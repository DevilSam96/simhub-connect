package com.local.simhubconnect

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.local.simhubconnect.settings.AppSettings

/**
 * Activity that applies the accent overlay chosen in the settings and follows
 * it when the user changes it while the screen is on the back stack.
 */
abstract class ThemedActivity : AppCompatActivity() {

    protected open val themedStyle: Int
        get() = AppSettings.themeRes(this)

    private var appliedAccent = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(themedStyle)
        super.onCreate(savedInstanceState)
        // Only safe after the activity is attached to its base context.
        appliedAccent = AppSettings.appAccent(this)
    }

    override fun onResume() {
        super.onResume()
        // The accent is a theme overlay, so a change needs a fresh activity.
        if (appliedAccent != AppSettings.appAccent(this)) {
            appliedAccent = AppSettings.appAccent(this)
            recreate()
        }
    }
}
