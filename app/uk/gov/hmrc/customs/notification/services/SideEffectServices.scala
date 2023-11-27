/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.customs.notification.services

import akka.actor.ActorSystem
import org.bson.types.ObjectId
import play.api.mvc.{Request, RequestHeader}
import uk.gov.hmrc.customs.notification.models.NotificationId
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import java.time.{ZoneId, ZonedDateTime}
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

@Singleton
class DateTimeService @Inject()() {
  def now(): ZonedDateTime = ZonedDateTime.now(ZoneId.of("UTC"))
}

@Singleton
class NewNotificationIdService @Inject()() {
  def newId(): NotificationId = NotificationId(UUID.randomUUID())
}

@Singleton
class NewObjectIdService @Inject()() {
  def newId(): ObjectId = new ObjectId()
}
@Singleton
class HeaderCarrierService @Inject()() {
  def newHc(): HeaderCarrier = HeaderCarrier()

  def hcFrom(request: RequestHeader): HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
}
