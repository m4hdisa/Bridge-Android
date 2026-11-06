package io.github.m4hdisa.bridge

import android.app.Application
import android.os.Build
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val working = File(filesDir, "working").apply { mkdirs() }
        Libbox.setup(
            SetupOptions().also {
                it.basePath = filesDir.path
                it.workingPath = working.path
                it.tempPath = cacheDir.path
                it.fixAndroidStack = needsStackFix()
                it.logMaxLines = 3000
                it.debug = BuildConfig.DEBUG
                it.crashReportSource = "Application"
                it.appVersion = BuildConfig.VERSION_CODE.toString()
                it.appMarketingVersion = BuildConfig.VERSION_NAME
            },
        )
    }

    companion object {
        private var model: AppModel? = null

        /** The screens' state, kept for as long as the app runs, so turning the phone or changing the language keeps it. */
        fun model(context: android.content.Context): AppModel = model ?: AppModel(context).also { model = it }
    }

    /** The same Android versions sing-box's own client applies this Go workaround on (golang/go#68760). */
    private fun needsStackFix(): Boolean = BuildConfig.DEBUG ||
        Build.VERSION.SDK_INT == Build.VERSION_CODES.N ||
        Build.VERSION.SDK_INT == Build.VERSION_CODES.N_MR1 ||
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
}
