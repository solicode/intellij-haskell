/*
 * Copyright 2016 Rik van der Kleij
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package intellij.haskell.external.component

import java.util.concurrent.TimeUnit

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.progress.{ProgressIndicator, ProgressManager, Task}
import com.intellij.openapi.project.Project
import intellij.haskell.HaskellNotificationGroup
import intellij.haskell.external.commandLine.StackCommandLine
import intellij.haskell.external.repl.StackReplsManager
import intellij.haskell.util.HaskellProjectUtil

import scala.concurrent.duration._

class StackProjectComponent(project: Project) extends ProjectComponent {
  override def getComponentName: String = "stack-repls-manager"

  override def projectClosed(): Unit = {}

  override def initComponent(): Unit = {}

  override def projectOpened(): Unit = {
    if (HaskellProjectUtil.isHaskellStackProject(project)) {
      ProgressManager.getInstance().run(new Task.Backgroundable(project, s"[$getComponentName] Starting Stack repls, building project, building tools and preloading cache", false) {

        def run(progressIndicator: ProgressIndicator) {
          progressIndicator.setText("Busy with building project and starting Stack repls")
          StackReplsManager.getProjectRepl(project).start()
          StackReplsManager.getGlobalRepl(project).start()

          progressIndicator.setText("Busy with preloading cache, building tools and/or rebuilding Hoogle database")
          val preloadCacheFuture = ApplicationManager.getApplication.executeOnPooledThread(new Runnable {
            override def run(): Unit = {
              StackReplsComponentsManager.preloadModuleIdentifiersCaches(project)

              HaskellNotificationGroup.logInfo("Restarting global repl to release memory")
              StackReplsManager.getGlobalRepl(project).restart()
            }
          })

          StackCommandLine.runCommand(Seq("exec", "--", "hoogle", "--numeric-version"), project) match {
            case Some(v) =>
              if (v.getStdout.trim > "5") {
                HaskellNotificationGroup.logInfo("Hoogle version > 5 is already installed")
              } else {
                StackCommandLine.executeBuild(project, Seq("build", "hoogle-5.0.4", "haskell-src-exts-1.18.2"), "Build of `hoogle`")
              }
            case _ => HaskellNotificationGroup.notifyBalloonWarning("Could not determine version of (maybe already installed) Hoogle. Version 5 of Hoogle will not be automatically build")
          }

          val buildToolsFuture = ApplicationManager.getApplication.executeOnPooledThread(new Runnable {
            override def run(): Unit = {
              StackCommandLine.executeBuild(project, Seq("build", HaskellDocumentationProvider.HaskellDocsName, HLintComponent.HlintName, "apply-refact"), "Build of `haskell-docs`, `hlint` and `apply-refact`")
            }
          })

          val rebuildHoogleFuture = ApplicationManager.getApplication.executeOnPooledThread(new Runnable {
            override def run(): Unit = {
              StackCommandLine.runCommand(Seq("hoogle", "--rebuild"), project, timeoutInMillis = 10.minutes.toMillis)
            }
          })

          if (!buildToolsFuture.isDone || !rebuildHoogleFuture.isDone || !preloadCacheFuture.isDone) {
            buildToolsFuture.get(15, TimeUnit.MINUTES)
            rebuildHoogleFuture.get(15, TimeUnit.MINUTES)
            preloadCacheFuture.get(15, TimeUnit.MINUTES)
          }
        }
      })
    }
  }

  override def disposeComponent(): Unit = {}
}