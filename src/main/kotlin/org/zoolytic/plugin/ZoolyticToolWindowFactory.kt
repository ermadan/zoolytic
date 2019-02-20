package org.zoolytic.plugin

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.WatchedEvent
import org.apache.zookeeper.Watcher
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.*
import java.util.regex.Pattern
import javax.swing.*
import javax.swing.event.*
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
    private val tableModel by lazy {TableModel()}
    private val addButton by lazy {AddAction()}
    private val removeButton by lazy {RemoveAction()}
    private var actionToolBar: ActionToolbar? = null

    private val config: ZooStateComponent = ServiceManager.getService(ZooStateComponent::class.java)

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

        tree.cellRenderer = ZkTreeCellRenderer()
        tree.addTreeSelectionListener {
            val node = it?.path?.lastPathComponent  as DefaultMutableTreeNode
            if (node == null) {
                removeButton.templatePresentation.isEnabled = false
            } else {
                removeButton.templatePresentation.setEnabled(true)
                LOG.info("remove enabled:" + removeButton.templatePresentation.isEnabled)
                tableModel.updateDetails(node)
            }
        }

        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    val paths = tree.selectionPaths
                    if (paths.size == 0) {
                        return
                    }
                    val allConnected =  tree.selectionPaths.fold(true) {a, v ->
                        val path = v.lastPathComponent
                        a && (path is ZkTreeNode) && path.isConnected()}

                    val menu = object: JPopupMenu() {
                        fun add(name: String, task: () -> Unit) {
                            add(JMenuItem(object: AbstractAction(name) {
                                override fun actionPerformed(e: ActionEvent) {
                                    task()
                                }
                            }))
                        }
                    }
                    if (allConnected) {
                        menu.add("Collect size info") {
                            background("Collecting size information for Zookeeper tree") {
                                paths.forEach {
                                    (it.lastPathComponent as ZkTreeNode).collectSize()
                                    LOG.info("calc complete")
                                    treeModel.reload(it.lastPathComponent as TreeNode)
                                    LOG.info("model reloaded")
                                }
                            }
                        }
                        menu.add("Report size info") {
                            background("Collecting size information for Zookeeper tree") {
                                paths.forEach {
                                    val nodeData = (it.lastPathComponent as ZkTreeNode).getNodeData()
                                    val file = File.createTempFile("report", "txt")
//                                    val file = ScratchRootType.getInstance().createScratchFile(project, "report", Language.ANY, "")
                                    ZkUtils.count(nodeData.zk!!, nodeData.getFullPath(), PrintWriter (file))
                                    val vfile = VirtualFileManager.getInstance().getFileSystem("file").findFileByPath(file.absolutePath)
                                    ApplicationManager.getApplication().invokeLater({FileEditorManager.getInstance(project!!).openFile(vfile!!, false)})
                                    LOG.info("calc complete")
                                }
                            }
                        }
                    }

                    if (paths.size == 1) {
                        val node = paths.first().lastPathComponent
                        if (node is ZkRootTreeNode) {
                            val cluster = node.getNodeData().text
                            if (node.isConnected()) {
                                menu.add("Disconnect") {
                                    ZkUtils.disconnect(cluster)
                                    disconnected(cluster)
                                }
                            } else {
                                menu.add("Connect") {
                                    background("Connecting to cluster:" + cluster) {
                                        try {
                                            node.expand(true)
                                            treeModel.reload()
                                        } catch (e: IOException) {
                                            error("Cannot connect:" + e.message, e)
                                        }
                                    }
                                }
                            }
                        }
                        if (allConnected) {
                            if (paths.size == 1 && !(paths[0].lastPathComponent is ZkTreeNode)) {
                                menu.add("Add cluster", {addCluster()})
                            } else {
                                menu.add("Create node", {addNode(paths)})
                            }
                            menu.add("Select") {
                                val pattern = Messages.showInputDialog("Enter selection regexp", "Select nodes", Messages.getQuestionIcon())
                                if (pattern != null && pattern.length > 0) {
                                    val parent = paths.first().lastPathComponent as ZkTreeNode
                                    tree.selectionModel.selectionPaths = (parent.children().asSequence().filter { Pattern.matches(pattern, (it as ZkTreeNode).getNodeData().text) }.map{
                                        TreePath((tree.getModel() as DefaultTreeModel).getPathToRoot(it as TreeNode))
                                    }.toList()).toTypedArray()
                                    info(tree.selectionModel.selectionPaths.size.toString() + " nodes were selected.")
                                }
                            }
                        }
                    }
                    if (isRemoveEnabled()) {
                        menu.add("Delete node(s)", {remove()})
                    }
                    menu.show(tree, e.x, e.y)
                }
            }
        })
        tree.isRootVisible = false
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION)
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent?) {
                val treeNode = event!!.getPath().lastPathComponent as DefaultMutableTreeNode
                background("Expanding " + treeNode.userObject) {
                    treeNode.children().asSequence().forEach{(it as ZkTreeNode).expand()}
                }
            }

            override fun treeCollapsed(event: TreeExpansionEvent?) {
            }
        })
        tableModel.init()

        val details = JBTable(tableModel)
        details.getColumnModel().getColumn(0).setPreferredWidth(50)
        details.setFillsViewportHeight(false)
        details.setShowColumns(false)
        details.getDefaultEditor(String::class.java).addCellEditorListener(object: CellEditorListener {
            override fun editingStopped(e: ChangeEvent?) {
                val node = tree.selectionPath.lastPathComponent as ZkTreeNode
                try {
                    val value = (e?.source as DefaultCellEditor).cellEditorValue.toString()
                    node.getNodeData().data = value.toByteArray()
                    node.getNodeData().reset()
                    tableModel.updateDetails(node)
                } catch (e: Exception) {
                    error("Exception altering value of node " + node.getNodeData().getFullPath(), e)
                }
            }

            override fun editingCanceled(e: ChangeEvent?) {
            }
        })

        panel.topComponent = JBScrollPane(tree)
        panel.bottomComponent = JBScrollPane(details)
        panel.setResizeWeight(0.7)
        panel.dividerSize = 2
        toolPanel.add(panel, BorderLayout.CENTER)

        return toolPanel
    }

    private fun addNode(paths: Array<TreePath>) {
        val dialog = CreateNodeDialog()
        dialog.show()
        if (dialog.exitCode == Messages.OK) {
            val nodeName = dialog.inputString
            if (nodeName != null && nodeName.length > 0) {
                background("Creating Zookeeper node") {
                    try {
                        val parent = paths.first().lastPathComponent as ZkTreeNode
                        val path = parent.createChildNode(nodeName, CreateMode.fromFlag(dialog.getMode()!!))
                        info(path + " node was created with " + CreateMode.fromFlag(dialog.getMode()!!))
                        treeModel.reload(parent)
                    } catch (e: Exception) {
                        error(nodeName + " node was not created ", e)
                    }
                }
            }
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
                        paths.fold("You are about to delete following nodes ") {a, v -> a + "\n" + v.lastPathComponent.toString()},
                        "Zookeeper", Messages.getQuestionIcon())) {
            background("Deleting Zookeeper node") {
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
            }
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
                updateText(searchTextField.text!!)
            }
        })
        panel.add(searchTextField)

        return panel
    }

    private fun updateText(text: String) {
        tree.selectionModel.selectionPaths = if (text.length < 2) {
            arrayOf()
        } else {
            select(zRoot, ".*$text.*".toRegex())
                    .asSequence()
                    .map{ TreePath((tree.model as DefaultTreeModel).getPathToRoot(it as TreeNode))}
                    .toList()
                    .toTypedArray()
        }
    }

    fun select(node: DefaultMutableTreeNode, regex: Regex) : List<DefaultMutableTreeNode> {
        val selected = ArrayList<DefaultMutableTreeNode>()
        if (regex.matches(node.toString())) {
            selected.add(node)
        }
        node.children().iterator().forEach { selected.addAll(select(it as DefaultMutableTreeNode, regex)) }
        return selected
    }

    private fun addCluster() {
        val cluster = Messages.showInputDialog("Enter Zookeeper connection string (comma separated host:port list)",
                "Add Zookeeper cluster", Messages.getQuestionIcon(), null, object: InputValidator {
            //host:port,
            private val matcher = """([a-zA-Z0-9.-]+:[0-9]{1,5},)*([a-zA-Z0-9-]+\.)*([a-zA-Z0-9-])+:[0-9]{1,5}""".toRegex()
            override fun checkInput(inputString: String?) = inputString != null && matcher.matches(inputString)
            override fun canClose(inputString: String?) = checkInput(inputString)
        })
        if (cluster != null && cluster.isNotEmpty()) {
            background("Adding Zookeeper cluster $cluster") {
                try {
                    zRoot.add(getZkTree(cluster))
                    treeModel.reload(zRoot)
                    config.clusters.add(cluster)
                    LOG.info("Cluster " + cluster + " added to config, " + config.clusters)
                } catch (e: IOException) {
                    error("Cannot connect to $cluster", e)
                }
            }
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
            e.presentation.isEnabled = isRemoveEnabled()
        }
    }

    private fun isRemoveEnabled(): Boolean {
        if (tree.selectionPaths == null) {
            return false
        }
        return tree.selectionPaths.fold(true) {a, v ->
            val path = v.lastPathComponent
            a && (path is ZkTreeNode) && (path.isLeaf || path is ZkRootTreeNode)}
    }

    inner class RefreshAction : AnAction("Refresh","Refresh Zookeeper cluster node", REFRESH_ICON) {
        override fun actionPerformed(e: AnActionEvent?) {
            tree.selectionPaths.forEach {
                val node = it.lastPathComponent
                if (node is ZkTreeNode) {
                    background ("Refreshing zookeeper") {
                        LOG.info("Refreshing " + node.userObject)
                        node.refresh()
                        treeModel.reload(node)
                        if (tree.isExpanded(it)) {
                            node.children().asSequence().forEach {(it as ZkTreeNode).expand()}
                        }
                    }
                }
            }
        }

        override fun update (e: AnActionEvent) {
            e.presentation.isEnabled = tree.selectionPaths != null && tree.selectionPaths.isNotEmpty()
        }
    }

    private fun error(message: String, e: Exception) {
        LOG.error(message, e)
        ApplicationManager.getApplication().invokeLater{
            Messages.showErrorDialog(message + e.toString(), "Zookeeper")
        }
    }

    private fun info(message: String) {
        LOG.info(message)
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(message, "Zookeeper")
        }
    }

    fun background(title: String, task: () -> Unit) {
        Notifications.Bus.notify(Notification("ApplicationName", "MethodName", title, NotificationType.INFORMATION))
        ApplicationManager.getApplication().invokeLater {
            ProgressManager.getInstance().run(object: Task.Backgroundable(project, title, false) {
                override fun run(indicator: ProgressIndicator) {
                    task()
                    LOG.info("background task complete:${title}")
                }
            })
        }
    }

    fun disconnected(address: String) {
        LOG.info("disconnected")
        zRoot.children().asSequence()
                .filter{(it as ZkRootTreeNode).getNodeData().text.equals(address)}
                .forEach {
                    val root = it as ZkRootTreeNode
                    root.removeAllChildren()
                    root.getNodeData().expanded = false
                    treeModel.reload()
                }
    }

    private fun getZkTree(address: String): MutableTreeNode {
        LOG.info("watcher added.")
        return ZkRootTreeNode(address, ZWatcher(this, address))
    }
}

class ZWatcher(val factory: ZoolyticToolWindowFactory, val address: String): Watcher {
    override fun process(event: WatchedEvent?) {
        if (event?.state == Watcher.Event.KeeperState.Disconnected) {
            factory.disconnected(address)
        }
    }
}


