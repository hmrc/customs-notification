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
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import uk.gov.hmrc.customs.notification.models.NotificationId
import uk.gov.hmrc.customs.notification.repo.Repository
import uk.gov.hmrc.customs.notification.services.{DateTimeService, HeaderCarrierService, NotificationIdService, ObjectIdService}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import uk.gov.hmrc.play.audit.http.connector.{AuditChannel, AuditConnector, DatastreamMetrics}
import util.{IntegrationTestData, TestData}

import java.time.ZonedDateTime
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.ScalaDurationOps

trait IntegrationBaseSpec extends TestSuite
  with FutureAwaits
  with DefaultAwaitTimeout
  with BeforeAndAfterEach
  with BeforeAndAfterAll
  with GuiceOneServerPerSuite {

  lazy val wireMockServer = new WireMockServer(wireMockConfig().port(IntegrationTestData.TestPort))

  override def beforeAll(): Unit = {
    super.beforeAll()
    if (!wireMockServer.isRunning) wireMockServer.start()
    WireMock.configureFor(IntegrationTestData.TestHost, IntegrationTestData.TestPort)
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
    mockDateTimeService.reset()
    mockObjectIdService.reset()
    if (clearRepoBeforeEachTest) await(mockRepo.collection.drop().toFuture())
  }

  protected val clearRepoBeforeEachTest: Boolean = false
  protected lazy val mockRepo: Repository = app.injector.instanceOf[Repository]
  protected val mockDateTimeService: MockDateTimeService = new MockDateTimeService
  protected val mockObjectIdService: MockObjectIdService = new MockObjectIdService

  override def fakeApplication(): Application = new GuiceApplicationBuilder()
    .overrides(
      bind[AuditConnector].toInstance(MockAuditConnector),
      bind[DateTimeService].toInstance(mockDateTimeService),
      bind[NotificationIdService].toInstance(MockNotificationIdService),
      bind[ObjectIdService].toInstance(mockObjectIdService),
      bind[HeaderCarrierService].toInstance(MockHeaderCarrierService)
    )
    .configure(Map(
      "auditing.enabled" -> false,
      "metrics.enabled" -> false,
      "auth.token.internal" -> TestData.BasicAuthTokenValue,
      "internal.clientIds" -> List(TestData.InternalClientId.id),
      "microservice.services.public-notification.host" -> IntegrationTestData.TestHost,
      "microservice.services.public-notification.port" -> IntegrationTestData.TestPort,
      "microservice.services.public-notification.context" -> IntegrationTestData.ExternalPushUrlContext,
      "microservice.services.notification-queue.host" -> IntegrationTestData.TestHost,
      "microservice.services.notification-queue.port" -> IntegrationTestData.TestPort,
      "microservice.services.notification-queue.context" -> IntegrationTestData.PullQueueContext,
      "microservice.services.api-subscription-fields.host" -> IntegrationTestData.TestHost,
      "microservice.services.api-subscription-fields.port" -> IntegrationTestData.TestPort,
      "microservice.services.api-subscription-fields.context" -> IntegrationTestData.ApiSubsFieldsUrlContext,
      "microservice.services.customs-notification-metrics.host" -> IntegrationTestData.TestHost,
      "microservice.services.customs-notification-metrics.port" -> IntegrationTestData.TestPort,
      "microservice.services.customs-notification-metrics.context" -> IntegrationTestData.MetricsUrlContext,
      "retry.metric-name" -> "some-metric-counter-name",
      "notification-ttl" -> "14 days",
      "retry.scheduler.enabled" -> false,
      "retry.failed-and-blocked.delay" -> "150 seconds",
      "retry.failed-but-not-blocked.delay" -> "1 minute",
      "retry.failed-but-not-blocked.available-after" -> "10 minutes",
      "non.blocking.retry.after.minutes" -> 1,
      "hotfix.translates" -> Map(TestData.OldClientSubscriptionId.toString -> TestData.NewClientSubscriptionId.toString),
      "mongodb.uri" -> "mongodb://localhost:27017/customs-notification"
    ))
    .build()
}

class MockDateTimeService extends DateTimeService {
  var time: ZonedDateTime = TestData.TimeNow

  override def now(): ZonedDateTime = time

  def travelForwardsInTime(duration: FiniteDuration): Unit = {
    time = time.plus(duration.toJava)
  }

  def reset(): Unit = {
    time = TestData.TimeNow
  }
}

object MockNotificationIdService extends NotificationIdService {
  override def newId(): NotificationId = TestData.NotificationId
}

class MockObjectIdService extends ObjectIdService {
  var idToGive: ObjectId = TestData.ObjectId

  override def newId(): ObjectId = idToGive

  def nextTimeGive(id: ObjectId): Unit = {
    idToGive = id
  }

  def reset(): Unit = {
    idToGive = TestData.ObjectId
  }
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
