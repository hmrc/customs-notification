/*
 * Copyright 2024 HM Revenue & Customs
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

import com.codahale.metrics.MetricRegistry
import com.google.inject.ImplementedBy
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames.NOTIFICATION_ID_HEADER_NAME
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationConfig, HttpResultError, NotificationId, NotificationWorkItem}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemMongoRepo
import uk.gov.hmrc.customs.notification.services.Debug.colourln
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{Failed, Succeeded}
import uk.gov.hmrc.mongo.workitem.WorkItem

import java.time.{Instant, ZoneId}
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
@ImplementedBy(classOf[WorkItemServiceImpl])
trait WorkItemService {

  def processOne(): Future[Boolean]
}

class WorkItemServiceImpl @Inject()(
                                     repository: NotificationWorkItemMongoRepo,
                                     pushOrPullService: PushOrPullService,
                                     dateTimeService: DateTimeService,
                                     logger: NotificationLogger,
                                     metricRegistry: MetricRegistry,
                                     customsNotificationConfig: CustomsNotificationConfig
                                   )
                                   (implicit ec: ExecutionContext) extends WorkItemService {

  private val metricName = "declaration-digital-notification-retry-total"

  def processOne(): Future[Boolean] = {

    val failedBefore = dateTimeService.zonedDateTimeUtc.toInstant
    val availableBefore = failedBefore
    val eventuallyProcessedOne: Future[Boolean] = repository.pullOutstanding(failedBefore, availableBefore).flatMap {
      case Some(firstOutstandingItem) =>
        incrementCountMetric(metricName, firstOutstandingItem)
        pushOrPull(firstOutstandingItem).map { _ =>
          true
        }
      case None =>
        Future.successful(false)
    }
    eventuallyProcessedOne
  }


  def incrementCountMetric(metric: String, workItem: WorkItem[NotificationWorkItem]): Unit = {
    implicit val loggingContext = workItem.item
    logger.debug(s"incrementing counter for metric: $metric")
    metricRegistry.counter(s"$metric-counter").inc()
  }

  private def pushOrPull(workItem: WorkItem[NotificationWorkItem]): Future[Unit] = {

    implicit val loggingContext = workItem.item
    implicit val hc: HeaderCarrier = HeaderCarrier()
      .withExtraHeaders(maybeAddNotificationId(workItem.item.notification.notificationId): _*)

    logger.debug(s"attempting retry of $workItem")
    pushOrPullService.send(workItem.item).flatMap {
      case Right(connector) =>
        logger.info(s"$connector retry succeeded for $workItem")
        repository.setCompletedStatus(workItem.id, Succeeded)
      case Left(PushOrPullError(connector, resultError)) =>
        logger.info(s"$connector retry failed for $workItem with error $resultError. Setting status to " +
          s"PermanentlyFailed for all notifications with clientSubscriptionId ${workItem.item.clientSubscriptionId.toString}")
        (resultError match {
              case httpResultError: HttpResultError if httpResultError.is3xx || httpResultError.is4xx =>
                val availableAt = dateTimeService.zonedDateTimeUtc.plusMinutes(customsNotificationConfig.notificationConfig.nonBlockingRetryAfterMinutes)
                logger.error(s"Status response ${httpResultError.status} received while pushing notification, setting availableAt to $availableAt")
                repository.setCompletedStatusWithAvailableAt(workItem.id, Failed, httpResultError.status, availableAt) // increase failure count
              case _ =>
                repository.setCompletedStatus(workItem.id, Failed) // increase failure count
                repository.toPermanentlyFailedByCsId(workItem.item.clientSubscriptionId).map(_ => ())
            }).recover {
          case NonFatal(e) =>
            logger.error("Error updating database", e)
        }
    }.recover {
      case NonFatal(e) => // this should never happen as exceptions are recovered in all above code paths
        logger.error(s"error processing work item $workItem", e)
        Future.failed(e)
    }

  }

  private def maybeAddNotificationId(maybeNotificationId: Option[NotificationId]): Seq[(String, String)] = {
    maybeNotificationId.fold(Seq.empty[(String, String)]) { id => Seq((NOTIFICATION_ID_HEADER_NAME, id.toString)) }
  }
}
