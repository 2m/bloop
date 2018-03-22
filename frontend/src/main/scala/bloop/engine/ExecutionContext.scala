package bloop.engine

import java.util.concurrent.{Executors, LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}

import monix.execution.Scheduler
import monix.execution.schedulers.ExecutorScheduler

object ExecutionContext {
  private[bloop] val nCPUs = Runtime.getRuntime.availableProcessors() + 1

  // This inlines the implementation of `Executors.newFixedThreadPool` to avoid losing the type
  private[bloop] lazy val executor: ThreadPoolExecutor =
    new ThreadPoolExecutor(nCPUs, nCPUs, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue());
  val a = Executors.newScheduledThreadPool(10)

  import monix.execution.Scheduler
  implicit lazy val bspScheduler: Scheduler = Scheduler {
    // TODO: Revisit this.
    java.util.concurrent.Executors.newFixedThreadPool(4)
  }

  import monix.execution.UncaughtExceptionReporter.LogExceptionsToStandardErr
  import monix.execution.ExecutionModel
  implicit lazy val scheduler: Scheduler = monix.execution.Scheduler.Implicits.global

  //ExecutorScheduler(executor, LogExceptionsToStandardErr, ExecutionModel.AlwaysAsyncExecution)
  implicit lazy val ioScheduler: Scheduler = Scheduler.io("bloop-io")
}
