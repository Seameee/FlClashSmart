package com.flsmart.clash

import android.app.Application
import android.content.Context
import com.flsmart.common.GlobalState

class FlClashApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        GlobalState.init(this)
    }
}
