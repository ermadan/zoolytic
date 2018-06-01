package org.zoolytic.plugin

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.IOException
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.*
import java.util.regex.Pattern
import javax.swing.*
import javax.swing.event.*
import javax.swing.table.DefaultTableModel
import javax.swing.tree.*


class ZoolyticToolWindowFactory : ToolWindowFactory {
    private val LOG = Logger.getInstance("zoolytic")
    private val ADD_ICON by lazy {IconLoader.getIcon("/general/add.png")}
    private val REMOVE_ICON by lazy {IconLoader.getIcon("/general/remove.png")}
    private val REFRESH_ICON by lazy {IconLoader.getIcon("/actions/refresh.png")}
    private var toolWindow: ToolWindow? = null
    private var project: Project? = null
    private val zRoot by lazy {DefaultMutableTreeNode("Zookeeper")}
    private val treeModel by lazy {DefaultTreeModel(zRoot)}
    private val tree by lazy {Tree(treeModel)}
    private val tableModel by lazy {DefaultTableModel()}
    private val addButton by lazy {AddAction()}
    private val removeButton by lazy {RemoveAction()}
    private var actionToolBar: ActionToolbar? = null

    val config: ZooStateComponent = ServiceManager.getService(ZooStateComponent::class.java)

    companion object Formatter {
        private val formatter = NumberFormat.getInstance(Locale.US) as DecimalFormat;

        init {
            val symbols = formatter.getDecimalFormatSymbols();
            symbols.setGroupingSeparator(' ');
            formatter.setDecimalFormatSymbols(symbols);
        }

        fun format(int: Int) : String {
            return formatter.format(int)
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        this.project = project
        this.toolWindow = toolWindow
        val contentFactory = ContentFactory.SERVICE.getInstance()
        val content = contentFactory!!.createContent(createToolWindowPanel(), "", false)
        toolWindow.contentManager!!.addContent(content)
    }

    private fun createToolWindowPanel(): JComponent {
        val toolPanel = JPanel(BorderLayout())
        toolPanel.add(getToolbar(), BorderLayout.PAGE_START)

        val panel = JSplitPane(JSplitPane.VERTICAL_SPLIT)

        config.clusters.forEach{zRoot.add(getZkTree(it))}
        tree.expandPath(TreePath(zRoot))

        tree.cellRenderer = DefaultTreeCellRenderer()
        tree.addTreeSelectionListener(object : TreeSelectionListener {
            override fun valueChanged(e: TreeSelectionEvent?) {
                val node = e?.path?.lastPathComponent  as DefaultMutableTreeNode
                if (node == null) {
                    removeButton.templatePresentation.isEnabled = false
                } else {
                    removeButton.templatePresentation.setEnabled(true)
                    LOG.info("remove enabled:" + removeButton.templatePresentation.isEnabled)
                    if (node is ZkTreeNode) {
                        val data = node.userObject as ZkTreeNode.NodeData
                        tableModel.setValueAt(data.getFullPath(), 0, 1)
                        if (data.stat == null) {
                            tableModel.setValueAt("", 1, 1)
                        } else {
                            tableModel.setValueAt(data.stat.toString(), 1, 1)
                        }
                        if (data.data == null) {
                            tableModel.setValueAt("", 2, 1)
                            tableModel.setValueAt("", 3, 1)
                            tableModel.setValueAt("", 4, 1)
                        } else {
                            tableModel.setValueAt(String(data.data!!), 2, 1)
                            tableModel.setValueAt(format(data.data?.size ?: 0), 3, 1)
                            tableModel.setValueAt(data.stat?.toString(), 4, 1)
                        }
                    }
                }
            }
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    val paths = tree.selectionPaths
                    if (paths.size == 0) {
                        return
                    }
                    val menu = JPopupMenu()
                    menu.add(JMenuItem(object: AbstractAction("Collect size info") {
                        override fun actionPerformed(e: ActionEvent) {
                            background("Collecting size information for Zookeeper tree", {
                                paths.forEach { (it.lastPathComponent as ZkTreeNode).collectSize() }
                            })
                        }
                    }))
                    if (paths.size == 1) {
                        menu.add(JMenuItem(object : AbstractAction("Create node") {
                            override fun actionPerformed(e: ActionEvent) {
                                add()
                            }
                        }))
                        menu.add(JMenuItem(object : AbstractAction("Select") {
                            override fun actionPerformed(e: ActionEvent) {
                                val pattern = Messages.showInputDialog("Enter selection regexp", "Select nodes", Messages.getQuestionIcon())
                                if (pattern != null && pattern.length > 0) {
                                    val parent = paths.first().lastPathComponent as ZkTreeNode
                                    tree.selectionModel.selectionPaths = (parent.children().asSequence().filter{ Pattern.matches(pattern,(it as ZkTreeNode).getNodeData().text)}.map({
                                        TreePath((tree.getModel() as DefaultTreeModel).getPathToRoot(it as TreeNode))
                                    }).toList() as List<TreePath>).toTypedArray()
                                    info(tree.selectionModel.selectionPaths.size.toString() + " nodes were selected.")
                                }
                            }
                        }))
                    }
                    if (removeEnabled()) {
                        menu.add(JMenuItem(object : AbstractAction("Delete node(s)") {
                            override fun actionPerformed(e: ActionEvent) {
                                remove()
                            }
                        }))
                    }
                    menu.show(tree, e.x, e.y)
                }
            }
        })
        tree.isRootVisible = false
//        tree.collapsePath(TreePath(zRoot))
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent?) {
                val treeNode = event!!.getPath().lastPathComponent as DefaultMutableTreeNode
                background("Expanding " + treeNode.userObject, {
                    treeNode.children().asSequence().forEach{(it as ZkTreeNode).expand()}
                })
            }

            override fun treeCollapsed(event: TreeExpansionEvent?) {
            }
        })
        tableModel.addColumn("Property")
        tableModel.addColumn("value")
        tableModel.addRow(arrayOf("Path", ""))
        tableModel.addRow(arrayOf("Stat", ""))
        tableModel.addRow(arrayOf("Data", ""))
        tableModel.addRow(arrayOf("Size", ""))
        tableModel.addRow(arrayOf("Stat", ""))
        val details = JBTable(tableModel)
        details.getColumnModel().getColumn(0).setPreferredWidth(50)
        details.setFillsViewportHeight(false)
        details.setShowColumns(false)

        panel.topComponent = JBScrollPane(tree)
        panel.bottomComponent = JBScrollPane(details)
        panel.setResizeWeight(0.7)
        panel.dividerSize = 2
        toolPanel.add(panel, BorderLayout.CENTER)

        return toolPanel
    }

    private fun add() {
        val paths = tree.selectionPaths
        if (paths.size == 1 && !(paths[0].lastPathComponent is ZkTreeNode)) {
            addCluster()
        } else {
            addNode(paths)
        }
    }

    private fun addNode(paths: Array<TreePath>) {
        val nodeName = Messages.showInputDialog("Enter name for new node", "New node", Messages.getQuestionIcon())
        if (nodeName != null && nodeName.length > 0) {
            background("Creating Zookeeper node", {
                try {
                    val parent = paths.first().lastPathComponent as ZkTreeNode
                    val path = parent.createChildNode(nodeName)
                    info(path + " node was created")
                    treeModel.reload(parent)
                } catch (e: Exception) {
                    error(nodeName + " node was not created ", e)
                }
            })
        }
    }

    private fun remove() {
        val paths = tree.selectionPaths
        if (paths.size == 1 && paths[0].lastPathComponent is ZkRootTreeNode) {
            removeCluster()
        } else {
            removeNode()
        }
    }

    private fun removeNode() {
        val paths = tree.selectionPaths
        if (Messages.OK == Messages.showOkCancelDialog(
                        paths.fold("You are about to delete following nodes ",
                                { a, v -> a + "\n" + v.lastPathComponent.toString() }),
                        "Zookeeper", Messages.getQuestionIcon())) {
            background("Deleting Zookeeper node", {
                var deleted: List<String>? = null
                try {
                    deleted = paths.filter { !(it.lastPathComponent is ZkRootTreeNode) }.map {
                        val child = it.lastPathComponent as ZkTreeNode
                        val parent = child.parent as ZkTreeNode
                        parent.deleteNode(child)
                        treeModel.reload(parent)
                        child.getNodeData().getFullPath()
                    }
                } catch (e: Exception) {
                    error("Deletion failed", e)
                }
                if (deleted != null) {
                    info(deleted.joinToString("\n") + "\nwas deleted")
                }
            })
        }
    }

    private fun removeCluster() {
        val clusterNode = tree.selectionPaths[0].lastPathComponent as ZkRootTreeNode
        if (Messages.OK == Messages.showOkCancelDialog(
                        "You are about to delete Zookeeper cluster " + clusterNode.getNodeData().text,
                        "Zookeeper", Messages.getQuestionIcon())) {
            zRoot.remove(clusterNode)
            treeModel.reload(zRoot)
            config.clusters.remove(clusterNode.getNodeData().text)
        }
    }

    fun getZkTree(address: String): MutableTreeNode {
        return ZkRootTreeNode(address)
    }

    fun updateTree(text: String) {
//        val packagesList = CabalInterface(project!!).getPackagesList()
//        val installedPackagesList = CabalInterface(project!!).getInstalledPackagesList()
//        treeModel!!.setRoot(getZkTree(packagesList, installedPackagesList, text))
    }

    private fun getToolbar(): JComponent {
        val panel = JPanel()

        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)


        val group = DefaultActionGroup()
        val refreshButton = RefreshAction()
        group.add(refreshButton)
        group.add(addButton)
        group.add(removeButton)
        removeButton.templatePresentation.isEnabled = false

        actionToolBar = ActionManager.getInstance().createActionToolbar("CabalTool", group, true)

        panel.add(actionToolBar!!.component!!)


        val searchTextField = SearchTextField()
        searchTextField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent?) {
                updateTree(searchTextField.text!!)
            }

        })


        panel.add(searchTextField)
        return panel
    }

    private fun addCluster() {
        val cluster = Messages.showInputDialog("Enter address for one o more nodes for Zookeeper cluster", "Add Zookeeper cluster", Messages.getQuestionIcon())
        if (cluster != null && cluster.length > 0) {
            background("Adding Zookeeper cluster " + cluster, {
                try {
                    ZkUtils.getZk(cluster)
                    zRoot.add(ZkRootTreeNode(cluster))
                    treeModel.reload(zRoot)
                    config.clusters.add(cluster)
                    LOG.info("Cluster " + cluster + " added to config, " + config.clusters)
                } catch (e: IOException) {
                    error("Cannot connect to " + cluster, e)
                }
            })
        }
    }

    inner class AddAction : AnAction("Add", "Add Zookeeper cluster node", ADD_ICON) {
        override fun actionPerformed(e: AnActionEvent?) {
            addCluster()
        }
    }

    inner class RemoveAction : AnAction("Remove","Remove Zookeeper cluster node", REMOVE_ICON), AnAction.TransparentUpdate {
        override fun actionPerformed(e: AnActionEvent?) {
            removeCluster()
        }

        override fun update (e: AnActionEvent) {
            e.getPresentation().setEnabled(removeEnabled());
        }
    }

    private fun removeEnabled(): Boolean {
        val path = tree.selectionPaths?.get(0)?.lastPathComponent
        return (path is ZkTreeNode) && (path?.isLeaf || path is ZkRootTreeNode)
    }

    inner class RefreshAction : AnAction("Refresh","Refresh Zookeeper cluster node", REFRESH_ICON) {
        override fun actionPerformed(e: AnActionEvent?) {
        }
    }

    private fun error(message: String, e: Exception) {
        LOG.error(message, e)
        ApplicationManager.getApplication().invokeLater({
            Messages.showErrorDialog(message + e.toString(), "Zookeeper")
        })
    }

    private fun info(message: String) {
        LOG.info(message)
        ApplicationManager.getApplication().invokeLater({
            Messages.showInfoMessage(message, "Zookeeper")
        })
    }

    fun background(title: String, task: () -> Unit) {
        ProgressManager.getInstance().run(object: Task.Backgroundable(project,title, false) {
            override fun run(indicator: ProgressIndicator) {
                task()
            }
        })
    }
}