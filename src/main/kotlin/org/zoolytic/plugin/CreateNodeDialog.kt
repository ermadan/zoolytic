package org.zoolytic.plugin

import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.ButtonGroup
import javax.swing.JPanel
import javax.swing.JRadioButton

class CreateNodeDialog() : Messages.InputDialog(
        "Enter name for new node",
        "New node",
        Messages.getQuestionIcon(),
        null,
        object: InputValidator {
            override fun checkInput(inputString: String?) = true
            override fun canClose(inputString: String?) = inputString != null && inputString.length > 0
        }) {

    private var radios: Array<JRadioButton>? = null

    override fun createMessagePanel(): JPanel {
        val messagePanel = JPanel(BorderLayout())
        if (myMessage != null) {
            val textComponent = createTextComponent()
            messagePanel.add(textComponent, BorderLayout.NORTH)
        }

        myField = createTextFieldComponent()
        messagePanel.add(createScrollableTextComponent(), BorderLayout.CENTER)

        val radioPane = JPanel(GridLayout(0, 2))
        radios = arrayOf(javax.swing.JRadioButton("Persistent"), JRadioButton("Ephimeral"), JRadioButton("Persistent Sequential"), JRadioButton("Ephimeral Sequential"))
        radios!![0].isSelected = true
        val group = ButtonGroup()
        radios!!.forEach {
            group.add(it);
            radioPane.add(it)
        }
        messagePanel.add(radioPane, BorderLayout.SOUTH)

        return messagePanel
    }

    fun getMode(): Int? {
        return (0..radios!!.size).asSequence().find{radios!![it].isSelected}
    }
}

