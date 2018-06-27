package org.zoolytic.plugin

import org.apache.zookeeper.WatchedEvent
import org.apache.zookeeper.Watcher
import org.apache.zookeeper.ZooKeeper
import org.apache.zookeeper.data.Stat
import java.io.IOException
import java.util.HashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val zookeepers = HashMap<String, ZooKeeper>()

@Throws(IOException::class, InterruptedException::class)
fun getZk(source: String): ZooKeeper {
    val latch = CountDownLatch(1)
    var zk: ZooKeeper? = zookeepers[source]
    if (zk != null && zk.state != ZooKeeper.States.CONNECTED) {
        zk = null
    }
    if (zk == null) {
        println(1)
        zk = ZooKeeper(source, 1000) { event: WatchedEvent ->
            if (event.state == Watcher.Event.KeeperState.SyncConnected) {
                println(2)
                latch.countDown()
            }
        }
        println(3)
        if (latch.await(10, TimeUnit.SECONDS)) {
            println(4)
            zookeepers[source] = zk
        } else {
            println(5)
            throw IOException("Couldnt establish connection: " + source)
        }
    }
    return zk
}

fun main(argv: Array<String>) {
    var d: String? = null
    val f1 = CompletableFuture<Pair<String, Int>>()
    Thread({println("f1 compl0"); Thread.sleep(1); println("f1 compl");f1.complete(Pair(d!!,1));println("f1 compl2");}).start()
    println("f1 get");
    f1.get()
    println("f1 get2");
//    val f = CompletableFuture<Pair<ByteArray?, Stat?>>()
//    getZk("scoreu01.uk.db.com:2181")
//            .getData("/brokers", false,
//            {rc: Int, path: String?, ctx: Any?, data: ByteArray?, stat: Stat?->
//                println("getData:callback:" + Thread.currentThread())
//                f.complete(Pair(data, stat))
//                println("getData:callback2")
//            }, null)
//    println("getData2" + Thread.currentThread())
//    f.join()
//    val result = f.get()
//    println("getData3")
}
