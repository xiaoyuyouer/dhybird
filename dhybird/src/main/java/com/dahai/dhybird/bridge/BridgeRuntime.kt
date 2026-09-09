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
    private val pendingResponses: Queue<PendingScript> = ArrayDeque()
    private var webView: WebView? = webView
    private var state = State.CREATED
    private var documentGeneration = 0L

    @Synchronized
    fun getState(): State = state

    /** 页面开始加载，清理上一个文档遗留的待发送响应。 */
    fun onPageStarted() {
        synchronized(this) {
            if (state == State.DESTROYED) return
            state = State.DOCUMENT_LOADING
            documentGeneration += 1
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
        val generation = synchronized(this) {
            if (state == State.DESTROYED) return
            documentGeneration
        }
        val request = try {
            BridgeRequest.parse(rawMessage)
        } catch (error: JSONException) {
            sendError(
                extractCallbackId(rawMessage),
                generation,
                "INVALID_MESSAGE",
                error.message
            )
            return
        }
        pluginRegistry.dispatch(request, Response(request.callbackId, generation))
    }

    /** 向 H5 发送独立事件，不复用请求响应的 callbackId。 */
    fun sendEvent(eventName: String, data: JSONObject?) {
        if (eventName.isBlank()) return
        val generation = synchronized(this) {
            if (state == State.DESTROYED) return
            documentGeneration
        }
        val response = JSONObject()
        try {
            response.put("type", EVENT_TYPE)
            response.put("eventName", eventName.trim())
            response.put("data", data ?: JSONObject.NULL)
        } catch (_: JSONException) {
            return
        }
        enqueueOrEvaluate("window.__dhybirdHandleEvent($response);", generation)
    }

    /** 标记为销毁状态，丢弃队列并停止插件后台执行器。 */
    fun destroy() {
        synchronized(this) {
            state = State.DESTROYED
            documentGeneration += 1
            pendingResponses.clear()
            webView = null
        }
        pluginRegistry.shutdown()
    }

    private fun sendError(
        callbackId: String,
        generation: Long,
        errorCode: String,
        errorMessage: String?
    ) {
        Response(callbackId, generation).failure(errorCode, errorMessage ?: errorCode)
    }

    private fun flushResponses() {
        while (true) {
            val pending = synchronized(this) {
                if (state != State.BRIDGE_READY || pendingResponses.isEmpty()) return
                pendingResponses.poll() ?: return
            }
            evaluateOnMain(pending.script, pending.generation)
        }
    }

    private fun enqueueOrEvaluate(script: String, generation: Long) {
        synchronized(this) {
            if (state == State.DESTROYED || generation != documentGeneration) return
            if (state != State.BRIDGE_READY) {
                if (pendingResponses.size >= MAX_PENDING_RESPONSES) pendingResponses.poll()
                pendingResponses.offer(PendingScript(generation, script))
                return
            }
        }
        evaluateOnMain(script, generation)
    }

    private fun evaluateOnMain(script: String, generation: Long) {
        mainHandler.post {
            val currentWebView = synchronized(this) {
                if (state == State.DESTROYED || generation != documentGeneration) {
                    null
                } else {
                    webView
                }
            } ?: return@post
            currentWebView.evaluateJavascript(script, null)
        }
    }

    private fun extractCallbackId(rawMessage: String?): String {
        if (rawMessage.isNullOrBlank()) return ""
        return try {
            JSONObject(rawMessage).optString("callbackId", "").trim()
        } catch (_: JSONException) {
            ""
        }
    }

    private data class PendingScript(
        val generation: Long,
        val script: String
    )

    private inner class Response(
        private val callbackId: String,
        private val generation: Long
    ) : BridgeResponder {
        override fun success(data: JSONObject?) {
            success(data, true)
        }

        override fun success(data: JSONObject?, complete: Boolean) {
            respondSuccess(data, complete)
        }

        override fun failure(errorCode: String, errorMessage: String?) {
            failure(errorCode, errorMessage, true)
        }

        override fun failure(errorCode: String, errorMessage: String?, complete: Boolean) {
            respondFailure(complete, errorCode, errorMessage)
        }

        private fun respondSuccess(data: JSONObject?, complete: Boolean) {
            respond(true, complete, null, null, data)
        }

        private fun respondFailure(complete: Boolean, errorCode: String, errorMessage: String?) {
            respond(false, complete, errorCode, errorMessage, null)
        }

        private fun respond(
            success: Boolean,
            complete: Boolean,
            errorCode: String?,
            errorMessage: String?,
            data: JSONObject?
        ) {
            val response = JSONObject()
            val safeErrorCode = errorCode?.trim().takeIf { !it.isNullOrEmpty() } ?: "NATIVE_ERROR"
            val safeErrorMessage = errorMessage
                ?.takeIf { it.isNotEmpty() }
                ?: safeErrorCode
            try {
                response.put("callbackId", callbackId)
                response.put("status", if (success) 1 else 0)
                response.put("complete", if (complete) 1 else 0)
                response.put(
                    "errorCode",
                    if (success) JSONObject.NULL else safeErrorCode
                )
                response.put(
                    "errorMessage",
                    if (success) JSONObject.NULL else safeErrorMessage
                )
                response.put(
                    "data",
                    if (success) data ?: JSONObject.NULL else JSONObject.NULL
                )
            } catch (_: JSONException) {
                return
            }
            enqueueOrEvaluate("window.__dhybirdHandleResponse($response);", generation)
        }
    }

    private companion object {
        const val MAX_PENDING_RESPONSES = 100
        const val EVENT_TYPE = "event"
    }
}
