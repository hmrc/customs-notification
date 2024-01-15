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

package uk.gov.hmrc.customs.notification

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.apache.pekko.Done
import org.scalatest.{BeforeAndAfterAll, TestSuite}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.*
import play.api.cache.AsyncCacheApi
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.cache.NamedCacheImpl
import uk.gov.hmrc.customs.notification.IntegrationSpecBase.uselessCache
import uk.gov.hmrc.customs.notification.services.{DateTimeService, HeaderCarrierService, ObjectIdService, UuidService}
import uk.gov.hmrc.customs.notification.util.IntegrationTestHelpers.PathFor
import uk.gov.hmrc.customs.notification.util.TestData.*
import uk.gov.hmrc.customs.notification.util.*

import scala.concurrent.Future
import scala.concurrent.duration.{Duration, DurationDouble, DurationInt}
import scala.language.postfixOps
import scala.reflect.ClassTag

trait IntegrationSpecBase extends TestSuite
  with WireMockHelpers
  with BeforeAndAfterAll
  with GuiceOneServerPerSuite {
  protected lazy val wireMockServer = new WireMockServer(wireMockConfig().port(testPort))

  protected val mockDateTimeService = new MockDateTimeService
  protected val mockObjectIdService = new MockObjectIdService
  protected val mockUuidService = new MockUuidService
  protected val mockHeaderCarrierService = new MockHeaderCarrierService

  override def beforeAll(): Unit = {
    super.beforeAll()
    if (!wireMockServer.isRunning) {
      wireMockServer.start()
      WireMock.configureFor(testHost, testPort)
    }
  }

  override def afterAll(): Unit = {
    try {
      super.afterAll()
    } finally {
      wireMockServer.stop()
    }
  }

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .overrides(
        bind[DateTimeService].toInstance(mockDateTimeService),
        bind[UuidService].toInstance(mockUuidService),
        bind[ObjectIdService].toInstance(mockObjectIdService),
        bind[HeaderCarrierService].toInstance(mockHeaderCarrierService),
        bind[AsyncCacheApi].qualifiedWith(new NamedCacheImpl("client-data")).toInstance(uselessCache)
      )
      .configure(Map(
        "metrics.enabled" -> false,
        "auth-token" -> BasicAuthTokenValue,
        "internal-client-ids" -> List(InternalClientId.id),
        "microservice.services.public-notification.host" -> testHost,
        "microservice.services.public-notification.port" -> testPort,
        "microservice.services.public-notification.context" -> PathFor.ExternalPush,
        "microservice.services.notification-queue.host" -> testHost,
        "microservice.services.notification-queue.port" -> testPort,
        "microservice.services.notification-queue.context" -> PathFor.PullQueue,
        "microservice.services.api-subscription-fields.host" -> testHost,
        "microservice.services.api-subscription-fields.port" -> testPort,
        "microservice.services.api-subscription-fields.context" -> PathFor.ClientData,
        "microservice.services.customs-notification-metrics.host" -> testHost,
        "microservice.services.customs-notification-metrics.port" -> testPort,
        "microservice.services.customs-notification-metrics.context" -> PathFor.Metrics,
        "notification-ttl" -> s"${14 days}",
        "retry.metric-name" -> "some-metric-counter-name",
        "retry.scheduler.enabled" -> false,
        "retry.delay.failed-and-blocked" ->s"${30 seconds}",
        "retry.delay.failed-but-not-blocked" -> s"${30 seconds}",
        "retry.available-after.failed-but-not-blocked" -> s"${10 minutes}",
        "retry.available-after.failed-and-blocked" -> s"${2.5 minutes}",
        "retry.max-transaction-retries" -> 5,
        "retry.buffer-size" -> "100",
        "hotfix.translates" -> Map(UntranslatedCsid.toString -> TranslatedCsid.toString),
        "mongodb.uri" -> "mongodb://localhost:27017/customs-notification",
        "blocked-csid-migration-enabled" -> false,
        "client-data-cache-ttl" -> s"${5 minutes}"
      ))
      .build()
}

private object IntegrationSpecBase {
  val uselessCache: AsyncCacheApi = new AsyncCacheApi {
    override def set(key: String, value: Any, expiration: Duration): Future[Done] = Future.successful(Done)

    override def remove(key: String): Future[Done] =  Future.successful(Done)

    override def getOrElseUpdate[A: ClassTag](key: String, expiration: Duration)(orElse: => Future[A]): Future[A] = orElse

    override def get[T: ClassTag](key: String): Future[Option[T]] = Future.successful(None)

    override def removeAll(): Future[Done] = Future.successful(Done)
  }
}
