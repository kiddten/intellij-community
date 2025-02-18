// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal

import com.intellij.find.FindModel
import com.intellij.find.SearchReplaceComponent
import com.intellij.find.SearchSession
import com.intellij.find.editorHeaderActions.NextOccurrenceAction
import com.intellij.find.editorHeaderActions.PrevOccurrenceAction
import com.intellij.find.editorHeaderActions.ToggleMatchCase
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.terminal.JBTerminalWidget
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import com.jediterm.terminal.SubstringFinder
import com.jediterm.terminal.ui.JediTermWidget
import java.awt.event.ItemListener
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JComponent
import javax.swing.event.DocumentListener

internal class TerminalSearchSession(private val terminalWidget: JBTerminalWidget) : SearchSession, DataProvider {
  private val searchComponent: SearchReplaceComponent = createSearchComponent()
  private val findModel: FindModel = createFindModel()
  private var hasMatches: Boolean = false
  private val terminalSearchComponent: MySearchComponent = MySearchComponent()

  private val searchComponentWrapper: BorderLayoutPanel

  init {
    searchComponentWrapper = object : BorderLayoutPanel() {
      override fun requestFocus() {
        IdeFocusManager.getInstance(terminalWidget.project).requestFocus(searchComponent.searchTextComponent, false)
      }
    }
    searchComponentWrapper.addToCenter(searchComponent)
    searchComponentWrapper.border = JBUI.Borders.customLine(JBUI.CurrentTheme.Editor.BORDER_COLOR, 0, 1, 0, 1)
  }

  fun getTerminalSearchComponent(): JediTermWidget.SearchComponent = terminalSearchComponent

  private fun createFindModel(): FindModel {
    return FindModel().also {
      var prevIsCaseSensitive = it.isCaseSensitive
      it.addObserver {
        if (prevIsCaseSensitive != findModel.isCaseSensitive) {
          prevIsCaseSensitive = findModel.isCaseSensitive
          terminalSearchComponent.fireCaseSensitiveChanged()
        }
      }
    }
  }

  override fun getFindModel(): FindModel = findModel

  override fun getComponent(): SearchReplaceComponent = searchComponent

  override fun hasMatches(): Boolean = hasMatches

  override fun searchForward() {
    terminalSearchComponent.fireKeyEvent(KeyEvent.VK_UP)
  }

  override fun searchBackward() {
    terminalSearchComponent.fireKeyEvent(KeyEvent.VK_DOWN)
  }

  override fun close() {
    terminalSearchComponent.fireKeyEvent(KeyEvent.VK_ESCAPE)
    IdeFocusManager.getInstance(terminalWidget.project).requestFocus(terminalWidget.terminalPanel, false)
  }

  override fun getData(dataId: String): Any? {
    return if (SearchSession.KEY.`is`(dataId)) this else null
  }

  private fun createSearchComponent(): SearchReplaceComponent {
    return SearchReplaceComponent
      .buildFor(terminalWidget.project, terminalWidget.terminalPanel)
      .addPrimarySearchActions(PrevOccurrenceAction(),
                               NextOccurrenceAction())
      .addExtraSearchActions(ToggleMatchCase())
      .withCloseAction { close() }
      .withDataProvider(this)
      .build()
  }

  private inner class MySearchComponent : JediTermWidget.SearchComponent {
    private val ignoreCaseListeners: CopyOnWriteArrayList<ItemListener> = CopyOnWriteArrayList()
    private val keyListeners: CopyOnWriteArrayList<KeyListener> = CopyOnWriteArrayList()

    override fun getText(): String {
      return searchComponent.searchTextComponent.text
    }

    override fun ignoreCase(): Boolean {
      return !findModel.isCaseSensitive
    }

    override fun getComponent(): JComponent {
      return searchComponentWrapper
    }

    override fun addDocumentChangeListener(listener: DocumentListener) {
      searchComponent.searchTextComponent.document.addDocumentListener(listener)
    }

    override fun addKeyListener(listener: KeyListener) {
      searchComponent.searchTextComponent.addKeyListener(listener)
      keyListeners.add(listener)
    }

    override fun addIgnoreCaseListener(listener: ItemListener) {
      ignoreCaseListeners.add(listener)
    }

    override fun onResultUpdated(results: SubstringFinder.FindResult?) {
      hasMatches = results != null && results.items.isNotEmpty()
    }

    override fun nextFindResultItem(selectedItem: SubstringFinder.FindResult.FindItem?) {
    }

    override fun prevFindResultItem(selectedItem: SubstringFinder.FindResult.FindItem?) {
    }

    fun fireKeyEvent(keyCode: Int) {
      for (keyListener in keyListeners) {
        keyListener.keyPressed(KeyEvent(searchComponent.searchTextComponent, 0, System.currentTimeMillis(), 0, keyCode, ' '))
      }
    }

    fun fireCaseSensitiveChanged() {
      for (ignoreCaseListener in ignoreCaseListeners) {
        ignoreCaseListener.itemStateChanged(null)
      }
    }
  }
}
