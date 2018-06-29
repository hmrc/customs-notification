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

package uk.gov.hmrc.lock

import java.util.UUID

import play.api.libs.json.Json
import reactivemongo.api.DB
import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId
import uk.gov.hmrc.lock.LockFormats.{Lock, expiryTime}
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class NotificationLockRepository(implicit mongo: () => DB) extends LockRepository {
  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats

  def findAllNonExpiredLocks(): Future[Set[ClientSubscriptionId]] = withCurrentTime { now =>
      super.find(expiryTime -> Json.obj("$gte" -> now.toDateTime())).map({
        listOfLocks => listOfLocks.toSet[Lock].map(lock => ClientSubscriptionId(UUID.fromString(lock.id)))
      })
  }
}
