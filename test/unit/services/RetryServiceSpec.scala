/*
 * Copyright 2019 HM Revenue & Customs
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

package unit.services

import akka.actor.ActorSystem
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.domain.{HttpResultError, PushNotificationConfig, ResultError}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.customs.notification.services.{OutboundSwitchService, RetryService}
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.play.test.UnitSpec
import unit.services.ClientWorkerTestData.{ClientIdOne, pnrOne}
import util.TestData

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class RetryServiceSpec extends UnitSpec with MockitoSugar  {

  trait SetUp {
    val mockLogger = mock[NotificationLogger]
    val exception = new IllegalStateException("BOOM!")
    val mockHttpResponse = mock[HttpResponse]
    val clientId = ClientIdOne
    val request = pnrOne
    val mockConnector = mock[OutboundSwitchService]
    implicit val rm = TestData.requestMetaData

    def futureCall: Future[Either[ResultError, HttpResponse]] = {
      mockConnector.send(clientId, request)
    }
    val system = akka.actor.ActorSystem("Test")
    val eventuallyOk: Future[Either[ResultError, HttpResponse]] = Future.successful(Right(mockHttpResponse))
    val httpErrorResult3XX = HttpResultError(MOVED_PERMANENTLY, exception)
    val httpErrorResult4XX = HttpResultError(BAD_REQUEST, exception)
    val httpErrorResult5XX = HttpResultError(INTERNAL_SERVER_ERROR, exception)
    val eventuallyFailedWith3XX = Future.successful(Left(httpErrorResult3XX))
    val eventuallyFailedWith4XX = Future.successful(Left(httpErrorResult4XX))
    val eventuallyFailedWith5XX = Future.successful(Left(httpErrorResult5XX))
    val mockConfigService = mock[ConfigService]
    val mockPushNotificationConfig = mock[PushNotificationConfig]
    val retryService = new RetryService(mockConfigService, mockLogger, ActorSystem("RetryServiceSpec"))

    when(mockConfigService.pushNotificationConfig).thenReturn(mockPushNotificationConfig)
    when(mockPushNotificationConfig.retryDelay).thenReturn(500 milliseconds)
    when(mockPushNotificationConfig.retryDelayFactor).thenReturn(2)

  }

  "Retry with a Future" should {
    "return Future of Right(HttpResponse) when first call succeeds" in new SetUp {
      when(mockPushNotificationConfig.retryMaxAttempts).thenReturn(3)
      when(mockConnector.send(clientId, request)).thenReturn(eventuallyOk)

      val Right(actual) = await(retryService.retry(futureCall))

      actual shouldBe mockHttpResponse
      verify(mockConnector, times(1)).send(clientId, request)
    }

    "return Future of Right(HttpResponse) when second call succeeds" in new SetUp {
      when(mockPushNotificationConfig.retryMaxAttempts).thenReturn(3)
      when(mockConnector.send(clientId, request)).thenReturn(eventuallyFailedWith5XX, eventuallyOk)

      val Right(actual) = await(retryService.retry(futureCall))

      actual shouldBe mockHttpResponse
      verify(mockConnector, times(2)).send(clientId, request)
    }

    "return Future of Right(HttpResponse) when third call succeeds" in new SetUp {
      when(mockPushNotificationConfig.retryMaxAttempts).thenReturn(3)
      when(mockConnector.send(clientId, request)).thenReturn(eventuallyFailedWith5XX, eventuallyFailedWith5XX, eventuallyOk)

      val Right(actual) = await(retryService.retry(futureCall))

      actual shouldBe mockHttpResponse
      verify(mockConnector, times(3)).send(clientId, request)
    }

    "return Future of Left(HttpErrorResult) when all calls fail with 5XX" in new SetUp {
      when(mockPushNotificationConfig.retryMaxAttempts).thenReturn(3)
      when(mockConnector.send(clientId, request)).thenReturn(eventuallyFailedWith5XX)

      val Left(actual) = await(retryService.retry(futureCall))

      actual shouldBe httpErrorResult5XX
      verify(mockConnector, times(3)).send(clientId, request)
    }

    "return Future of Left(HttpErrorResult) when calls fails with ONE max attempts with 5XX" in new SetUp {
      when(mockPushNotificationConfig.retryMaxAttempts).thenReturn(1)
      when(mockConnector.send(clientId, request)).thenReturn(eventuallyFailedWith5XX)

      val Left(actual) = await(retryService.retry(futureCall))

      actual shouldBe httpErrorResult5XX
      verify(mockConnector, times(1)).send(clientId, request)
    }

    "return Future of Left(HttpErrorResult) when calls fails with ZERO max attempts with 5XX (ie is lenient with ZERO max attempts)" in new SetUp {
      when(mockPushNotificationConfig.retryMaxAttempts).thenReturn(0)
      when(mockConnector.send(clientId, request)).thenReturn(eventuallyFailedWith5XX)

      val Left(actual) = await(retryService.retry(futureCall))

      actual shouldBe httpErrorResult5XX
      verify(mockConnector, times(1)).send(clientId, request)
    }

    "fail fast with Future of Left(HttpErrorResult) when call returns 3XX" in new SetUp {
      when(mockPushNotificationConfig.retryMaxAttempts).thenReturn(3)
      when(mockConnector.send(clientId, request)).thenReturn(eventuallyFailedWith3XX)

      val Left(actual) = await(retryService.retry(futureCall))

      actual shouldBe httpErrorResult3XX
      verify(mockConnector, times(1)).send(clientId, request)
    }

    "fail fast with Future of Left(HttpErrorResult) when call returns 4XX" in new SetUp {
      when(mockPushNotificationConfig.retryMaxAttempts).thenReturn(3)
      when(mockConnector.send(clientId, request)).thenReturn(eventuallyFailedWith4XX)

      val Left(actual) = await(retryService.retry(futureCall))

      actual shouldBe httpErrorResult4XX
      verify(mockConnector, times(1)).send(clientId, request)
    }

  }

}
