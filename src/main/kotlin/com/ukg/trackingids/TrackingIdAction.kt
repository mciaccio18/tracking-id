package com.ukg.trackingids

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ui.Messages.InputDialog
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.xml.XmlTag
import java.util.UUID

class TrackingIdAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val psiFile = event.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE) ?: return
        if (!psiFile.name.endsWith(".js") && !psiFile.name.endsWith(".tsx")){
            println("Not a js or tsx file")
            return
        }
        val xmlTagsToCheck = setOf("button", "Button" , "InlineButton", "InlineButton2", "IconButton")
        var withinLoop = false
        var outOfLoopIndicator: PsiElement? = null
        val visitor = object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is JSCallExpression && (element.methodExpression?.text?.contains("map") == true || element.methodExpression?.text?.contains("for") == true || element.methodExpression?.text?.contains("forEach") == true || element.methodExpression?.text?.contains("reduce") == true)) {
                    withinLoop = true
                    outOfLoopIndicator = outOfLoopIndicator ?: findOutOfLoopIndicator(element)
                }else if (element == outOfLoopIndicator) {
                    withinLoop = false
                    outOfLoopIndicator = null
                }
                if (element is XmlTag && element.name in xmlTagsToCheck) {
                    val isNative = element.name == "button"
                    val attributeToCheck = if (isNative) "data-tracking-id" else "trackingId"
                    val trackingIdAttrs = element.getAttribute(attributeToCheck)
                    if (trackingIdAttrs == null) {
                        val trackingId = UUID.randomUUID().toString()
                        if (withinLoop) {
                            val uniqueVariableName = promptForUniqueVariableName(project, element) ?: return
                            WriteCommandAction.runWriteCommandAction(project) {
                                element.setAttribute(attributeToCheck, "{`$trackingId-\${$uniqueVariableName}`}")
                            }
                        } else {
                            WriteCommandAction.runWriteCommandAction(project) {
                                element.setAttribute(attributeToCheck, trackingId)
                            }
                        }
                    }
                }
                element.acceptChildren(this)
            }
        }
        psiFile.accept(visitor)
    }

    private fun findOutOfLoopIndicator(element: JSCallExpression): PsiElement? {
        element.nextSibling?.let { return it }
        var parent = element.parent
        while (parent.nextSibling == null && parent.parent != null) {
            parent = parent.parent
        }
        return parent.nextSibling
    }

    private fun promptForUniqueVariableName(project: Project, xmlTag: XmlTag): String? {
        val lineNumber = getLineNumber(xmlTag, project) ?: return null
        val message = "Enter a property that is unique to each ${xmlTag.name} in the loop on line ${lineNumber}."
        val title = "Unique Variable Name"
        val options = arrayOf("OK", "Navigate to Line")
        val icon = Messages.getQuestionIcon()
        val comment = "Caution: You will need to use a unique property for the tracking attribute that will be the same each time the page is rendered."
        val validator = object : InputValidator {
            override fun checkInput(inputString: String?): Boolean = !inputString.isNullOrBlank() && inputString != "index"
            override fun canClose(inputString: String?): Boolean = !inputString.isNullOrBlank()
        }
        val inputDialog = InputDialog(project, message, title, icon, "", validator, options, 0, comment)
        inputDialog.show()
        val result = inputDialog.exitCode
        when (result) {
            -1 -> return null
            0 -> return inputDialog.inputString
            1 -> { navigateToLine(xmlTag, project) }
        }
        return Messages.showInputDialog(project, message, title, icon, "", validator, null, comment)
    }

    private fun navigateToLine(element: PsiElement, project: Project) {
        val psiFile: PsiFile = element.containingFile ?: return
        val documentManager = PsiDocumentManager.getInstance(project)
        val document = documentManager.getDocument(psiFile) ?: return
        val lineNumber = document.getLineNumber(element.textRange.startOffset)
        val virtualFile = psiFile.virtualFile
        val openFileDescriptor = OpenFileDescriptor(project, virtualFile, lineNumber, 0)
        openFileDescriptor.navigate(true)
    }

    private fun getLineNumber(element: PsiElement, project: Project): Int? {
        val psiFile: PsiFile = element.containingFile ?: return null
        val documentManager = PsiDocumentManager.getInstance(project)
        val document: Document? = documentManager.getDocument(psiFile)
        return document?.getLineNumber(element.textRange.startOffset)?.plus(1)
    }
}