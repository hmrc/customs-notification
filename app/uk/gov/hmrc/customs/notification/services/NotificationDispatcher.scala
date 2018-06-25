package uk.gov.hmrc.customs.notification.services

import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId

import scala.concurrent.Future

/**
  * Created by dev on 25/06/2018.
  */
trait NotificationDispatcher {

  def process(csids: Set[ClientSubscriptionId]): Future[Unit]


}
