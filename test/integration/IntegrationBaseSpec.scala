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

package integration

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.bson.types.ObjectId
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, TestSuite}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api._
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.RequestHeader
import uk.gov.hmrc.customs.notification.models.NotificationId
import uk.gov.hmrc.customs.notification.services.{DateTimeService, HeaderCarrierService, NewNotificationIdService, NewObjectIdService}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import uk.gov.hmrc.play.audit.http.connector.{AuditChannel, AuditConnector, DatastreamMetrics}
import util.{IntegrationTest, TestData}

import java.time.ZonedDateTime

trait IntegrationBaseSpec extends TestSuite
  with BeforeAndAfterEach
  with BeforeAndAfterAll
  with GuiceOneServerPerSuite {

  lazy val wireMockServer = new WireMockServer(wireMockConfig().port(IntegrationTest.TestPort))

  override def beforeAll(): Unit = {
    super.beforeAll()
    if (!wireMockServer.isRunning) wireMockServer.start()
    WireMock.configureFor(IntegrationTest.TestHost, IntegrationTest.TestPort)
  }

  override def afterAll(): Unit = {
    try {
      super.afterAll()
    } finally {
      wireMockServer.stop()
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    wireMockServer.resetAll()
  }

  override def fakeApplication(): Application = new GuiceApplicationBuilder()
    .overrides(
      bind[AuditConnector].toInstance(MockAuditConnector),
      bind[DateTimeService].toInstance(MockDateTimeService),
      bind[NewNotificationIdService].toInstance(MockNewNotificationIdService),
      bind[NewObjectIdService].toInstance(MockNewObjectIdService),
      bind[HeaderCarrierService].toInstance(MockHeaderCarrierService)
    )
    .configure(Map(
      "auditing.enabled" -> false,
      "metrics.enabled" -> false,
      "retry.scheduler.enabled" -> false,
      "auth.token.internal" -> TestData.BasicAuthTokenValue,
      "internal.clientIds.0" -> TestData.InternalClientId.id,
      "microservice.services.public-notification.host" -> IntegrationTest.TestHost,
      "microservice.services.public-notification.port" -> IntegrationTest.TestPort,
      "microservice.services.public-notification.context" -> IntegrationTest.ExternalPushUrlContext,
      "microservice.services.notification-queue.host" -> IntegrationTest.TestHost,
      "microservice.services.notification-queue.port" -> IntegrationTest.TestPort,
      "microservice.services.notification-queue.context" -> IntegrationTest.PullQueueContext,
      "microservice.services.api-subscription-fields.host" -> IntegrationTest.TestHost,
      "microservice.services.api-subscription-fields.port" -> IntegrationTest.TestPort,
      "microservice.services.api-subscription-fields.context" -> IntegrationTest.ApiSubsFieldsUrlContext,
      "microservice.services.customs-notification-metrics.host" -> IntegrationTest.TestHost,
      "microservice.services.customs-notification-metrics.port" -> IntegrationTest.TestPort,
      "microservice.services.customs-notification-metrics.context" -> IntegrationTest.MetricsUrlContext,
      "ttlInSeconds" -> 42,
      "retry.poller.interval.milliseconds" -> 2000,
      "unblock.poller.interval.milliseconds" -> 2000,
      "retry.poller.retryAfterFailureInterval.seconds" -> 2,
      "non.blocking.retry.after.minutes" -> 1,
      "hotfix.translates.0" -> s"${TestData.OldClientSubscriptionId.toString}:${TestData.NewClientSubscriptionId.toString}",
      "mongodb.uri" -> "mongodb://localhost:27017/customs-notification"
    ))
    .build()
}

object MockDateTimeService extends DateTimeService {
  override def now(): ZonedDateTime = TestData.TimeNow
}

object MockNewNotificationIdService extends NewNotificationIdService {
  override def newId(): NotificationId = TestData.NotificationId
}

object MockNewObjectIdService extends NewObjectIdService {
  override def newId(): ObjectId = TestData.ObjectId
}

object MockHeaderCarrierService extends HeaderCarrierService {
  override def newHc(): HeaderCarrier = TestData.HeaderCarrier

  override def hcFrom(request: RequestHeader): HeaderCarrier = TestData.HeaderCarrier
}

object MockAuditConnector extends AuditConnector {
  override def auditingConfig: AuditingConfig = AuditingConfig(
    enabled = false,
    consumer = None,
    auditSource = "auditing disabled",
    auditSentHeaders = false
  )

  override def auditChannel: AuditChannel = ???

  override def datastreamMetrics: DatastreamMetrics = DatastreamMetrics.disabled
}
