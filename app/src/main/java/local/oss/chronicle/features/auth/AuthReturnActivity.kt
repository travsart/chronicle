package local.oss.chronicle.features.auth

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import local.oss.chronicle.application.MainActivity
import timber.log.Timber

/**
 * Transparent Activity that handles deep link callbacks from OAuth browser flow.
 *
 * This activity receives the `https://auth.chronicleapp.net/callback` App Link (or
 * `chronicle://auth/callback` as legacy fallback) after the user completes authentication
 * in Chrome Custom Tabs. It notifies the auth coordinator to expedite polling,
 * brings MainActivity to the foreground, and immediately finishes.
 *
 * **Deep Link Formats:**
 * - Primary: `https://auth.chronicleapp.net/callback` (Android App Link - auto-closes CCT)
 * - Fallback: `chronicle://auth/callback` (custom scheme for legacy support)
 *
 * **Launch Mode:** `singleTask`
 * - Ensures only one instance exists
 * - New intents are delivered via [onNewIntent]
 * - Prevents multiple instances from deep links
 *
 * **Activity Flags:**
 * - `excludeFromRecents="true"` - Does not appear in recent apps list
 * - `noHistory="true"` - Not kept in back stack
 * - `exported="true"` - Can receive external intents (deep links)
 *
 * **User Experience:**
 * - User completes OAuth in browser
 * - Plex redirects to `https://auth.chronicleapp.net/callback`
 * - Android App Links intercept the URL and open this activity
 * - This activity receives deep link, notifies coordinator
 * - MainActivity is brought to foreground (pushing CCT behind it)
 * - This activity finishes, revealing MainActivity
 * - User sees seamless return to Chronicle app with login flow continuing
 */
class AuthReturnActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.i("AuthReturnActivity onCreate - deep link received")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        Timber.i("AuthReturnActivity onNewIntent - deep link received")
        handleIntent(intent)
    }

    /**
     * Handles the deep link intent.
     *
     * Extracts the URI from the intent, notifies the coordinator singleton,
     * launches MainActivity to bring the app to the foreground, and finishes.
     *
     * **Why launch MainActivity?**
     * Chrome Custom Tabs don't auto-close when navigating away via deep links.
     * Explicitly launching MainActivity with CLEAR_TOP | SINGLE_TOP brings the
     * existing MainActivity instance to the foreground, pushing the CCT behind it.
     * This is the standard Android pattern for OAuth redirect activities.
     *
     * @param intent The intent containing the deep link URI
     */
    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data

        if (uri != null) {
            Timber.d("Deep link URI: $uri")

            // Notify coordinator that browser returned
            // This triggers expedited polling for faster auth completion
            AuthCoordinatorSingleton.onBrowserReturned()
        } else {
            Timber.w("AuthReturnActivity received intent with no data URI")
        }

        // Launch MainActivity to bring the app to the foreground over the Chrome Custom Tab,
        // which doesn't auto-close when navigating away via deep links
        val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(mainActivityIntent)

        // Immediately finish - we don't need to show any UI
        // This removes AuthReturnActivity from the back stack
        finish()
    }
}
