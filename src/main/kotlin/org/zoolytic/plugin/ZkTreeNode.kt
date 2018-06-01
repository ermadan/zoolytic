package org.zoolytic.plugin

import com.intellij.openapi.diagnostic.Logger
import org.apache.zookeeper.AsyncCallback
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.ZooKeeper
import org.apache.zookeeper.data.Stat
import javax.swing.tree.DefaultMutableTreeNode


open class ZkTreeNode(userObject: Any?) : DefaultMutableTreeNode(userObject) {
    private val LOG = Logger.getInstance("zoolytic")
    class NodeData(val text : String,
                   val path: String,
                   val zk : ZooKeeper,
                   var expanded : Boolean = false
                   ) {
        private val LOG = Logger.getInstance("zoolytic")
        private var _data: ByteArray? = null
        val data: ByteArray?
            get() {
                if (_data == null) {
                    if (path != "") {
                        _data = zk.getData(getFullPath(), false, stat)
                    }
                    LOG.info("getting data:" + path + ":" + stat)
                }
                return _data?: null
            }
        var stat: Stat? = null
        var size: Int = 0
        override fun toString(): String {
            if (size == 0) {
                return text
            }
            return text + " (" + if (size > 1000) {if (size > 1000000) {(size/1000_000 as Int).toString() + "M"} else {(size/1000 as Int).toString() + "K"}} else {size} + ")"
        }
        fun getFullPath(): String = if (path == "") {"/"} else if (path == "/") { "/" + text } else {path + "/" + text}
    }

    fun getNodeData() : NodeData {
        return userObject as NodeData
    }

    fun collectSize(): Int {
        LOG.info("calculating size for " + getNodeData().getFullPath())
        expand(true)
        val childs =  children().asSequence().fold(0, {a, v-> a + (v as ZkTreeNode).collectSize()})
        val payload = getNodeData().zk.getData(getNodeData().getFullPath(), false, null)
        getNodeData().size = (payload?.size ?: 0) + childs
        if (payload == null) {
            LOG.info("payload null" + getNodeData().getFullPath())
        }
        LOG.info("calculated for " + getNodeData().getFullPath() + ":" + getNodeData().size + "/" + childs)

        return getNodeData().size
    }

    fun createChildNode(name: String) : String? {
        val path = getNodeData().zk.create(getNodeData().getFullPath() + if (getNodeData().getFullPath() == "/") {""} else {"/"} + name, ByteArray(0), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
        addChild(name)
        return path
    }

    fun deleteNode(child: ZkTreeNode) {
        getNodeData().zk.delete(child.getNodeData().getFullPath(), -1)
        remove(child)
    }

    fun expand(sync : Boolean = false) {
        val data = userObject as NodeData
        if (!data.expanded) {
            LOG.info("expanding:" + data.getFullPath())
            if (sync) {
                addChildren(data.zk.getChildren(data.getFullPath(), false))
            } else {
                data.zk.getChildren(data.getFullPath(), false, object : AsyncCallback.ChildrenCallback {
                    override fun processResult(rc: Int, path: String?, ctx: Any?, children: MutableList<String>?) {
                        addChildren(children)
                    }
                }, path)
            }
            data.expanded = true
        }
    }

    fun addChildren(children: MutableList<String>?): Unit {
        if (children != null) {
            children.sorted().forEach {
                addChild(it)
            }
        }
    }

    private fun addChild(name: String) {
        val node = NodeData(name, getNodeData().getFullPath(), getNodeData().zk)
        LOG.info("child added: " + node.getFullPath())
        add(ZkTreeNode(node))
    }
}

class ZkRootTreeNode(cluster: String) : ZkTreeNode(NodeData(cluster, "", ZkUtils.getZk(cluster),true)) {
    init {
        val zk = ZkUtils.getZk(cluster)
        zk.getChildren("/", false).sorted().forEach{
            val node = NodeData(it, "/", zk)
            add(ZkTreeNode(node))
        }
    }
}