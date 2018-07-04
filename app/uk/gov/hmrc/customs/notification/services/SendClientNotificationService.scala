package uk.gov.hmrc.customs.notification.services

import javax.inject.{Inject, Singleton}

import uk.gov.hmrc.customs.notification.logging.NotificationLogger

@Singleton
class SendClientNotificationService @Inject()(notificationsLogger: NotificationLogger) {

  def send(): Boolean = {

    true
  }

}
