package com.dahai.dhybird.core.system

import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/** 处理标题、文件选择和 HTML5 视频全屏等 WebChromeClient 回调。 */
class DNativeWebChromeClient(
    private val webPageView: IWebPageView?
) : WebChromeClient() {
    private var progressVideo: View? = null
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        webPageView?.startProgress(newProgress)
    }

    override fun onShowFileChooser(
        webView: WebView,
        valueCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        webPageView?.selectFile(valueCallback) ?: valueCallback.onReceiveValue(null)
        return true
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback?) {
        val pageView = webPageView ?: return
        pageView.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        pageView.hindWebView()
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        pageView.fullViewAddView(view)
        customView = view
        customViewCallback = callback
        pageView.showVideoFullView()
    }

    override fun onHideCustomView() {
        val pageView = webPageView ?: return
        val currentView = customView ?: return
        pageView.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        currentView.visibility = View.GONE
        pageView.getVideoFullView()?.removeView(currentView)
        customView = null
        pageView.hindVideoFullView()
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        pageView.showWebView()
    }

    override fun getVideoLoadingProgressView(): View? {
        if (progressVideo == null) {
            progressVideo = webPageView?.getVideoLoadingProgressView()
        }
        return progressVideo
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        super.onReceivedTitle(view, title)
        webPageView?.webViewTitle(title)
    }

    fun inCustomView(): Boolean = customView != null
}
