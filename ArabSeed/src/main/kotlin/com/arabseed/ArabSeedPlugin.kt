package com.arabseed

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArabSeedPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ArabSeed())
    }
}