package uk.gov.hmrc.customs.notification.repo

import org.joda.time.DateTime
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, ClientSubscriptionId}

import scala.concurrent.Future
import scala.concurrent.duration.Duration

/**
  * Created by dev on 25/06/2018.
  */
trait LockRepo {


  def lock(csid: ClientSubscriptionId, duration: Duration): Future[Boolean]

  def release(csid: ClientSubscriptionId): Future[Unit]

  // if it returns false, stop processing the client, abort abort abort
  def refreshLock(csid: ClientSubscriptionId, duration: Duration): Future[Boolean]
}
