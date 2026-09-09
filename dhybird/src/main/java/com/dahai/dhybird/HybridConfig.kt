package com.dahai.dhybird

import android.app.Activity
import android.view.ViewGroup
import android.webkit.WebSettings
import com.dahai.dhybird.bridge.BridgeAccessPolicy
import java.lang.ref.WeakReference

/**
 * Hybrid 容器的不可变配置。
 *
 * Activity 和容器使用弱引用，避免 Controller 因配置对象反向持有页面生命周期对象。
 */
class HybridConfig private constructor(builder: Builder) {
    private val activity = WeakReference(builder.activity)
    private val container = WeakReference(builder.container)
    val url: String = builder.url!!
    val debug: Boolean = builder.debug
    val userAgentSuffix: String = builder.userAgentSuffix
    val cookies: Map<String, String> = builder.cookies.toMap()
    val bridgeAccessPolicy: BridgeAccessPolicy = builder.bridgeAccessPolicy
    val allowFileAccess: Boolean = builder.allowFileAccess
    val allowMixedContent: Boolean = builder.allowMixedContent
    val cacheMode: Int = builder.cacheMode

    fun getActivity(): Activity? = activity.get()

    fun getContainer(): ViewGroup? = container.get()

    /** 用于创建 HybridConfig，build() 会校验必填参数。 */
    class Builder(activity: Activity?) {
        var activity: Activity? = activity
            private set
        var container: ViewGroup? = null
            private set
        var url: String? = null
            private set
        var debug: Boolean = false
            private set
        var userAgentSuffix: String = ""
            private set
        var cookies: Map<String, String> = emptyMap()
            private set
        var bridgeAccessPolicy: BridgeAccessPolicy = BridgeAccessPolicy.allOrigins()
            private set
        var allowFileAccess: Boolean = false
            private set
        var allowMixedContent: Boolean = false
            private set
        var cacheMode: Int = WebSettings.LOAD_DEFAULT
            private set

        fun container(container: ViewGroup?) = apply { this.container = container }

        fun url(url: String?) = apply { this.url = url }

        fun debug(debug: Boolean) = apply { this.debug = debug }

        fun userAgentSuffix(userAgentSuffix: String?) = apply {
            this.userAgentSuffix = userAgentSuffix.orEmpty()
        }

        fun cookies(cookies: Map<String, String>?) = apply {
            this.cookies = cookies?.toMap().orEmpty()
        }

        fun bridgeAccessPolicy(bridgeAccessPolicy: BridgeAccessPolicy?) = apply {
            requireNotNull(bridgeAccessPolicy) { "bridgeAccessPolicy is required" }
            this.bridgeAccessPolicy = bridgeAccessPolicy
        }

        fun allowFileAccess(allowFileAccess: Boolean) = apply {
            this.allowFileAccess = allowFileAccess
        }

        fun allowMixedContent(allowMixedContent: Boolean) = apply {
            this.allowMixedContent = allowMixedContent
        }

        fun cacheMode(cacheMode: Int) = apply { this.cacheMode = cacheMode }

        /** 校验 Activity、容器和 URL，并生成不可变配置。 */
        fun build(): HybridConfig {
            requireNotNull(activity) { "activity is required" }
            requireNotNull(container) { "container is required" }
            require(!url.isNullOrBlank()) { "url is required" }
            return HybridConfig(this)
        }
    }
}
