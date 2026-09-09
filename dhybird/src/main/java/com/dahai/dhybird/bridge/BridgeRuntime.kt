package com.dahai.dhybird.bridge

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONException
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Queue

/**
 * Bridge 的运行时状态机和 Native -> H5 响应通道。
 *
 * H5 可以在页面脚本阶段提前发起请求；页面尚未完成时，Native 响应会暂存在队列中，
 * 页面完成后再按顺序通过 evaluateJavascript 回传。
 */
class BridgeRuntime(
    webView: WebView,
    private val pluginRegistry: PluginRegistry
) {
    /** 当前文档对应的 Bridge 生命周期状态。 */
    enum class State {
        CREATED,
        DOCUMENT_LOADING,
        BRIDGE_READY,
        DESTROYED
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingResponses: Queue<String> = ArrayDeque()
    private var webView: WebView? = webView
    private var state = State.CREATED

    @Synchronized
    fun getState(): State = state

    /** 页面开始加载，清理上一个文档遗留的待发送响应。 */
    fun onPageStarted() {
        synchronized(this) {
            if (state == State.DESTROYED) return
            state = State.DOCUMENT_LOADING
            pendingResponses.clear()
        }
    }

    /** 页面加载完成，允许把 Native 响应刷回 H5。 */
    fun onPageFinished() {
        synchronized(this) {
            if (state == State.DESTROYED) return
            state = State.BRIDGE_READY
        }
        flushResponses()
    }

    /** 接收来自 WebMessageListener 的异步 H5 请求。 */
    fun postMessage(rawMessage: String?) {
        synchronized(this) {
            if (state == State.DESTROYED) return
        }
        val request = try {
            BridgeRequest.parse(rawMessage)
        } catch (error: JSONException) {
            sendError("", "INVALID_MESSAGE", error.message)
            return
        }
        pluginRegistry.dispatch(request, Response(request.callbackId))
    }

    /** 向 H5 发送连续事件，不依赖某个请求的 callbackId。 */
    fun sendEvent(eventName: String?, data: JSONObject?) {
        if (eventName.isNullOrBlank()) return
        val response = JSONObject()
        try {
            response.put("callbackId", eventName)
            response.put("status", 1)
            response.put("complete", 0)
            response.put("errorMessage", JSONObject.NULL)
            response.put("data", data ?: JSONObject.NULL)
        } catch (_: JSONException) {
            return
        }
        enqueueOrEvaluate("window.__dhybirdHandleEvent($response);")
    }

    /** 标记为销毁状态，丢弃队列并停止插件后台执行器。 */
    fun destroy() {
        synchronized(this) {
            state = State.DESTROYED
            pendingResponses.clear()
            webView = null
        }
        pluginRegistry.shutdown()
    }

    private fun sendError(callbackId: String, errorCode: String, errorMessage: String?) {
        Response(callbackId).failure(errorCode, errorMessage ?: errorCode)
    }

    private fun flushResponses() {
        while (true) {
            val script = synchronized(this) {
                if (state != State.BRIDGE_READY || pendingResponses.isEmpty()) return
                pendingResponses.poll() ?: return
            }
            evaluateOnMain(script)
        }
    }

    private fun enqueueOrEvaluate(script: String) {
        synchronized(this) {
            if (state == State.DESTROYED) return
            if (state != State.BRIDGE_READY) {
                if (pendingResponses.size >= MAX_PENDING_RESPONSES) pendingResponses.poll()
                pendingResponses.offer(script)
                return
            }
        }
        evaluateOnMain(script)
    }

    private fun evaluateOnMain(script: String) {
        mainHandler.post {
            val currentWebView = synchronized(this) {
                if (state == State.DESTROYED) null else webView
            } ?: return@post
            currentWebView.evaluateJavascript(script, null)
        }
    }

    private inner class Response(private val callbackId: String?) : BridgeResponder {
        override fun success(data: JSONObject?) {
            success(data, true)
        }

        override fun success(data: JSONObject?, complete: Boolean) {
            respond(true, complete, "", data)
        }

        override fun failure(errorCode: String?, errorMessage: String?) {
            failure(errorCode, errorMessage, true)
        }

        override fun failure(errorCode: String?, errorMessage: String?, complete: Boolean) {
            respond(false, complete, errorCode, errorMessage ?: "")
        }

        private fun respond(
            success: Boolean,
            complete: Boolean,
            errorCode: String?,
            errorMessageOrData: Any?
        ) {
            val response = JSONObject()
            try {
                response.put("callbackId", callbackId ?: "")
                response.put("status", if (success) 1 else 0)
                response.put("complete", if (complete) 1 else 0)
                response.put("errorCode", if (success) JSONObject.NULL else errorCode)
                response.put("errorMessage", if (success) JSONObject.NULL else errorMessageOrData)
                response.put(
                    "data",
                    if (success && errorMessageOrData is JSONObject) errorMessageOrData else JSONObject.NULL
                )
            } catch (_: JSONException) {
                return
            }
            enqueueOrEvaluate("window.__dhybirdHandleResponse($response);")
        }
    }

    private companion object {
        const val MAX_PENDING_RESPONSES = 100
    }
}
