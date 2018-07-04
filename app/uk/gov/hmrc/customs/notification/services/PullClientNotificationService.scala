package uk.gov.hmrc.customs.notification.services

import javax.inject.{Inject, Singleton}

import uk.gov.hmrc.customs.notification.connectors.NotificationQueueConnector
import uk.gov.hmrc.customs.notification.domain.ClientNotification
import uk.gov.hmrc.customs.notification.logging.NotificationLogger

@Singleton
class PullClientNotificationService @Inject() (notificationQueueConnector: NotificationQueueConnector,
                                               notificationsLogger: NotificationLogger) {

  def send(clientNotification: ClientNotification): Boolean = {

    true
  }

}
