package com.maxrave.simpmusic.expect.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.distantPast
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.WKWebsiteDataTypeCookies
import platform.darwin.NSObject

// Google refuses sign-in inside a WKWebView whose user agent lacks the "Safari" token
// ("disallowed_useragent"), so the view presents itself as mobile Safari.
private const val SAFARI_USER_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

private data class CookieEntry(
    val name: String,
    val value: String,
    val domain: String,
)

/**
 * WKHTTPCookieStore only answers asynchronously, while [WebViewCookieManager.getCookie] is
 * synchronous. The jar is refreshed every time a page finishes, BEFORE `onPageFinished` is called,
 * and callers read cookies inside that callback, so they always see the cookies of the page that
 * just finished loading.
 */
private object IosCookieJar {
    var entries: List<CookieEntry> = emptyList()

    fun cookieHeader(url: String): String {
        val host = NSURL.URLWithString(url)?.host?.lowercase() ?: return ""
        return entries
            .filter { entry ->
                val domain = entry.domain.trimStart('.').lowercase()
                host == domain || host.endsWith(".$domain")
            }.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun refresh(done: () -> Unit) {
        WKWebsiteDataStore.defaultDataStore().httpCookieStore.getAllCookies { cookies ->
            entries =
                cookies
                    .orEmpty()
                    .filterIsInstance<NSHTTPCookie>()
                    .map { CookieEntry(it.name, it.value, it.domain) }
            done()
        }
    }

    fun clear(done: () -> Unit) {
        entries = emptyList()
        WKWebsiteDataStore.defaultDataStore().removeDataOfTypes(
            dataTypes = setOf(WKWebsiteDataTypeCookies),
            modifiedSince = NSDate.distantPast,
            completionHandler = done,
        )
    }
}

actual fun createWebViewCookieManager(): WebViewCookieManager =
    object : WebViewCookieManager {
        override fun getCookie(url: String): String = IosCookieJar.cookieHeader(url)

        override fun removeAllCookies() {
            IosCookieJar.clear {}
        }
    }

private class PageDelegate(
    var onStarted: () -> Unit,
    var onFinished: (String) -> Unit,
) : NSObject(),
    WKNavigationDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        didStartProvisionalNavigation: WKNavigation?,
    ) {
        onStarted()
    }

    override fun webView(
        webView: WKWebView,
        didFinishNavigation: WKNavigation?,
    ) {
        val url = webView.URL?.absoluteString ?: return
        IosCookieJar.refresh { onFinished(url) }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformWebView(
    state: MutableState<WebViewState>,
    initUrl: String,
    aboveContent: @Composable (BoxScope.() -> Unit),
    clearCookies: Boolean,
    onPageFinished: (String) -> Unit,
) {
    val latestOnPageFinished = rememberUpdatedState(onPageFinished)
    // WKWebView keeps its navigationDelegate weakly, so the delegate lives as long as the composition.
    val delegate =
        remember {
            PageDelegate(
                onStarted = { state.value = WebViewState.Loading(0) },
                onFinished = { url ->
                    state.value = WebViewState.Finished
                    latestOnPageFinished.value(url)
                },
            )
        }
    // Once per visit, like Android: recomposition must not drop a sign-in that already finished.
    val cookiesCleared = remember { booleanArrayOf(false) }

    Box {
        UIKitView(
            factory = {
                val webView =
                    WKWebView(
                        frame = CGRectZero.readValue(),
                        configuration = WKWebViewConfiguration(),
                    )
                webView.navigationDelegate = delegate
                webView.customUserAgent = SAFARI_USER_AGENT
                webView.allowsBackForwardNavigationGestures = true

                val load = {
                    NSURL.URLWithString(initUrl)?.let { webView.loadRequest(NSURLRequest.requestWithURL(it)) }
                    Unit
                }
                if (clearCookies && !cookiesCleared[0]) {
                    cookiesCleared[0] = true
                    IosCookieJar.clear { load() }
                } else {
                    load()
                }
                webView
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { webView ->
                webView.stopLoading()
                webView.navigationDelegate = null
            },
        )
        aboveContent()
    }
}
