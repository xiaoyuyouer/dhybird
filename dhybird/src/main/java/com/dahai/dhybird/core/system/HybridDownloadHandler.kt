package com.dahai.dhybird.core.system

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil

/** 使用系统 DownloadManager 处理 WebView 下载，自动带上 UA 和 Cookie。 */
class HybridDownloadHandler(context: Context) : DownloadListener {
    private val context = context.applicationContext

    override fun onDownloadStart(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        if (url.isNullOrBlank()) return
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url))
        request.setMimeType(mimeType)
        request.setTitle(fileName)
        request.setDescription("正在下载")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (!userAgent.isNullOrEmpty()) request.addRequestHeader("User-Agent", userAgent)
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotEmpty() }?.let {
            request.addRequestHeader("Cookie", it)
        }
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)?.enqueue(request)
    }
}
