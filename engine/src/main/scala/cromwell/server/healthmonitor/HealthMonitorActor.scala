//package cromwell.server.healthmonitor
//
//import java.util.concurrent.TimeoutException
//
//import akka.actor.{Actor, Props}
//import akka.pattern.{after, pipe}
//import cats._
//import cats.implicits._
//import com.typesafe.scalalogging.LazyLogging
//import HealthMonitorActor._
//import cromwell.core.Dispatcher
//
//import scala.concurrent.Future
//import scala.concurrent.duration._
//import scala.util.control.NonFatal
//import scala.language.postfixOps
//
///*
//  Checks:
//
//  PAPI (if backend exists)
//  GCS (if filesystem exists)
//  DB
//  Dockerhub (if exists)
// */
//
//class HealthMonitorActor private(val subsystems: List[Subsystem],
//                                 val futureTimeout: FiniteDuration,
//                                 val staleThreshold: FiniteDuration) extends Actor with LazyLogging {
//  implicit val ec = context.dispatcher
//
//  logger.info("Starting health monitor...")
//  val checkTick = context.system.scheduler.schedule(10 seconds, 1 minute, self, HealthMonitorActor.CheckAll)
//
//  override def postStop() = checkTick.cancel()
//
//  /**
//    * Contains each subsystem status along with a timestamp of when the entry was made.
//    * Initialized with unknown status.
//    */
//  private var data: Map[Subsystem, (SubsystemStatus, Long)] = {
//    val now = System.currentTimeMillis
//    subsystems.map(_ -> (UnknownStatus, now)).toMap
//  }
//
//  override def receive: Receive = {
//    case CheckAll => subsystems.foreach(checkSubsystem)
//    case Store(subsystem, status) => store(subsystem, status)
//    case GetCurrentStatus => sender ! getCurrentStatus
//  }
//
//  private def checkSubsystem(subsystem: Subsystem): Unit = {
//    val result = subsystem.check()
//    result.withTimeout(futureTimeout, s"Timed out after ${futureTimeout.toString} waiting for a response from ${subsystem.toString}")
//      .recover { case NonFatal(ex) =>
//        failedStatus(ex.getMessage)
//      } map {
//      Store(subsystem, _)
//    } pipeTo self
//  }
//
//  private def store(subsystem: Subsystem, status: SubsystemStatus): Unit = {
//    data = data + (subsystem -> (status, System.currentTimeMillis))
//    logger.debug(s"New health monitor state: $data")
//  }
//
//  private def getCurrentStatus: StatusCheckResponse = {
//    val now = System.currentTimeMillis()
//    // Convert any expired statuses to unknown
//    val processed = data.mapValues {
//      case (_, t) if now - t > staleThreshold.toMillis => UnknownStatus
//      case (status, _) => status
//    }
//    // overall status is ok iff all subsystems are ok
//    val overall = processed.forall(_._2.ok)
//    StatusCheckResponse(overall, processed)
//  }
//
//  /**
//    * A monoid used for combining SubsystemStatuses.
//    * Zero is an ok status with no messages.
//    * Append uses && on the ok flag, and ++ on the messages.
//    */
//  private implicit val SubsystemStatusMonoid = new Monoid[SubsystemStatus] {
//    def combine(a: SubsystemStatus, b: SubsystemStatus): SubsystemStatus = {
//      SubsystemStatus(a.ok && b.ok, a.messages |+| b.messages)
//    }
//
//    def empty: SubsystemStatus = OkStatus
//  }
//
//  /**
//    * Adds non-blocking timeout support to futures.
//    * Example usage:
//    * {{{
//    *   val future = Future(Thread.sleep(1000*60*60*24*365)) // 1 year
//    *   Await.result(future.withTimeout(5 seconds, "Timed out"), 365 days)
//    *   // returns in 5 seconds
//    * }}}
//    */
//  private implicit class FutureWithTimeout[A](f: Future[A]) {
//    def withTimeout(duration: FiniteDuration, errMsg: String): Future[A] =
//      Future.firstCompletedOf(Seq(f, after(duration, context.system.scheduler)(Future.failed(new TimeoutException(errMsg)))))
//  }
//
//  /**
//    * Checks database status by running a "select version()" query.
//    * We don't care about the result, besides checking that the query succeeds.
//    */
//  //  private def checkDB: Future[SubsystemStatus] = {
//  //    // Note: this uses the slick thread pool, not this actor's dispatcher.
//  //    logger.debug("Checking Database...")
//  //    slickDataSource.inTransaction(_.sqlDBStatus).map(_ => OkStatus)
//  //  }
//
//
//  /**
//    * Checks Google bucket status by doing a Get on the token bucket using the buckets service account.
//    */
//  //  private def checkGoogleBuckets: Future[SubsystemStatus] = {
//  //    logger.debug("Checking Google Buckets...")
//  //    // Note: call to `foldMap` depends on SubsystemStatusMonoid, defined implicitly below
//  //    bucketsToCheck.toList.foldMap { bucket =>
//  //      googleServicesDAO.getBucket(bucket).map {
//  //        case Some(_) => OkStatus
//  //        case None => failedStatus(s"Could not find bucket: $bucket")
//  //      }
//  //    }
//  //  }
//
//
//  /**
//    * Checks Google genomics status by doing a list() using the genomics service account.
//    * Does not validate the results; only that the API call succeeds.
//    */
//  //  private def checkGoogleGenomics: Future[SubsystemStatus] = {
//  //    logger.debug("Checking Google Genomics...")
//  //    googleServicesDAO.listGenomicsOperations.map { _ =>
//  //      OkStatus
//  //    }
//  //  }
//}
//
//object HealthMonitorActor {
//  import Dispatcher.HealthMonitorDispatcher
//
//  val DefaultFutureTimeout = 1 minute
//  val DefaultStaleThreshold = 15 minutes
//
//  val OkStatus = SubsystemStatus(true, None)
//  val UnknownStatus = SubsystemStatus(false, Some(List("Unknown status")))
//  def failedStatus(message: String) = SubsystemStatus(false, Some(List(message)))
//
//  final case class Subsystem(name: String, check: () => Future[SubsystemStatus])
//  final case class SubsystemStatus(ok: Boolean, messages: Option[List[String]])
//  final case class StatusCheckResponse(ok: Boolean, systems: Map[Subsystem, SubsystemStatus])
//
//  // Actor API:
//  sealed trait HealthMonitorMessage
//  /** Triggers subsystem checking */
//  case object CheckAll extends HealthMonitorMessage
//  /** Stores status for a particular subsystem */
//  final case class Store(subsystem: Subsystem, status: SubsystemStatus) extends HealthMonitorMessage
//  /** Retrieves current status and sends back to caller */
//  case object GetCurrentStatus extends HealthMonitorMessage
//
//  def props(checks: List[Subsystem],
//            futureTimeout: FiniteDuration = DefaultFutureTimeout,
//            staleThreshold: FiniteDuration = DefaultStaleThreshold): Props = {
//    Props(new HealthMonitorActor(checks, futureTimeout, staleThreshold)).withDispatcher(HealthMonitorDispatcher)
//  }
//}