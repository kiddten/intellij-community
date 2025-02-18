// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.github.pullrequest.ui.details.action

import com.intellij.openapi.util.NlsActions
import org.jetbrains.plugins.github.pullrequest.data.service.GHPRSecurityService
import org.jetbrains.plugins.github.pullrequest.ui.details.model.GHPRStateModel
import javax.swing.AbstractAction

internal abstract class GHPRStateChangeAction(
  actionName: @NlsActions.ActionText String,
  protected val stateModel: GHPRStateModel,
  protected val securityService: GHPRSecurityService
) : AbstractAction(actionName) {

  init {
    stateModel.addAndInvokeBusyStateChangedListener(::update)
  }

  protected fun update() {
    isEnabled = computeEnabled()
  }

  protected open fun computeEnabled(): Boolean = !stateModel.isBusy
}