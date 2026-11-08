package au.net.maz.privacyai

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import au.net.maz.privacyai.ui.AppRoot
import au.net.maz.privacyai.ui.theme.PrivacyTheme

class MainActivity : ComponentActivity() {

    private val app get() = application as PrivacyApp

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Blank in the recent-apps view, and no screenshots of app content.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            PrivacyTheme {
                AppRoot(app, onLocked = { viewModelStore.clear() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.session.checkInactivity()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        app.session.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        app.session.touch()
        return super.dispatchKeyEvent(event)
    }
}
