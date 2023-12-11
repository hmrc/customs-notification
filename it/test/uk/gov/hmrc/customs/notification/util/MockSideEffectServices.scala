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

package uk.gov.hmrc.customs.notification.util

import org.bson.types.ObjectId as BsonObjectId
import play.api.Logging
import play.api.mvc.RequestHeader
import uk.gov.hmrc.customs.notification.services.{DateTimeService, HeaderCarrierService, ObjectIdService, UuidService}
import uk.gov.hmrc.customs.notification.util.TestData.{Implicits, ObjectId, SomeUuid, TimeNow}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import uk.gov.hmrc.play.audit.http.connector.{AuditChannel, AuditConnector, DatastreamMetrics}

import java.time.ZonedDateTime
import java.util.UUID
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.ScalaDurationOps


class MockDateTimeService extends DateTimeService with Logging {
  private var time: ZonedDateTime = TimeNow

  override def now(): ZonedDateTime = time

  def travelBackInTime(duration: FiniteDuration): Unit = {
    time = time.minus(duration.toJava)
    logger.debug(s"Time travelled backwards [$duration] to [$time]")
  }

  def timeTravelToNow(): Unit = {
    time = TimeNow
    logger.debug(s"Time travelled back to the present ($time/${time.toInstant.toEpochMilli})")
  }
}

class MockUuidService extends UuidService {
  override def randomUuid(): UUID = SomeUuid
}

class MockObjectIdService extends ObjectIdService {
  var idToGive: BsonObjectId = ObjectId

  override def newId(): BsonObjectId = idToGive

  def nextTimeGive(id: BsonObjectId): Unit = {
    idToGive = id
  }

  def reset(): Unit = {
    idToGive = ObjectId
  }
}

class MockHeaderCarrierService extends HeaderCarrierService {
  override def newHc(): HeaderCarrier = Implicits.HeaderCarrier

  override def hcFrom(request: RequestHeader): HeaderCarrier = Implicits.HeaderCarrier
}

class MockAuditConnector extends AuditConnector {
  override def auditingConfig: AuditingConfig = AuditingConfig(
    enabled = false,
    consumer = None,
    auditSource = "auditing disabled",
    auditSentHeaders = false
  )

  override def auditChannel: AuditChannel = null // scalastyle:off null

  override def datastreamMetrics: DatastreamMetrics = DatastreamMetrics.disabled
}
