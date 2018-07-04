package uk.gov.hmrc.customs.notification.services

import javax.inject.{Inject, Singleton}

import uk.gov.hmrc.customs.notification.connectors.PublicNotificationServiceConnector
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, DeclarantCallbackData}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger

@Singleton
class PushClientNotificationService @Inject()(publicNotificationServiceConnector: PublicNotificationServiceConnector,
                                              notificationsLogger: NotificationLogger) {

  def send(declarantCallbackData: DeclarantCallbackData, clientNotification: ClientNotification): Boolean = {

    true
  }

}
