package org.zoolytic.plugin

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import sun.swing.DefaultLookup
import javax.swing.JTree

class ZkTreeCellRenderer() : ColoredTreeCellRenderer() {
    val leafIcon = DefaultLookup.getIcon(this, ui, "Tree.leafIcon")
    val openIcon = DefaultLookup.getIcon(this, ui, "Tree.openIcon")
    val closedIcon = DefaultLookup.getIcon(this, ui, "Tree.closedIcon")

    override fun customizeCellRenderer(tree: JTree, node: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
        if (leaf) {
            icon = leafIcon
        } else if (expanded) {
            icon = openIcon
        } else {
            icon = closedIcon
        }
        if (node is ZkTreeNode) {
            if (node is ZkRootTreeNode) {
                append(node.getNodeData().text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(" (" + (if (node.isConnected()) "" else "dis") + "connected)", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
            } else {
                append(node.getNodeData().text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(node.getNodeData().getSizeString(), SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
            }
        } else {
            append(node.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }
}