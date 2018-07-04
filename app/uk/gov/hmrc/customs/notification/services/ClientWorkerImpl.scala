package uk.gov.hmrc.customs.notification.services

import javax.inject.{Inject, Singleton}

import akka.actor.ActorSystem
import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationRepo, LockRepo}

import scala.concurrent.Future

@Singleton
class ClientWorkerImpl @Inject()(
                        actorSystem: ActorSystem,
                        repo: ClientNotificationRepo,
                        pull: PullClientNotificationService,
                        push: PushClientNotificationService,
                        lockRepo: LockRepo,
                        logger: NotificationLogger
                      ) extends ClientWorker {

  override def processNotificationsFor(csid: ClientSubscriptionId): Future[Unit] = {
    Future.successful(())
  }
}