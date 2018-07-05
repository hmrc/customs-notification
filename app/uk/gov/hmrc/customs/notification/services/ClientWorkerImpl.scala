/*
 * Copyright 2018 HM Revenue & Customs
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

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Singleton}

import akka.actor.ActorSystem
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, ClientSubscriptionId}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationRepo, LockOwnerId, LockRepo}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationDouble
import scala.util.control.NonFatal

case class PushProcessingException(msg: String) extends RuntimeException(msg)
case class PullProcessingException(msg: String) extends RuntimeException(msg)

/*
Assumptions:
- "Still Locked" - my interpretation is that lock refresh has not failed
Questions
- I still have concerns with blocking code inside a FUTURE
- I think if we have many concurrent CSIDs then with blocking code inside a FUTURE we may exhaust thread pool
  - https://stackoverflow.com/questions/15950998/futures-for-blocking-calls-in-scala
  - we may have to take responsibility of tuning thread pool with upper limit to prevent exhaustion

 */
@Singleton
class ClientWorkerImpl @Inject()(
                                  //TODO: pass in serviceConfig
                                  actorSystem: ActorSystem,
                                  repo: ClientNotificationRepo,
                                  callbackDetailsConnector: ApiSubscriptionFieldsConnector,
                                  push: PushClientNotificationService,
                                  pull: PullClientNotificationService,
                                  lockRepo: LockRepo,
                                  logger: NotificationLogger
                                ) extends ClientWorker {

  private val extendLockDuration =  org.joda.time.Duration.millis(1200)//org.joda.time.Duration.millis(config.pushNotificationConfig.lockRefreshDurationInMilliseconds)
  private val refreshDuration = 800 milliseconds //Duration(config.pushNotificationConfig.lockRefreshDurationInMilliseconds, TimeUnit.MILLISECONDS)
  private val awaitApiCallDuration = 1 second

  override def processNotificationsFor(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId): Future[Unit] = {
    //implicit HeaderCarrier required for ApiSubscriptionFieldsConnector
    //however looking at api-subscription-fields service I do not think it is required so keep new HeaderCarrier() for now
    implicit val hc = HeaderCarrier()
    implicit val refreshLockFailed: AtomicBoolean = new AtomicBoolean(false)
    val timer = actorSystem.scheduler.schedule(refreshDuration, refreshDuration, new Runnable {
      override def run() = {
        refreshLock(csid, lockOwnerId)
      }
    })

    // cleanup timer
    val eventuallyProcess = process(csid, lockOwnerId)
    eventuallyProcess.onComplete { _ => // always cancel timer ie for both Success and Failure cases
      logger.debug(s"about to cancel timer")

      val cancelled = timer.cancel()
      logger.debug(s"timer cancelled=$cancelled, timer.isCancelled=${timer.isCancelled}")
    }

    eventuallyProcess
  }

  private def refreshLock(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId)(implicit hc: HeaderCarrier, refreshLockFailed: AtomicBoolean): Future[Unit] = {
    lockRepo.tryToAcquireOrRenewLock(csid, lockOwnerId, extendLockDuration).map{ refreshedOk =>
      if (!refreshedOk) {
        val ex = new IllegalStateException("Unable to refresh lock")
        throw ex
      }
    }.recover{
      case NonFatal(e) =>
        refreshLockFailed.set(true)
        val msg = e.getMessage
        logger.error(msg) //TODO: extend logging API so that we can log an error on a throwable
    }
  }

  private def releaseLock(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId)(implicit hc: HeaderCarrier): Future[Unit] = {
    lockRepo.release(csid, lockOwnerId).map { _ =>
      logger.info("released lock")
    }.recover {
      case NonFatal(e) =>
        val msg = "error releasing lock"
        logger.error(msg) //TODO: extend logging API so that we can log an error on a throwable
    }
  }

  protected def process(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId)(implicit hc: HeaderCarrier, refreshLockFailed: AtomicBoolean): Future[Unit] = {

    logger.info(s"About to push notifications")

    repo.fetch(csid).map{ clientNotifications =>
      blockingInnerPushLoop(clientNotifications)
    }.flatMap {_ =>
      logger.info("Push successful")
      releaseLock(csid, lockOwnerId)
    }.recover{
      case PushProcessingException(msg) =>
        enqueueNotificationsOnPullQueue(csid, lockOwnerId)
      case NonFatal(e) =>
        logger.error("error pushing notifications")
        releaseLock(csid, lockOwnerId)
    }
  }

  private def blockingInnerPushLoop(clientNotifications: Seq[ClientNotification])(implicit hc: HeaderCarrier, refreshLockFailed: AtomicBoolean): Unit = {
    clientNotifications.foreach { cn =>
      if (refreshLockFailed.get) {
        throw new IllegalStateException("quiting pull processing - error refreshing lock")
      }

      val maybeDeclarantCallbackData = blockingMaybeDeclarantDetails(cn)

      maybeDeclarantCallbackData.fold(throw new PushProcessingException("Declarant details not found")){ declarantCallbackData =>
        if (push.send(declarantCallbackData, cn)) {
          blockingDeleteNotification(cn)
        } else {
          throw new PushProcessingException("Push of notification failed")
        }
      }
    }
  }

  private def blockingMaybeDeclarantDetails(cn: ClientNotification)(implicit hc: HeaderCarrier) = {
    Await.result(callbackDetailsConnector.getClientData(cn.csid.id.toString), awaitApiCallDuration)
  }

  private def blockingDeleteNotification(cn: ClientNotification)(implicit hc: HeaderCarrier): Unit = {
    Await.result(
      repo.delete(cn).recover{
        case NonFatal(e) => {
          // we can't do anything other than log delete error
          logger.error("error deleting notification")
        }
      },
      awaitApiCallDuration)
  }


  private def enqueueNotificationsOnPullQueue(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId)(implicit hc: HeaderCarrier, refreshLockFailed: AtomicBoolean) = {
    logger.info(s"About to enqueue notifications to pull queue")

    repo.fetch(csid).map{ clientNotifications =>
      blockingInnerPullLoop(clientNotifications)
    }.map{ _ =>
      logger.info("enqueue to pull queue successful")
      releaseLock(csid, lockOwnerId)
    }.recover{
      case NonFatal(e) =>
        logger.error("error enqueuing notifications to pull queue")
        releaseLock(csid, lockOwnerId)
    }
  }

  private def blockingInnerPullLoop(clientNotifications: Seq[ClientNotification])(implicit hc: HeaderCarrier, refreshLockFailed: AtomicBoolean): Unit = {
    clientNotifications.foreach { cn =>
      if (refreshLockFailed.get) {
        throw new IllegalStateException("quiting pull processing - error refreshing lock")
      }

      if (pull.send(cn)) {
        blockingDeleteNotification(cn)
      } else {
        throw new PullProcessingException("Pull of notification failed")
      }
    }
  }
}