package com.dahai.dhybird

import android.net.Uri
import android.webkit.ValueCallback

interface HybridCallbacks {
    fun onPageLoadProgress(progress: Int) {}

    fun onTitleChanged(title: String?) {}

    fun onPageLoadError() {}

    fun onDocumentDownloaded(path: String?) {}

    fun onSelectFile(callback: ValueCallback<Array<Uri>>?) {
        callback?.onReceiveValue(null)
    }
}
