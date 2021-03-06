package async

import scala.util.continuations._
import akka.dispatch.ExecutionContext
import akka.dispatch.Future
import akka.dispatch.Promise
import akka.dispatch.ExecutionContexts
import scala.concurrent.forkjoin.ForkJoinPool
import java.util.concurrent.atomic.AtomicReference

object Async {
  /** Holds the ExecutionContext captured by async for use in await. */
  private val localContext = new ThreadLocal[ExecutionContext]()
  /** By name to runnable. */
  implicit def toRunnable[T](f: => T): Runnable = new Runnable { def run = f }
  /** Default ExecutionContext. */
  val defaultContext = new ExecutionContext {
    val pool = new ForkJoinPool // Might need some configuration
    def execute(r: Runnable) { pool.execute(r) }
    def reportFailure(t: Throwable) { sys.error(t.toString()) }
  }
  
  def async[A](body: => A @suspendable)(implicit ec: ExecutionContext = defaultContext): Future[A] = {
    assert(ec != null, "ec must not be null!")
    val p = Promise[A]()(ec)
    val runnable = toRunnable { 
      localContext.set(ec)
      reset[Unit,Unit] { 
        try { p.success(body); () }
        catch { case e => { p.failure(e); () } 
        }
      }  
    }
    ec.execute(runnable)
    p.future
  }
  
  def await[A](block: => Future[A]): A@suspendable = {
    val ec = localContext get()
    val a = shift[Either[Throwable,A],Unit,Unit] { cont: (Either[Throwable,A] => Unit) =>
      block onComplete { r => ec.execute(cont(r)) }
    }
    a.fold(throw _, x => x)
  }
}
