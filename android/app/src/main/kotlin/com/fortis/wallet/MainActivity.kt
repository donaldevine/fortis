package com.fortis.wallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // debug-only: `adb shell am start -n rest.fortis.wallet/.MainActivity --ez crash true`
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("crash", false) == true) {
            throw RuntimeException("test crash via intent")
        }
        setContent { FortisApp() }
    }
}
