package com.dahai.dhybird.core.system

import android.net.Uri
import android.view.View
import android.webkit.ValueCallback
import android.widget.FrameLayout

interface IWebPageView {
    fun startProgress(newProgress: Int)
    fun showWebView()
    fun hindWebView()
    fun fullViewAddView(view: View)
    fun showVideoFullView()
    fun hindVideoFullView()
    fun setRequestedOrientation(screenOrientationPortrait: Int)
    fun getVideoFullView(): FrameLayout?
    fun getVideoLoadingProgressView(): View
    fun docDownloadFinish(path: String?)
    fun onLoadError()
    fun selectFile(valueCallback: ValueCallback<Array<Uri>>?)
    fun webViewTitle(title: String?)
}
