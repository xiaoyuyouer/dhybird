package com.dahai.dhybird

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.dahai.dhybird.bridge.BridgeRuntime
import com.dahai.dhybird.bridge.PluginRegistry
import com.dahai.dhybird.core.system.DNativeWebChromeClient
import com.dahai.dhybird.core.system.FullscreenHolder
import com.dahai.dhybird.core.system.HybridDownloadHandler
import com.dahai.dhybird.core.system.HybridWebViewClient
import com.dahai.dhybird.core.system.IWebPageView
import org.json.JSONObject

/**
 * Hybrid WebView 的生命周期控制器。
 *
 * 负责 WebView 创建、Bridge 安装、插件注册、导航、全屏视频和销毁流程；
 * 具体的消息解析和插件分发交给 BridgeRuntime/PluginRegistry。
 */
class HybridController(
    private val config: HybridConfig,
    private val callbacks: HybridCallbacks? = null
) : IWebPageView {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pluginRegistry = PluginRegistry()
    private var webView: WebView? = null
    private var bridgeRuntime: BridgeRuntime? = null
    private var videoFullView: FrameLayout? = null
    private var destroyed = false

    init {
        requireNotNull(config) { "config is required" }
    }

    /** 在主线程创建 WebView，安装 Bridge，并加载配置中的 URL。 */
    fun start() {
        runOnMain { startOnMain() }
    }

    @SuppressLint("RequiresFeature")
    private fun startOnMain() {
        if (destroyed || webView != null) return

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG && config.debug)
        val activity = config.getActivity()
            ?: throw IllegalStateException("Hybrid host has been released before start")
        val container = config.getContainer()
            ?: throw IllegalStateException("Hybrid host has been released before start")
        val currentWebView = WebView(activity)
        webView = currentWebView
        configureWebView(currentWebView)

        val runtime = BridgeRuntime(currentWebView, pluginRegistry)
        bridgeRuntime = runtime

        currentWebView.webViewClient = HybridWebViewClient(
            this,
            runtime
        )
        currentWebView.webChromeClient = DNativeWebChromeClient(this)
        currentWebView.setDownloadListener(HybridDownloadHandler(activity))
        installBridgeTransport(currentWebView, runtime)
        container.addView(
            currentWebView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        applyCookies(config.url, config.cookies)
        currentWebView.loadUrl(config.url)
    }

    /**
     * 安装 H5 到 Native 的消息入口。
     * v2 只使用 WebMessageListener；运行环境不支持时直接失败，不再保留旧回退。
     */
    private fun installBridgeTransport(currentWebView: WebView, runtime: BridgeRuntime) {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "This v2 runtime requires WebMessageListener support"
        }
        WebViewCompat.addWebMessageListener(
            currentWebView,
            BRIDGE_NAME,
            config.bridgeAccessPolicy.allowedOriginRules,
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy
                ) {
                    message.data?.let { runtime.postMessage(it) }
                }
            }
        )
    }

    @SuppressLint("WrongConstant")
    private fun configureWebView(view: WebView) {
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.cacheMode = config.cacheMode
        settings.domStorageEnabled = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowFileAccess = config.allowFileAccess
        settings.mixedContentMode = if (config.allowMixedContent) {
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        } else {
            WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        if (config.userAgentSuffix.isNotEmpty()) {
            settings.userAgentString = settings.userAgentString + config.userAgentSuffix
        }
    }

    private fun applyCookies(url: String, cookies: Map<String, String>) {
        val manager = CookieManager.getInstance()
        cookies.forEach { (key, value) -> manager.setCookie(url, "$key=$value") }
        manager.flush()
    }

    fun getWebView(): WebView? = webView

    fun getPluginRegistry(): PluginRegistry = pluginRegistry

    /** 注册宿主 App 自定义插件，必须在需要调用前完成注册。 */
    fun registerPlugin(plugin: com.dahai.dhybird.bridge.BridgePlugin) {
        pluginRegistry.register(plugin)
    }

    /** 在主线程导航到新 URL；页面切换会重新建立当前文档的 Bridge 状态。 */
    fun navigate(url: String?) {
        runOnMain {
            if (!destroyed && webView != null && !url.isNullOrBlank()) {
                webView?.loadUrl(url)
            }
        }
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true

    fun goBack() {
        webView?.takeIf { it.canGoBack() }?.goBack()
    }

    fun onResume() {
        webView?.onResume()
    }

    fun onPause() {
        webView?.onPause()
    }

    /** 停止加载、取消 Bridge、移除 WebView 并释放页面相关引用。 */
    fun destroy() {
        runOnMain { destroyOnMain() }
    }

    private fun destroyOnMain() {
        if (destroyed) return
        destroyed = true
        bridgeRuntime?.destroy()
        bridgeRuntime = null

        webView?.let { currentWebView ->
            currentWebView.stopLoading()
            WebViewCompat.removeWebMessageListener(currentWebView, BRIDGE_NAME)
            currentWebView.webChromeClient = null
            currentWebView.webViewClient = WebViewClient()
            (currentWebView.parent as? ViewGroup)?.removeView(currentWebView)
            currentWebView.removeAllViews()
            currentWebView.destroy()
        }
        webView = null

        videoFullView?.let { fullView ->
            (fullView.parent as? ViewGroup)?.removeView(fullView)
        }
        videoFullView = null
    }

    /** 向当前 H5 页面发送一个 Native -> H5 事件。 */
    fun sendEventMessageToJS(eventName: String, params: JSONObject?) {
        bridgeRuntime?.sendEvent(eventName, params)
    }

    fun onPageLoadError() {
        callbacks?.onPageLoadError()
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    override fun startProgress(newProgress: Int) {
        callbacks?.onPageLoadProgress(newProgress)
    }

    override fun showWebView() {
        webView?.visibility = View.VISIBLE
    }

    override fun hindWebView() {
        webView?.visibility = View.INVISIBLE
    }

    override fun fullViewAddView(view: View) {
        val activity = config.getActivity() ?: return
        val decor = activity.window.decorView as? FrameLayout ?: return
        videoFullView = FullscreenHolder(activity).also { holder ->
            holder.addView(view)
            decor.addView(holder)
        }
    }

    override fun showVideoFullView() {
        videoFullView?.visibility = View.VISIBLE
    }

    override fun hindVideoFullView() {
        videoFullView?.visibility = View.GONE
    }

    override fun setRequestedOrientation(screenOrientationPortrait: Int) {
        config.getActivity()?.requestedOrientation = screenOrientationPortrait
    }

    override fun getVideoFullView(): FrameLayout? = videoFullView

    override fun getVideoLoadingProgressView(): View {
        val activity = config.getActivity()
            ?: throw IllegalStateException("Activity has been released")
        return LayoutInflater.from(activity).inflate(
            R.layout.video_loading_progress,
            webView,
            false
        )
    }

    override fun docDownloadFinish(path: String?) {
        callbacks?.onDocumentDownloaded(path)
    }

    override fun onLoadError() {
        onPageLoadError()
    }

    override fun selectFile(valueCallback: ValueCallback<Array<Uri>>?) {
        callbacks?.onSelectFile(valueCallback) ?: valueCallback?.onReceiveValue(null)
    }

    override fun webViewTitle(title: String?) {
        callbacks?.onTitleChanged(title)
    }

    private companion object {
        const val BRIDGE_NAME = "android"
    }
}
