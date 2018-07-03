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

import javax.inject.Singleton
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, ClientSubscriptionId}

import scala.concurrent.Future

//TODO MC to be removed after CDD-1603
@Singleton
class DummyClientNotificationRepoImpl extends ClientNotificationRepo {
  override def save(cn: ClientNotification): Future[Boolean] = Future.successful(true)

  override def fetch(csid: ClientSubscriptionId): Future[List[ClientNotification]] = ???
  override def fetchDistinctNotificationCSIDsWhichAreNotLocked(): Future[Set[ClientSubscriptionId]] = ???

  override def delete(clientNotification: ClientNotification): Future[Unit] = ???
}
