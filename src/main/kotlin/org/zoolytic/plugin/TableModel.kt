package org.zoolytic.plugin

import com.intellij.openapi.diagnostic.Logger
import java.util.*
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode

class TableModel : DefaultTableModel() {
    private val LOG = Logger.getInstance(this.javaClass)

    fun init() {
        addColumn("Property")
        addColumn("value")
        arrayOf("Path", "Data", "Size", "Version", "Mod Date", "Stat").forEach { addRow(arrayOf(it, "")) }
    }

    override fun isCellEditable(row: Int, column: Int): Boolean {
        if (row == 1 && column == 1) {
            return true
        }
        return false
    }

    fun updateDetails(node: DefaultMutableTreeNode) {
        if (node is ZkTreeNode) {
            val nodeData = node.getNodeData()
            with(nodeData) {
                setValueAt(getFullPath(), 0, 1)
                LOG.info("reading data")
                setValueAt(if (data == null) {""} else {String(data!!)}, 1, 1)
                LOG.info("reading data2")
                if (stat == null) {
                    (2..5).forEach { setValueAt("", it, 1) }
                } else {
                    setValueAt(stat?.dataLength, 2, 1)
                    setValueAt(stat?.version, 3, 1)
                    setValueAt(Date(stat!!.mtime), 4, 1)
                    setValueAt(stat.toString(), 5, 1)
                }
                LOG.info("reading data10")

            }
        }
    }
}