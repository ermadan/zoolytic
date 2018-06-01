package org.zoolytic.plugin

import com.intellij.openapi.diagnostic.Logger
import org.apache.zookeeper.WatchedEvent
import org.apache.zookeeper.Watcher
import org.apache.zookeeper.ZooKeeper
import java.io.IOException
import java.util.HashMap
import java.util.concurrent.CountDownLatch

object ZkUtils {

    private val LOG = Logger.getInstance("zoolytic")
    private val zookeepers = HashMap<String, ZooKeeper>()

    @Throws(IOException::class, InterruptedException::class)
    fun getZk(source: String): ZooKeeper {
        val latch = CountDownLatch(1)
        var zk: ZooKeeper? = zookeepers[source]
        LOG.info("Found zk:" + zk)
        if (zk == null) {
            LOG.info("Establishing connection....")
            zk = ZooKeeper(source, 1000) { event: WatchedEvent ->
                if (event.state == Watcher.Event.KeeperState.SyncConnected) {
                    latch.countDown()
                }
            }
            latch.await()
            zookeepers[source] = zk
            LOG.info("Connection established")
        }
        return zk
    }
}
