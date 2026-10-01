package io.github.waph1.syncer.youtube

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import io.github.waph1.syncer.R
import io.github.waph1.syncer.appContainer
import io.github.waph1.syncer.ui.theme.SyncerTheme

/**
 * Google sign-in to YouTube in a WebView. As soon as the youtube.com session cookies exist they
 * are saved (encrypted) for yt-dlp and the WebView's cookies are wiped, so that the WebView never
 * uses (and rotates) that session again. If Google refuses the sign-in in a WebView, the
 * cookies.txt import in the settings is the alternative.
 */
class YouTubeLoginActivity : ComponentActivity() {
    private var done = false

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) CookieManager.getInstance().removeAllCookies(null)
        setContent {
            SyncerTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.youtube_login_title)) },
                            navigationIcon = {
                                IconButton(onClick = ::finish) { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) }
                            },
                        )
                    },
                ) { padding ->
                    Column(Modifier.padding(padding)) {
                        Text(
                            stringResource(R.string.youtube_login_hint),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        AndroidView(
                            factory = { context -> createWebView(context) },
                            onRelease = { it.destroy() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    // Google's sign-in page needs JavaScript and DOM storage.
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: android.content.Context) = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val host = url?.toUri()?.host.orEmpty()
                if (host == "youtube.com" || host.endsWith(".youtube.com")) checkSignedIn()
            }
        }
        loadUrl(LOGIN_URL)
    }

    private fun checkSignedIn() {
        if (done) return
        val header = CookieManager.getInstance().getCookie("https://www.youtube.com") ?: return
        if (!NetscapeCookies.headerHasYouTubeSession(header)) return
        done = true
        val saved = runCatching {
            appContainer.youtubeAccount.saveCookies(NetscapeCookies.fromCookieHeader(header, System.currentTimeMillis()))
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        if (saved.isSuccess) {
            setResult(RESULT_OK)
        } else {
            Toast.makeText(this, saved.exceptionOrNull()?.message ?: getString(R.string.youtube_login_failed), Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private companion object {
        /** YouTube's own sign-in entry point; returns to the mobile site once signed in. */
        const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26hl%3Dit" +
            "%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"
    }
}
