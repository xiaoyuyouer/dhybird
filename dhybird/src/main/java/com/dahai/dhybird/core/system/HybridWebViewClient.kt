package com.dahai.dhybird.core.system

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dahai.dhybird.HybridController
import com.dahai.dhybird.bridge.BridgeRuntime

/** 处理文档生命周期、页面错误和电话链接。 */
class HybridWebViewClient(
    private val controller: HybridController,
    private val bridgeRuntime: BridgeRuntime
) : WebViewClient() {
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        bridgeRuntime.onPageStarted()
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        bridgeRuntime.onPageFinished()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handleUrl(view.context, request.url.toString())

    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        handleUrl(view.context, url)

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) controller.onPageLoadError()
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        controller.onPageLoadError()
    }

    private fun handleUrl(context: Context, url: String?): Boolean {
        if (url == null) return false
        if (url.startsWith("tel:")) {
            return try {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                true
            } catch (_: Exception) {
                controller.onPageLoadError()
                true
            }
        }
        return false
    }
}
