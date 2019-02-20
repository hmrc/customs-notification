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

import cats.data.OptionT
import cats.implicits._
import com.google.inject.ImplementedBy
import javax.inject.Inject
import uk.gov.hmrc.customs.notification.domain.NotificationWorkItem
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemMongoRepo
import uk.gov.hmrc.workitem.{Failed, Succeeded, WorkItem}
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[WorkItemServiceImpl])
trait WorkItemService {

  def processOne(): Future[Boolean]
}

class WorkItemServiceImpl @Inject()(
                                             repository: NotificationWorkItemMongoRepo,
                                             pushOrPullService: PushOrPullService,
                                             dateTimeService: DateTimeService,
                                             logger: NotificationLogger
  ) extends WorkItemService {

  def processOne(): Future[Boolean] = {

    val failedBefore = dateTimeService.zonedDateTimeUtc.toDateTime
    val availableBefore = failedBefore

    val result: OptionT[Future, Unit] = for {
      firstOutstandingItem <- OptionT(
        repository.pullOutstanding(failedBefore, availableBefore))
      _ <- OptionT.liftF(pushOrPull(firstOutstandingItem))
    } yield ()

    val somethingHasBeenProcessed = result.value.map(_.isDefined)

    somethingHasBeenProcessed
  }

  private def pushOrPull(workItem: WorkItem[NotificationWorkItem]): Future[Unit] = {

    implicit val loggingContext = workItem.item

    pushOrPullService.send(workItem.item).flatMap{
      case Right(connector) =>
        logger.info(s"Retry succeeded for $connector")
        repository.setCompletedStatus(workItem.id, Succeeded)
      case Left(PushOrPullError(connector, resultError)) =>
        logger.info(s"Retry failed for $connector with error $resultError. Setting status to " +
          s"PermanentlyFailed for all notifications with clientId ${workItem.item.clientId.toString}")
        (for {
          _ <- repository.setCompletedStatus(workItem.id, Failed) // increase failure count
          _ <- repository.toPermanentlyFailedByClientId(workItem.item.clientId)
        } yield ()).recover {
          case NonFatal(e) =>
            logger.error("Error updating database", e)
        }
    }.recover{
      case NonFatal(e) => // this should never happen as exceptions are recovered in all above code paths
        logger.error(s"error processing notification ${workItem.item}", e)
        Future.failed(e)
    }

  }

}
