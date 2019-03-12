/*
 * Copyright 2019 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.customs.notification.services

import java.math.MathContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.ActorSystem
import com.google.inject.ImplementedBy
import javax.inject.{Inject, Singleton}
import org.joda.time.Duration
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.domain.{ApiSubscriptionFields, ClientNotification, ClientSubscriptionId, CustomsNotificationConfig}
import uk.gov.hmrc.customs.notification.logging.LoggingHelper.logMsgPrefix
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationRepo, LockOwnerId, LockRepo}

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global // contains blocking code so uses standard scala ExecutionContext
import scala.concurrent.duration.{DurationDouble, FiniteDuration}
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.control.{ControlThrowable, NonFatal}

@ImplementedBy(classOf[ClientWorkerImpl])
trait ClientWorker {

  def processNotificationsFor(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId, lockDuration: Duration): Future[Unit]
}


/*
TODO:
- Do we need to also call isLocked?
Questions
- I still have concerns with blocking code inside a FUTURE
- I think if we have many concurrent CSIDs then with blocking code inside a FUTURE we may exhaust thread pool
  - https://stackoverflow.com/questions/15950998/futures-for-blocking-calls-in-scala
  - we may have to take responsibility of tuning thread pool with upper limit to prevent exhaustion

 */
@Singleton
class ClientWorkerImpl @Inject()(
                                  actorSystem: ActorSystem,
                                  repo: ClientNotificationRepo,
                                  callbackDetailsConnector: ApiSubscriptionFieldsConnector,
                                  push: PushClientNotificationService,
                                  pull: PullClientNotificationService,
                                  lockRepo: LockRepo,
                                  logger: CdsLogger,
                                  configService: CustomsNotificationConfig
                                ) extends ClientWorker {

  private case class PushProcessingException(msg: String) extends RuntimeException(msg)
  private case class PullProcessingException(msg: String) extends RuntimeException(msg)
  // ControlThrowable is a marker trait that ensures this is treated as a Fatal exception
  private case class ExitOuterLoopException(msg: String) extends RuntimeException(msg) with ControlThrowable

  // TODO: read this value from HTTP VERBS config and add 10%
  private val awaitApiCallDuration = 25 second
  private val awaitMongoCallDuration = 25 second
  protected val loopIncrementToLog = 1
  private val csidsNeverSentToPull = configService.pullExcludeConfig.csIdsToExclude.map{ id => ClientSubscriptionId(UUID.fromString(id))}
  private val pullExcludeEnabled = configService.pullExcludeConfig.pullExcludeEnabled

  override def processNotificationsFor(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId, lockDuration: Duration): Future[Unit] = {
    implicit val refreshLockFailed: AtomicBoolean = new AtomicBoolean(false)
    val refreshDuration = ninetyPercentOf(lockDuration)
    val timer = actorSystem.scheduler.schedule(initialDelay = refreshDuration, interval = refreshDuration, new Runnable {
      override def run(): Unit = {
        refreshLock(csid, lockOwnerId, lockDuration)
      }
    })

    // cleanup timer
    val eventuallyProcess = process(csid, lockOwnerId)
    eventuallyProcess.onComplete { _ => // always cancel timer ie for both Success and Failure cases
      logger.debug("about to cancel timer")
      val cancelled = timer.cancel()
      logger.debug(s"timer cancelled=$cancelled, timer.isCancelled=${timer.isCancelled}")
    }

    eventuallyProcess
  }

  private def ninetyPercentOf(lockDuration: Duration): FiniteDuration = {
    val ninetyPercentOfMillis: Long = BigDecimal(lockDuration.getMillis * 0.9, new MathContext(2)).toLong
    ninetyPercentOfMillis milliseconds
  }

  private def refreshLock(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId, lockDuration: Duration)(implicit refreshLockFailed: AtomicBoolean): Future[Unit] = {
    lockRepo.tryToAcquireOrRenewLock(csid, lockOwnerId, lockDuration).map{ refreshedOk =>
      if (!refreshedOk) {
        val ex = new IllegalStateException(s"[clientSubscriptionId=$csid] Unable to refresh lock")
        throw ex
      }
    }.recover{
      case NonFatal(e) =>
        refreshLockFailed.set(true)
        val msg = "error refreshing lock in timer: " + e.getMessage
        logger.error(msg) //TODO: extend logging API so that we can log an error on a throwable
    }
  }

  private def blockingReleaseLock(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId): Unit = {
    val f = lockRepo.release(csid, lockOwnerId).map { _ =>
      logger.info(s"[clientSubscriptionId=$csid][lockOwnerId=${lockOwnerId.id}] released lock")
    }.recover {
      case NonFatal(_) =>
        val msg = s"[clientSubscriptionId=$csid][lockOwnerId=${lockOwnerId.id}] error releasing lock"
        logger.error(msg) //TODO: extend logging API so that we can log an error on a throwable
    }
    scala.concurrent.blocking {
      Await.result(f, awaitMongoCallDuration)
    }
  }

  private def blockingFetch(csid: ClientSubscriptionId): Seq[ClientNotification] = {
    try {
      scala.concurrent.blocking {
        Await.result(repo.fetch(csid), awaitMongoCallDuration)
      }
    }
    catch {
      case NonFatal(e) =>
        logger.error(s"[clientSubscriptionId=$csid] error fetching notifications: ${e.getMessage}")
        Seq.empty[ClientNotification]
    }
  }

  protected def process(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId)(implicit refreshLockFailed: AtomicBoolean): Future[Unit] = {
    Future{
      blockingOuterProcessLoop(csid, lockOwnerId)
      blockingReleaseLock(csid, lockOwnerId)
    }
  }

  /**
    * The only way to exit this loop is:
    * <ol>
    * <li>if blockingFetch returns empty list or there is a FATAL exception
    * <li>maybeCurrentRecord == maybePreviousRecord (implies we are not making progress) so end this loop/worker. After the polling interval we will try again. Polling interval will hopefully allow us to reclaim resources via Garbage Collection
    * <li>there is a FATAL exception
    * <li>the push notification fails and its csid belongs to a configured collection of csids are not sent to the pull queue
    * </ol>
    * @param csid
    * @param lockOwnerId
    * @param hc
    * @param refreshLockFailed
    */
  private def blockingOuterProcessLoop(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId)(implicit refreshLockFailed: AtomicBoolean): Unit = {

    logger.info(s"[clientSubscriptionId=$csid] About to push notifications")

    var continue = true
    var counter = 0
    var maybeCurrentRecord: Option[ClientNotification] = None
    var maybePreviousRecord: Option[ClientNotification] = None
    try {
      while (continue) {
        try {
          val seq = blockingFetch(csid)
          maybePreviousRecord = maybeCurrentRecord
          maybeCurrentRecord = seq.headOption
          if (seq.isEmpty) {
            logger.info(s"[clientSubscriptionId=$csid] fetch returned zero records so exiting")
            continue = false
          } else if (maybeCurrentRecord == maybePreviousRecord) {
            logger.info(s"[clientSubscriptionId=$csid] not making progress processing records so exiting")
            continue = false
          }
          else {
            counter += 1
            if (loopIncrementToLog == 1 || counter % loopIncrementToLog == 0) {
              val msg = s"[clientSubscriptionId=$csid] processing notification record number $counter, logging every $loopIncrementToLog records"
              logger.info(msg)
            }
            blockingInnerPushLoop(seq)
            logger.info(s"[clientSubscriptionId=$csid] Push successful")
          }
        } catch {
          case PushProcessingException(_) =>
            if (pullExcludeEnabled && csidsNeverSentToPull.contains(csid)) {
              logger.info(s"[clientSubscriptionId=$csid] failed push and was not sent to pull queue")
              continue = false
            } else {
              blockingEnqueueNotificationsOnPullQueue(csid, lockOwnerId)
            }
          case NonFatal(e) =>
            logger.error(s"[clientSubscriptionId=$csid] error processing notifications: ${e.getMessage}")
        }
      }
    } catch {
      case ExitOuterLoopException(msg) =>
        logger.error(s"Fatal error - exiting processing: $msg")
    }

  }

  protected def blockingInnerPushLoop(clientNotifications: Seq[ClientNotification])(implicit refreshLockFailed: AtomicBoolean): Unit = {
    clientNotifications.foreach { cn =>
      if (refreshLockFailed.get) {
        throw ExitOuterLoopException(s"[clientSubscriptionId=${cn.csid}] error refreshing lock during push processing")
      }

      val maybeDeclarantCallbackData = blockingMaybeDeclarantDetails(cn)

      maybeDeclarantCallbackData.fold(throw PushProcessingException(s"[clientSubscriptionId=${cn.csid}] Declarant details not found")){ declarantCallbackData =>
        if (declarantCallbackData.fields.callbackUrl.isEmpty) {
          throw PushProcessingException(s"[clientSubscriptionId=${cn.csid}] callbackUrl is empty")
        } else {
          if (push.send(declarantCallbackData, cn)) {
            blockingDeleteNotification(cn)
          } else {
            throw PushProcessingException(s"[clientSubscriptionId=${cn.csid}] Push of notification failed")
          }
        }
      }
    }
  }

  private def blockingMaybeDeclarantDetails(cn: ClientNotification): Option[ApiSubscriptionFields] = {
    try {
      scala.concurrent.blocking {
        Await.result(callbackDetailsConnector.getClientData(cn.csid.id.toString), awaitApiCallDuration)
      }
    } catch {
      case NonFatal(e) =>
        throw ExitOuterLoopException(s"Error getting declarant details: ${e.getMessage}")
    }
  }

  private def blockingDeleteNotification(cn: ClientNotification): Unit = {
    scala.concurrent.blocking {
      Await.result(
        repo.delete(cn).recover {
          case NonFatal(e) =>
            // we can't do anything other than log delete error
            logger.error(s"${logMsgPrefix(cn)} error deleting notification")
        },
        awaitMongoCallDuration)
    }
  }


  private def blockingEnqueueNotificationsOnPullQueue(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId)(implicit refreshLockFailed: AtomicBoolean): Unit = {
    logger.info(s"[clientSubscriptionId=$csid] About to enqueue notifications to pull queue")

    try {
      blockingInnerPullLoop(blockingFetch(csid))
      logger.info(s"[clientSubscriptionId=$csid] enqueue to pull queue successful")
    } catch {
      case NonFatal(e) =>
        val msg = s"[clientSubscriptionId=$csid] error enqueuing notifications to pull queue: ${e.getMessage}"
        logger.error(msg)
    }

  }

  protected def blockingInnerPullLoop(clientNotifications: Seq[ClientNotification])(implicit refreshLockFailed: AtomicBoolean): Unit = {
    clientNotifications.foreach { cn =>
      if (refreshLockFailed.get) {
        throw ExitOuterLoopException(s"[clientSubscriptionId=${cn.csid}] error refreshing lock during pull processing")
      }
      if (pull.send(cn)) {
        blockingDeleteNotification(cn)
      } else {
        //when both customs-notification-gateway and api-notification-queue are down this exception will guarantee that notifications
        //are sent in order to the notification queue when it comes back up
        throw PullProcessingException("pull queue unavailable")
      }
    }
  }
}
