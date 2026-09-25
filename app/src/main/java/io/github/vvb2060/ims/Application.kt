package io.github.vvb2060.ims

import android.app.Application

class Application : Application() {
    override fun onTerminate() {
        super.onTerminate()
        LogcatRepository.stopAndClear()
    }
}
