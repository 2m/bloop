package bloop.io

import java.nio.file.Path

import bloop.Project
import bloop.engine.{ExecutionContext, State}
import bloop.logging.Logger
import bloop.monix.FoldLeftSyncConsumer

import scala.collection.JavaConverters._
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.{DirectoryChangeEvent, DirectoryChangeListener, DirectoryWatcher}
import monix.eval.Task
import monix.reactive.{MulticastStrategy, Observable}

final class SourceWatcher(project: Project, dirs0: Seq[Path], logger: Logger) {
  private val dirs = dirs0.distinct
  private val dirsCount = dirs.size
  private val dirsAsJava: java.util.List[Path] = dirs.asJava

  // Create source directories if they don't exist, otherwise the watcher fails.
  import java.nio.file.Files
  dirs.foreach(p => if (!Files.exists(p)) Files.createDirectories(p) else ())

  import SourceWatcher.XDirectoryChangeEvent
  def watch(state0: State, action: State => Task[State]): Task[State] = {
    val ngout = state0.commonOptions.ngout
    def runAction(state: State, event: DirectoryChangeEvent): Task[State] = {
      // Someone that wants this to be supported by Windows will need to make it work for all terminals
/*      if (!BspServer.isWindows)
        logger.info("\u001b[H\u001b[2J") // Clean the terminal before acting on the file event action*/
      logger.debug(s"A ${event.prettyPrint} has triggered an event.")
      action(state)
    }

    val fileEventConsumer = FoldLeftSyncConsumer.consume[State, DirectoryChangeEvent](state0) {
      case (state, event) =>
        event.eventType match {
          case EventType.CREATE => runAction(state, event)
          case EventType.MODIFY => runAction(state, event)
          case EventType.OVERFLOW => runAction(state, event)
          case EventType.DELETE => Task.now(state)
        }
    }

    val (observer, observable) =
      Observable.multicast[DirectoryChangeEvent](MulticastStrategy.publish)(
        ExecutionContext.ioScheduler)

    val watcher = DirectoryWatcher.create(
      dirsAsJava,
      new DirectoryChangeListener {
        override def onEvent(event: DirectoryChangeEvent): Unit = {
          val targetFile = event.path()
          val targetPath = targetFile.toFile.getAbsolutePath()
          if (Files.isRegularFile(targetFile) &&
              (targetPath.endsWith(".scala") || targetPath.endsWith(".java"))) {
            val ack = observer.onNext(event)
             import scala.concurrent.ExecutionContext.Implicits.global
            logger.info(s"File watching received ack $ack after ${event.prettyPrint}")
            ack.onComplete(s => logger.info(s.toString))
            ()
          }
        }
      }
    )

    val watchingTask = Task {
      logger.info(s"File watching $dirsCount directories...")
      try watcher.watch()
      finally watcher.close()
    }.doOnCancel(Task {
      observer.onComplete()
      watcher.close()
      ngout.println(
        s"File watching on '${project.name}' and dependent projects has been successfully cancelled.")
    })

    val watchHandle = watchingTask.materialize.runAsync(ExecutionContext.ioScheduler)

    observable
      .consumeWith(fileEventConsumer)
      .doOnFinish(_ => Task(watchHandle.cancel()))
      .doOnCancel(Task(watchHandle.cancel()))
  }
}

object SourceWatcher {
  implicit class XDirectoryChangeEvent(event: DirectoryChangeEvent) {
    def prettyPrint: String = s"${event.eventType()} in ${event.path()} [${event.count()}]"
  }
}
