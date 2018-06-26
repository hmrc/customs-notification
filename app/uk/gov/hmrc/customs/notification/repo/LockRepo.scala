/*
 * Copyright 2018 HM Revenue & Customs
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
