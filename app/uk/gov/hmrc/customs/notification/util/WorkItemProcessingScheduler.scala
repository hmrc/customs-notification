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

package uk.gov.hmrc.customs.notification.util

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import com.kenshoo.play.metrics.Metrics
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.customs.notification.config.AppConfig
import uk.gov.hmrc.customs.notification.models.Loggable
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableUnit
import uk.gov.hmrc.customs.notification.repo.NotificationRepo
import uk.gov.hmrc.customs.notification.services.{SendNotificationService, WorkItemService}
import uk.gov.hmrc.customs.notification.util.FailedButNotBlockedActor._
import uk.gov.hmrc.http.HeaderCarrier

import java.time.ZonedDateTime
import javax.inject.Inject
import scala.collection.immutable.ListMap
import scala.concurrent.Future
import scala.util.{Failure, Success}

// TODO: Move all of this over to UnblockPollerService
// TODO: Write tests
class WorkItemProcessingScheduler @Inject()(queueProcessor: WorkItemService,
                                            repo: NotificationRepo,
                                            logger: NotificationLogger,
                                            metrics: Metrics,
                                            config: AppConfig,
                                            now: () => ZonedDateTime,
                                            sendNotificationService: SendNotificationService)
                                           (implicit actorSystem: ActorSystem,
                                            applicationLifecycle: ApplicationLifecycle) {

//  private val pollingActor = actorSystem.actorOf(Props(new FailedButNotBlockedActor(queueProcessor, config, logger)))
//
//  logger.info("Starting retry poller", ())
//  pollingActor ! Poll
//  applicationLifecycle.addStopHook { () =>
//    pollingActor ! PoisonPill
//    Future.successful(())
//  }
}

class FailedButNotBlockedActor(repo: NotificationRepo,
                               now: () => ZonedDateTime,
                               config: AppConfig,
                               metrics: Metrics,
                               logger: NotificationLogger) extends Actor {

  import context.dispatcher

  implicit val loggableThis: Loggable[FailedButNotBlockedActor] =
    _ => ListMap("unblocker" -> Some("FailedButNotBlocked"))

  override def receive: Receive = {
    case Poll =>
      process() andThen {
        case Success(SuccessfullyRetried) =>
          self ! Poll
        case Success(NothingToRetry) =>
          context.system.scheduler.scheduleOnce(config.retryFailedAndNotBlockedDelay, self, Poll)
        case Success(ErrorRetrying) =>
          context.system.scheduler.scheduleOnce(config.retryFailedAndNotBlockedDelay, self, Poll)
        case Failure(e) =>
          logger.error("Queue processing failed", e, this)
          context.system.scheduler.scheduleOnce(config.retryPollerAfterFailureInterval, self, Poll)
      }
  }

  def process(): Future[Result] = {
    val before = now().toInstant
    val eventuallyProcessedOne = repo.pullOutstanding(before, before).flatMap {
      case Some(firstOutstandingNotificationWorkItem) =>
        metrics.defaultRegistry.counter("declaration-digital-notification-retry-total-counter").inc()
        implicit val headerCarrier: HeaderCarrier = HeaderCarrier()

        //          httpConnector.get(apiSubsFieldsRequest).flatMap()
        //        eventuallyMaybeApiSubscriptionFields.map { maybeApiSubscriptionFields =>
        //          maybeApiSubscriptionFields.map(apiSubscriptionFields => SendNotificationService.send(firstOutstandingNotificationWorkItem, apiSubscriptionFields, false))
        //        }
        Future.successful(SuccessfullyRetried)
      case None =>
        Future.successful(NothingToRetry)
    }
    eventuallyProcessedOne
  }
}

object FailedButNotBlockedActor {
  case object Poll

  sealed trait Result

  case object SuccessfullyRetried extends Result

  case object NothingToRetry extends Result

  case object ErrorRetrying extends Result
}

