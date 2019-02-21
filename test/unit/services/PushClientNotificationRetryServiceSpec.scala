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
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq => meq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import play.api.test.Helpers.BAD_REQUEST
import uk.gov.hmrc.customs.notification.connectors.CustomsNotificationMetricsConnector
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.customs.notification.services.{DateTimeService, OutboundSwitchService, PushClientNotificationRetryService, OnlineRetryService}
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.play.test.UnitSpec
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class PushClientNotificationRetryServiceSpec extends UnitSpec with MockitoSugar with Eventually with BeforeAndAfterEach {

  private val mockLogger = mock[NotificationLogger]
  private val mockOutboundSwitchService = mock[OutboundSwitchService]
  private val notificationLogger = mock[NotificationLogger]
  private val mockCustomsNotificationsMetricsConnector = mock[CustomsNotificationMetricsConnector]
  private val mockDateTimeService = mock[DateTimeService]
  private val mockHttpResponse = mock[HttpResponse]
  private val httpResultError = HttpResultError(BAD_REQUEST, emulatedServiceFailure)
  private val mockConfigService = mock[ConfigService]
  private val mockPushNotificationConfig = mock[PushNotificationConfig]
  implicit private val implicitConversationId = conversationId
  private val retryService = new OnlineRetryService(mockConfigService, mockLogger, ActorSystem("PushClientNotificationRetryServiceSpec"))
  private val pushService = new PushClientNotificationRetryService(retryService, mockOutboundSwitchService,
    notificationLogger, mockCustomsNotificationsMetricsConnector, mockDateTimeService)
  private implicit val implicitRequestMetaData = requestMetaData

  override protected def beforeEach(): Unit = {
    reset(mockOutboundSwitchService, mockCustomsNotificationsMetricsConnector, mockDateTimeService,
      mockHttpResponse, mockConfigService, mockPushNotificationConfig)

    when(mockConfigService.pushNotificationConfig).thenReturn(mockPushNotificationConfig)
    when(mockPushNotificationConfig.retryDelay).thenReturn(500 milliseconds)
    when(mockPushNotificationConfig.retryDelayFactor).thenReturn(2)
    when(mockPushNotificationConfig.retryMaxAttempts).thenReturn(1)
  }

  "PushClientNotificationRetryService" should {
    "call metrics service when push is successful" in {
      when(mockOutboundSwitchService.send(eqClientId(clientId1), meq(PushNotificationRequest1))(any[HasId])).thenReturn(Future.successful(Right(mockHttpResponse)))

      val result = await(pushService.send(ApiSubscriptionFieldsOneForPush, NotificationWorkItemWithMetricsTime1))

      result shouldBe true
      verifyMetricsConnector()
      eventually(verify(mockOutboundSwitchService).send(eqClientId(clientId1), meq(PushNotificationRequest1))(any[HasId]))
    }

    "do not call metrics service when push is successful but no metrics start time exists" in {
      when(mockOutboundSwitchService.send(eqClientId(clientId1), meq(PushNotificationRequest1))(any[HasId])).thenReturn(Future.successful(Right(mockHttpResponse)))

      val result = await(pushService.send(ApiSubscriptionFieldsOneForPush, NotificationWorkItem1))

      result shouldBe true
      verifyZeroInteractions(mockCustomsNotificationsMetricsConnector)
      eventually(verify(mockOutboundSwitchService).send(eqClientId(clientId1), meq(PushNotificationRequest1))(any[HasId]))
    }

    "log error when push fails" in {
      when(mockOutboundSwitchService.send(eqClientId(clientId1), meq(PushNotificationRequest1))(any[HasId])).thenReturn(Future.successful(Left(httpResultError)))

      val result = await(pushService.send(ApiSubscriptionFieldsOneForPush, NotificationWorkItem1))

      result shouldBe false
      verifyZeroInteractions(mockCustomsNotificationsMetricsConnector)
      logVerifier("error", "failed to push notification due to: Emulated service failure.")
    }
  }

  private def eqClientId(clientId: ClientId) = meq[String](clientId.id).asInstanceOf[ClientId]

  private def verifyMetricsConnector(): Unit = {
    val metricsRequestCaptor: ArgumentCaptor[CustomsNotificationsMetricsRequest] = ArgumentCaptor.forClass(classOf[CustomsNotificationsMetricsRequest])
    Eventually.eventually(verify(mockCustomsNotificationsMetricsConnector, times(1)).post(metricsRequestCaptor.capture()))
    val metricsRequest = metricsRequestCaptor.getValue
    metricsRequest.conversationId.toString shouldBe conversationId.id.toString
    ()
  }

  private def logVerifier(logLevel: String, logText: String): Unit = {
    PassByNameVerifier(notificationLogger, logLevel)
      .withByNameParam(logText)
      .withParamMatcher(any[HasId])
      .verify()
  }
}
