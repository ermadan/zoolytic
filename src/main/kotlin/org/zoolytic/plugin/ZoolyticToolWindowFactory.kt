package org.zoolytic.plugin

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ZoolyticToolWindowFactory : ToolWindowFactory {

    private val config: ZooStateComponent = ServiceManager.getService(ZooStateComponent::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.SERVICE.getInstance()
        val content = contentFactory!!.createContent(MainWindow(config, project), "", false)
        toolWindow.contentManager!!.addContent(content)
    }
}



