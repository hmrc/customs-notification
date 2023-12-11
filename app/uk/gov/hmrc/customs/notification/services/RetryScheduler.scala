/*
 * Copyright 2023 HM Revenue & Customs
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

import org.apache.pekko.actor.ActorSystem
import org.mongodb.scala.{Observable, Observer, Subscription}
import uk.gov.hmrc.customs.notification.config.RetrySchedulerConfig
import uk.gov.hmrc.customs.notification.connectors.ClientDataConnector.*
import uk.gov.hmrc.customs.notification.connectors.{ClientDataConnector, MetricsConnector}
import uk.gov.hmrc.customs.notification.models.*
import uk.gov.hmrc.customs.notification.models.Auditable.Implicits.auditableNotification
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.*
import uk.gov.hmrc.customs.notification.models.ProcessingStatus.{FailedAndBlocked, FailedButNotBlocked}
import uk.gov.hmrc.customs.notification.repo.Repository
import uk.gov.hmrc.customs.notification.repo.Repository.MongoDbError
import uk.gov.hmrc.customs.notification.util.FutureEither.Implicits.*
import uk.gov.hmrc.customs.notification.util.Helpers.ignoreResult
import uk.gov.hmrc.customs.notification.util.Logger
import uk.gov.hmrc.http.HeaderCarrier

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.*
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future, Promise}

@Singleton
class RetryScheduler @Inject()(actorSystem: ActorSystem,
                               retryService: RetryService,
                               config: RetrySchedulerConfig)(implicit ec: ExecutionContext) {
  if (config.enabled) {
    actorSystem.scheduler.scheduleWithFixedDelay(0.second, config.failedButNotBlockedDelay)(() => retryService.retryFailedButNotBlocked())
    actorSystem.scheduler.scheduleWithFixedDelay(0.seconds, config.failedAndBlockedDelay)(() => retryService.retryFailedAndBlocked())
  }
}

@Singleton
class RetryService @Inject()(repo: Repository,
                             sendService: SendService,
                             cache: ClientDataCache,
                             headerCarrierService: HeaderCarrierService,
                             metrics: MetricsConnector,
                             config: RetrySchedulerConfig)
                            (implicit ec: ExecutionContext) extends Logger {
  def retryFailedButNotBlocked(): Future[Unit] =
    retry(
      processingStatus = FailedButNotBlocked,
      maybeRetry = repo.getAllOutstanding,
      afterSingleSuccessfulRetry = _ => Future.unit
    )

  def retryFailedAndBlocked(): Future[Unit] =
    retry(
      processingStatus = FailedAndBlocked,
      maybeRetry = repo.getLatestOutstandingFailedAndBlockedForEachCsId,
      afterSingleSuccessfulRetry = { notification =>
        implicit val lc: LogContext = LogContext(notification)
        (for {
          count <- repo.unblockFailedAndBlocked(notification.csid).toFutureEither
          _ = logger.info(s"$count FailedAndBlocked notifications unblocked and queued for retry for this client subscription ID")
        } yield ()).value.map(ignoreResult)
      }
    )

  private def retry(processingStatus: ProcessingStatus,
                    maybeRetry: LogContext => Either[MongoDbError, Observable[Notification]],
                    afterSingleSuccessfulRetry: Notification => Future[Unit]): Future[Unit] = {
    implicit val lc: LogContext = LogContext.empty
    metrics.incrementRetryCounter()

    maybeRetry(lc) match {
      case Left(error) =>
        logger.error(error.message)
        Future.unit
      case Right(notificationObs) =>
        val promise = Promise[Unit]()

        notificationObs.subscribe(
          new RetryObserver(promise, processingStatus, afterSingleSuccessfulRetry)
        )

        promise.future
    }
  }

  private class RetryObserver(promise: Promise[Unit],
                              processingStatus: ProcessingStatus,
                              afterSingleSuccessfulRetry: Notification => Future[Unit]) extends Observer[Notification] {
    private implicit val hc: HeaderCarrier = headerCarrierService.newHc()
    private implicit val lc: LogContext = LogContext.empty
    private val totalProcessedCount: AtomicInteger = new AtomicInteger()
    private var totalAttemptedCount: Int = 0
    private var eventualResults: List[Future[Unit]] = List.empty
    private var subscription: Subscription = _

    override def onSubscribe(subscription: Subscription): Unit = {
      this.subscription = subscription
      subscription.request(config.retryBufferSize)
    }

    override def onNext(notification: Notification): Unit = {
      implicit val lc: LogContext = LogContext(notification)
      implicit val ac: AuditContext = AuditContext(notification)
      totalAttemptedCount += 1

      val thisEventualResult = {
        cache.getOrUpdate(notification.csid).flatMap {
          case None =>
            Future.unit
          case Some(sendData) =>
            (for {
              _ <- sendService.send(notification, sendData).toFutureEither
              _ <- afterSingleSuccessfulRetry(notification).map(Right(_)).toFutureEither
              _ = totalProcessedCount.incrementAndGet()
            } yield ()).value
              .map(_ => ignoreResult {
                subscription.request(1)
              })
        }
      }

      eventualResults = thisEventualResult :: eventualResults
    }

    override def onError(e: Throwable): Unit = {
      cleanUp {
        logger.error(s"Error occurred when retrying ${processingStatus.name} notifications " +
          s"(successfully retried [${totalProcessedCount.get()}] notifications)", e)
      }
    }

    override def onComplete(): Unit = {
      cleanUp {
        if (totalAttemptedCount > 0) {
          logger.info(s"Successfully retried ${totalProcessedCount.get()} out of $totalAttemptedCount " +
            s"${processingStatus.name} notifications")
        }
      }
    }

    private def cleanUp(doWhenComplete: => Unit): Unit = {
      cache.clear()
      subscription.unsubscribe()

      val allResults =
        Future.sequence(eventualResults)
          .map(_ => doWhenComplete)

      promise.completeWith(allResults)
    }
  }
}

@Singleton
private class ClientDataCache @Inject()(clientDataConnector: ClientDataConnector)
                                       (implicit ec: ExecutionContext) {
  private var underlying: Map[ClientSubscriptionId, Future[Option[SendData]]] = Map.empty

  def getOrUpdate(clientSubscriptionId: ClientSubscriptionId)
                 (implicit lc: LogContext,
                  hc: HeaderCarrier): Future[Option[SendData]] = {
    underlying.get(clientSubscriptionId) match {
      case Some(d) => d
      case None =>
        val newValue = clientDataConnector.get(clientSubscriptionId).map {
          case Left(_) =>
            None
          case Right(Success(clientData)) =>
            val result = Some(clientData.sendData)
            result
        }
        underlying += (clientSubscriptionId -> newValue)

        newValue
    }
  }

  def clear(): Unit = {
    underlying = Map.empty
  }
}
