package cromwell.services.healthmonitor

import java.util.concurrent.TimeoutException

import akka.actor.Actor
import akka.pattern.{after, pipe}
import cats._
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.language.postfixOps
import HealthMonitorServiceActor._
import cromwell.services.ServiceRegistryActor.ServiceRegistryMessage

trait HealthMonitorServiceActor extends Actor with LazyLogging {
  def subsystems: List[Subsystem]

  implicit val ec = context.system.dispatcher

  val futureTimeout: FiniteDuration = DefaultFutureTimeout
  val staleThreshold: FiniteDuration = DefaultStaleThreshold

  logger.info("Starting health monitor...")
  val checkTick = context.system.scheduler.schedule(10 seconds, 1 minute, self, CheckAll)

  override def postStop() = checkTick.cancel()

  /**
    * Contains each subsystem status along with a timestamp of when the entry was made.
    * Initialized with unknown status.
    */
  private var data: Map[Subsystem, (SubsystemStatus, Long)] = {
    val now = System.currentTimeMillis
    subsystems.map(_ -> (UnknownStatus, now)).toMap
  }

  override def receive: Receive = {
    case CheckAll => subsystems.foreach(checkSubsystem)
    case Store(subsystem, status) => store(subsystem, status)
    case GetCurrentStatus => sender ! getCurrentStatus
  }

  private def checkSubsystem(subsystem: Subsystem): Unit = {
    val result = subsystem.check()
    result.withTimeout(futureTimeout, s"Timed out after ${futureTimeout.toString} waiting for a response from ${subsystem.toString}")
      .recover { case NonFatal(ex) =>
        failedStatus(ex.getMessage)
      } map {
      Store(subsystem, _)
    } pipeTo self
  }

  private def store(subsystem: Subsystem, status: SubsystemStatus): Unit = {
    data = data + (subsystem -> (status, System.currentTimeMillis))
    logger.debug(s"New health monitor state: $data")
  }

  private def getCurrentStatus: StatusCheckResponse = {
    val now = System.currentTimeMillis()
    // Convert any expired statuses to unknown
    val processed = data.mapValues {
      case (_, t) if now - t > staleThreshold.toMillis => UnknownStatus
      case (status, _) => status
    }
    // overall status is ok iff all subsystems are ok
    val overall = processed.forall(_._2.ok)
    StatusCheckResponse(overall, processed)
  }

  /**
    * A monoid used for combining SubsystemStatuses.
    * Zero is an ok status with no messages.
    * Append uses && on the ok flag, and ++ on the messages.
    */
  private implicit val SubsystemStatusMonoid = new Monoid[SubsystemStatus] {
    def combine(a: SubsystemStatus, b: SubsystemStatus): SubsystemStatus = {
      SubsystemStatus(a.ok && b.ok, a.messages |+| b.messages)
    }

    def empty: SubsystemStatus = OkStatus
  }

  /**
    * Adds non-blocking timeout support to futures.
    * Example usage:
    * {{{
    *   val future = Future(Thread.sleep(1000*60*60*24*365)) // 1 year
    *   Await.result(future.withTimeout(5 seconds, "Timed out"), 365 days)
    *   // returns in 5 seconds
    * }}}
    */
  private implicit class FutureWithTimeout[A](f: Future[A]) {
    def withTimeout(duration: FiniteDuration, errMsg: String): Future[A] =
      Future.firstCompletedOf(Seq(f, after(duration, context.system.scheduler)(Future.failed(new TimeoutException(errMsg)))))
  }
}

object HealthMonitorServiceActor {
  val DefaultFutureTimeout = 1 minute
  val DefaultStaleThreshold = 15 minutes

  val OkStatus = SubsystemStatus(true, None)
  val UnknownStatus = SubsystemStatus(false, Some(List("Unknown status")))
  def failedStatus(message: String) = SubsystemStatus(false, Some(List(message)))

  final case class Subsystem(name: String, check: () => Future[SubsystemStatus])
  final case class SubsystemStatus(ok: Boolean, messages: Option[List[String]])

  sealed abstract class HealthMonitorServiceActorRequest
  case object CheckAll extends HealthMonitorServiceActorRequest
  final case class Store(subsystem: Subsystem, status: SubsystemStatus) extends HealthMonitorServiceActorRequest
  case object GetCurrentStatus extends HealthMonitorServiceActorRequest with ServiceRegistryMessage { override val serviceName = "HealthMonitor" }

  sealed abstract class HealthMonitorServiceActorResponse
  final case class StatusCheckResponse(ok: Boolean, systems: Map[Subsystem, SubsystemStatus]) extends HealthMonitorServiceActorResponse
}