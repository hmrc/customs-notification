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

import java.util.concurrent.TimeUnit
import javax.inject.Singleton

import akka.actor.ActorSystem
import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, NotificationQueueConnector, PublicNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationRepo, LockOwnerId, LockRepo}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

@Singleton
class ClientWorkerImpl(
                        config: CustomsNotificationConfig,
                        actorSystem: ActorSystem,
                        repo: ClientNotificationRepo,
                        callbackDetailsConnector: ApiSubscriptionFieldsConnector,
                        pushConnector: PublicNotificationServiceConnector,
                        pullConnector: NotificationQueueConnector,
                        lockRepo: LockRepo,
                        logger: NotificationLogger
                      ) extends ClientWorker {

  private val extendLockDuration = org.joda.time.Duration.millis(config.pushNotificationConfig.lockRefreshDurationInMilliseconds)
  private val refreshDuration = Duration(config.pushNotificationConfig.lockRefreshDurationInMilliseconds, TimeUnit.MILLISECONDS)

  implicit val hc = HeaderCarrier()
  //TODO: should we pass in HeaderCarrier as an implicit parameter for logging?
  override def processNotificationsFor(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId): Future[Unit] = {
    //implicit HeaderCarrier required for ApiSubscriptionFieldsConnector
    //however looking at api-subscription-fields service I do not think it is required so keep new HeaderCarrier() for now

    val timer = actorSystem.scheduler.schedule(refreshDuration, refreshDuration, new Runnable {

      override def run() = {
        refreshLock(csid, lockOwnerId)
      }
    })

    // cleanup timer
    val eventuallyProcess = process(csid)
    eventuallyProcess.onComplete { _ => // always cancel timer ie for both Success and Failure cases
      logger.debug(s"about to cancel timer")
      val cancelled = timer.cancel()
      logger.debug(s"timer cancelled=$cancelled, timer.isCancelled=${timer.isCancelled}")
    }

    eventuallyProcess
  }

  private def refreshLock(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId)(implicit hc: HeaderCarrier): Future[Unit] = {
    lockRepo.tryToAcquireOrRenewLock(csid, lockOwnerId, extendLockDuration).map { refreshedOk =>
      if (!refreshedOk) {
        val ex = new IllegalStateException("Unable to refresh lock")
        throw ex
      }
    }.recover {
      // If refresh of the lock fails there is nothing much we can do apart from logging the error
      // It is unsafe to abort the notification processing as this could lead to the notifications
      // database being in an inconsistent state eg notification could have been sent OK, but if
      // we abort processing before notification is deleted then client could receive duplicate
      // notifications
      case e: Exception =>
        val msg = e.getMessage
        logger.error(msg) //TODO: extend logging API so that we can log an error on a throwable
    }
  }


  protected def process(csid: ClientSubscriptionId)(implicit hc: HeaderCarrier): Future[Unit] = {

    logger.info(s"About to process notifications")
    repo.fetch(csid).map { clientNotifications =>
      if (!pushNotifications(clientNotifications)) {
        enqueueClientNotificationsToPullQueue(csid)
      }

    }.recover {
      case e: Throwable =>
        //This error log should be failed to fetch collection and we can log Error Push by wrapping the foreach call in try/catch.....
        logger.error("Error fetching notifications")

    }
  }


  private def pushNotifications(notifications: List[ClientNotification]): Boolean = {
    // we need to stop this execution when failed to refresh the lock
    Try(notifications.foreach(pushClientNotification(_))) match {
      case Success(v) => true
      case Failure(e) => {
        // logger.error("Error pushing notification due to" + e.getMessage, e)
        logger.error("Error pushing notification")
        false
      }
    }
  }

  private def pushClientNotification(cn: ClientNotification)(implicit hc: HeaderCarrier): Unit = {


    Await.result(for {
      request <- eventualPublicNotificationRequest(cn)
      _ <- pushConnector.send(request)
      _ <- repo.delete(cn)
    } yield ()
      // we need to decide what the duration should be to wait here, should not be less than the http timeout * 3.
      , Duration.apply(3, TimeUnit.MILLISECONDS))
  }

  private def eventualPublicNotificationRequest(cn: ClientNotification)(implicit hc: HeaderCarrier): Future[PublicNotificationRequest] = {
    val futureMaybeCallbackDetails: Future[Option[DeclarantCallbackData]] = callbackDetailsConnector.getClientData(cn.csid.id.toString)
    futureMaybeCallbackDetails.map {
      maybeCallbackDetails =>
        val declarantCallbackData = maybeCallbackDetails.getOrElse(throw new IllegalStateException("No callback details found"))
        val request = publicNotificationRequest(declarantCallbackData, cn)
        request
    }
  }


  /////////////////////////////////  PULL NOTIFICATION CODE //////////////////////////////////
  private def publicNotificationRequest(
                                         declarantCallbackData: DeclarantCallbackData,
                                         cn: ClientNotification): PublicNotificationRequest = {

    PublicNotificationRequest(
      cn.csid.id.toString,
      PublicNotificationRequestBody(
        declarantCallbackData.callbackUrl,
        declarantCallbackData.securityToken,
        cn.notification.conversationId.id.toString,
        cn.notification.headers,
        cn.notification.payload
      ))
  }


  private def enqueueClientNotificationsToPullQueue(csid: ClientSubscriptionId)(implicit hc: HeaderCarrier): Unit = {

    (for {
      clientNotifications <- repo.fetch(csid)
      _ <- sequence(clientNotifications)(enqueueClientNotification)
    } yield ())
      .recover {
        case e: Exception =>
          logger.error("Error enqueueing notification to pull queue")
      }
  }

  private def enqueueClientNotification(cn: ClientNotification)(implicit hc: HeaderCarrier): Future[Unit] = {

    for {
      request <- eventualPublicNotificationRequest(cn)
      _ <- pullConnector.enqueue(request)
    } yield ()

  }

  /////////////////////////////////////////// end PULL code /////////////

  private def sequence[A, B](iter: Iterable[A])(fn: A => Future[B])
                            (implicit ec: ExecutionContext): Future[List[B]] =
    iter.foldLeft(Future(List.empty[B])) {
      (previousFuture, next) =>
        for {
          previousResults <- previousFuture
          next <- fn(next)
        } yield previousResults :+ next
    }

}
