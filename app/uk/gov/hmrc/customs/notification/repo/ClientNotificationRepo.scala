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

import uk.gov.hmrc.customs.notification.domain.{ClientNotification, ClientSubscriptionId}

import scala.concurrent.Future

/**
  * Created by dev on 25/06/2018.
  */
trait ClientNotificationRepo {


  def save(cn: ClientNotification): Future[Boolean]
  //FIFO based on whatever we decide to use, this method  has to return the list in insertion order. for now, leaving the timestamp in there but it yet to be decided.
  // speak to Avinder & Paul to get more context
  def fetch(csid: ClientSubscriptionId): Future[List[ClientNotification]]


  def fetchDistinctNotificationCSIDsWhichAreNotLocked(): Future[Set[ClientSubscriptionId]]

  //make sure we log it properly, we cant recover from delete failure, we might need to raise an alert for this one. We'll come back to this one.
  def delete(mongoObjectId: String): Future[Unit]

}
