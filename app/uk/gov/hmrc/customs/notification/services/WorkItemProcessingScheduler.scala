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

import org.apache.pekko.actor.{Actor, ActorSystem, PoisonPill, Props}
import org.apache.pekko.event.Logging
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.customs.notification.domain.CustomsNotificationConfig
import uk.gov.hmrc.customs.notification.logging.CdsLogger

import java.util.concurrent.{Executors, TimeUnit}
import javax.inject.Inject
import scala.concurrent.Future
import scala.util.{Failure, Success}

class WorkItemProcessingScheduler @Inject()(queueProcessor: WorkItemService,
                                            config: CustomsNotificationConfig,
                                            logger: CdsLogger)
                                           (implicit actorSystem: ActorSystem,
                                            applicationLifecycle: ApplicationLifecycle) {

  case object Poll

  class ContinuousPollingActor extends Actor {

    import context.dispatcher
    val log = Logging(context.system, this)

    override def receive: Receive = {
      case Poll =>
        queueProcessor.processOne() andThen {
          case Success(true) =>
            self ! Poll
          case Success(false) =>
            context.system.scheduler.scheduleOnce(config.notificationConfig.retryPollerInterval, self, Poll)
          case Failure(e) =>
            logger.error("Queue processing failed", e)
            context.system.scheduler.scheduleOnce(config.notificationConfig.retryPollerAfterFailureInterval, self, Poll)
        }
    }

  }

  private lazy val pollingActors = List.fill(config.notificationConfig.retryPollerInstances)(actorSystem.actorOf(Props(new ContinuousPollingActor())))

  private val bootstrap = new Runnable {
    override def run(): Unit = {
      pollingActors.foreach { pollingActor =>
        pollingActor ! Poll
      }
    }
  }

  def shutDown(): Unit = {
    pollingActors.foreach { pollingActor =>
      pollingActor ! PoisonPill
    }
  }

  if (config.notificationConfig.retryPollerEnabled) {
    logger.info("about to start retry poller")

    // Start the poller after a delay.
    Executors.newScheduledThreadPool(1).schedule(
      bootstrap, config.notificationConfig.retryPollerInterval.toMillis, TimeUnit.MILLISECONDS)

    applicationLifecycle.addStopHook { () =>
      shutDown()
      Future.successful(())
    }
  } else {
    logger.info("retry poller is disabled")
  }
}
