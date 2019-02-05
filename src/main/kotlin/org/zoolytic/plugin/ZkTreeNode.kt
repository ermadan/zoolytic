package org.zoolytic.plugin

import com.intellij.openapi.diagnostic.Logger
import org.apache.zookeeper.*
import org.apache.zookeeper.data.Stat
import java.util.concurrent.CompletableFuture
import javax.swing.tree.DefaultMutableTreeNode


open class ZkTreeNode(userObject: Any?) : DefaultMutableTreeNode(userObject) {
    private val LOG = Logger.getInstance(this.javaClass)
    class NodeData(val text : String,
                   val path: String,
                   var zk : ZooKeeper?,
                   var expanded : Boolean = false
                   ) {
        private val LOG = Logger.getInstance("zoolytic")
        private var _data: ByteArray? = null
        var data: ByteArray?
            get() {
                LOG.info("getData")
                if (_data == null) {
                    if (path != "") {
                        val f = CompletableFuture<Pair<ByteArray?, Stat?>>()
                        zk?.getData(getFullPath(), false,
                            {rc: Int, path: String?, ctx: Any?, data: ByteArray?, stat: Stat?->
                                f.complete(Pair(data, stat))
                        }, null)
                        val result = f.get()
                        _data = result.first
                        stat = result.second
                    }
                }
                return _data?: null
            }
            set(value) {
                zk?.setData(getFullPath(), value, -1)
                _data = value
            }
        var stat: Stat? = null
        var size: Int = 0
        override fun toString() = text
        fun getSizeString() =  if (size == 0) "" else ZkUtils.format(size)
        fun getFullPath(): String = if (path == "") "/" else if (path == "/") { "/" + text } else {path + "/" + text}
        fun reset() { _data = null }
    }

    fun isConnected() =  getNodeData().zk?.state == ZooKeeper.States.CONNECTED

    fun getNodeData() = userObject as NodeData

    fun collectSize(): Int {
        LOG.info("calculating size for " + getNodeData().getFullPath())
        expand(true)
        val childs =  children().asSequence().fold(0, {a, v-> a + (v as ZkTreeNode).collectSize()})
        val payload = getNodeData().zk?.getData(getNodeData().getFullPath(), false, null)
        getNodeData().size = (payload?.size ?: 0) + childs
        if (payload == null) {
            LOG.info("payload null" + getNodeData().getFullPath())
        }
        LOG.info("calculated for " + getNodeData().getFullPath() + ":" + getNodeData().size + "/" + childs)

        return getNodeData().size
    }

    fun createChildNode(name: String, mode: CreateMode = CreateMode.PERSISTENT) : String? {
        val path = getNodeData().zk?.create(
                getNodeData().getFullPath() + if (getNodeData().getFullPath() == "/") {""} else {"/"} + name, ByteArray(0),
                ZooDefs.Ids.OPEN_ACL_UNSAFE, mode)
        addChild(name)
        return path
    }

    fun deleteNode(child: ZkTreeNode) {
        LOG.info("deleteNode")
        getNodeData().zk?.delete(child.getNodeData().getFullPath(), -1)
        remove(child)
    }

    fun refresh() {
        LOG.info("refresh")
        removeAllChildren()
        getNodeData().expanded = false
        expand(true)
    }

    fun addChildren(children: MutableList<String>?) {
        LOG.info("addChildren")
        if (children != null) {
            children.sorted().forEach {
                addChild(it)
            }
        }
    }

    private fun addChild(name: String) {
        LOG.info("addChild")
        val node = NodeData(name, getNodeData().getFullPath(), getNodeData().zk)
        LOG.info("child added: " + node.getFullPath())
        add(ZkTreeNode(node))
    }

    open fun expand(sync : Boolean = false) {
        val data = userObject as NodeData
        LOG.info("expanding:" + data.getFullPath() + " expanded:" + data.expanded)
        if (!data.expanded) {
            if (sync) {
                addChildren(data.zk?.getChildren(data.getFullPath(), false))
            } else {
                data.zk?.getChildren(data.getFullPath(), false, object : AsyncCallback.ChildrenCallback {
                    override fun processResult(rc: Int, path: String?, ctx: Any?, children: MutableList<String>?) {
                        addChildren(children)
                    }
                }, path)
            }
            data.expanded = true
        }
    }
}

class ZkRootTreeNode(cluster: String, val watcher: Watcher) : ZkTreeNode(NodeData(cluster, "", null,false)) {
    override fun expand(sync : Boolean) {
        getNodeData().zk = ZkUtils.getZk(getNodeData().text)
        super.expand(sync)
        getNodeData().zk?.register(watcher)
    }
}