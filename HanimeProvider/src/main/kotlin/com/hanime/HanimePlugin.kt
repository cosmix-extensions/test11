package com.hanime

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HanimePlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(HanimeProvider())
    }
}
