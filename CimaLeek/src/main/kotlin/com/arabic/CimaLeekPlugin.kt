package com.arabic

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CimaLeekPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CimaLeek())
    }
}
