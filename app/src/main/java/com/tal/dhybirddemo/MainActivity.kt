package com.tal.dhybirddemo

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.ValueCallback
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.dahai.dhybird.HybridCallbacks
import com.dahai.dhybird.HybridConfig
import com.dahai.dhybird.HybridController
import com.tal.dhybirddemo.plugin.DemoCheckAvailablePlugin
import com.tal.dhybirddemo.plugin.DemoDeviceInfoPlugin
import com.tal.dhybirddemo.plugin.DemoToastPlugin

class MainActivity : AppCompatActivity(), HybridCallbacks {

    private lateinit var containerView: FrameLayout

    private var hybridController: HybridController? = null

    private val testUrl = "file:///android_asset/dhybird/react-demo.html"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        containerView = findViewById(R.id.layout_fl)
        initHybrid()
    }

    private fun initHybrid() {
        val cookieMap = HashMap<String,String>()
        cookieMap["test11"] = "232"
        cookieMap["test12"] = "433"

        val config = HybridConfig.Builder(this)
            .container(containerView)
            .cookies(cookieMap)
            .debug(true)
            .allowFileAccess(true)
            .url(testUrl)
            .build()
        hybridController = HybridController(config, this).also { controller ->
            controller.registerPlugin(DemoToastPlugin(this))
            controller.registerPlugin(DemoCheckAvailablePlugin(controller.getPluginRegistry()))
            controller.registerPlugin(DemoDeviceInfoPlugin())
        }
        hybridController?.start()

        findViewById<Button>(R.id.btn_sent).setOnClickListener {
            hybridController?.sendEventMessageToJS("refreshToken")
        }
    }

    override fun onPageLoadProgress(progress: Int) {
    }

    override fun onTitleChanged(title: String?) {
       Log.e("MainActivity","webViewTitle:$title")
    }

    override fun onSelectFile(callback: ValueCallback<Array<Uri>>?) {
        callback?.onReceiveValue(null)
    }

    override fun onPageLoadError() {
        Log.e("magic", "onLoadError")
    }

    override fun onDocumentDownloaded(path: String?) {
    }

    override fun onBackPressed() {
        if (hybridController?.canGoBack() == true) {
            hybridController?.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        hybridController?.onResume()
    }

    override fun onPause() {
        hybridController?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        hybridController?.destroy()
        super.onDestroy()
    }
}
