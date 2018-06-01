package org.zoolytic.plugin

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.List

@State(name = "Zoolytic", storages = [Storage("zoolytic.xml")])
class ZooStateComponent : PersistentStateComponent<ZooStateComponent> {
    private val LOG = Logger.getInstance("ZooStateComponent")

    public var clusters: java.util.List<String> = java.util.ArrayList<String>() as List<String>

    override fun getState(): ZooStateComponent {
        LOG.info("Save state:" + clusters)
        return this
    }

    override fun loadState(state: ZooStateComponent) {
        LOG.info("Load state:" + state)
        XmlSerializerUtil.copyBean(state, this);
    }
}

