package com.dahai.dhybird.bridge

import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 显式插件注册表。
 *
 * 通过插件名查找实例，不使用 Class.forName、JSON 配置或运行时反射。
 */
class PluginRegistry {
    private val plugins = ConcurrentHashMap<String, BridgePlugin>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor: ExecutorService = Executors.newCachedThreadPool()

    /** 注册或替换同名插件。 */
    fun register(plugin: BridgePlugin?) {
        require(plugin != null && plugin.name().trim().isNotEmpty()) {
            "plugin and plugin name are required"
        }
        plugins[plugin.name()] = plugin
    }

    /** 移除指定插件；不存在时不报错。 */
    fun unregister(name: String?) {
        if (name != null) plugins.remove(name)
    }

    fun contains(name: String?): Boolean = name != null && plugins.containsKey(name)

    fun names(): Set<String> = Collections.unmodifiableSet(plugins.keys)

    /** 根据请求中的插件名查找并按插件声明的线程执行。 */
    fun dispatch(request: BridgeRequest, responder: BridgeResponder) {
        val plugin = plugins[request.plugin]
        if (plugin == null) {
            responder.failure("PLUGIN_NOT_FOUND", "No plugin registered for ${request.plugin}")
            return
        }

        val task = Runnable {
            try {
                plugin.execute(request, responder)
            } catch (error: Throwable) {
                responder.failure("PLUGIN_EXECUTION_FAILED", safeMessage(error))
            }
        }

        when (plugin.executionThread()) {
            BridgePlugin.ExecutionThread.CALLER -> task.run()
            BridgePlugin.ExecutionThread.BACKGROUND -> backgroundExecutor.execute(task)
            BridgePlugin.ExecutionThread.MAIN -> {
                if (Looper.myLooper() == Looper.getMainLooper()) task.run() else mainHandler.post(task)
            }
        }
    }

    /** 停止后台线程池并清空插件，Controller 销毁时调用。 */
    fun shutdown() {
        backgroundExecutor.shutdownNow()
        plugins.clear()
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotEmpty() } ?: error.javaClass.simpleName
}
