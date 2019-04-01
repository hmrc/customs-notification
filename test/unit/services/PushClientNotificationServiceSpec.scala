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

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{eq => meq, _}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatestplus.mockito.MockitoSugar
import play.api.test.Helpers.BAD_REQUEST
import uk.gov.hmrc.customs.notification.connectors.CustomsNotificationMetricsConnector
import uk.gov.hmrc.customs.notification.domain.{ClientId, CustomsNotificationsMetricsRequest, HasId, HttpResultError}
import uk.gov.hmrc.customs.notification.services.{DateTimeService, OutboundSwitchService, PushClientNotificationService}
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.play.test.UnitSpec
import unit.logging.StubCdsLogger
import unit.services.ClientWorkerTestData._
import util.TestData
import util.TestData.emulatedServiceFailure

import scala.concurrent.Future

class PushClientNotificationServiceSpec extends UnitSpec with MockitoSugar with Eventually with BeforeAndAfterEach {

  private val mockOutboundSwitchService = mock[OutboundSwitchService]
  private val stubCdsLogger = StubCdsLogger()
  private val mockCustomsNotificationsMetricsConnector = mock[CustomsNotificationMetricsConnector]
  private val mockDateTimeService = mock[DateTimeService]
  private val mockHttpResponse = mock[HttpResponse]
  private val httpResultError = HttpResultError(BAD_REQUEST, emulatedServiceFailure)
  private implicit val rm = TestData.requestMetaData

  private val pushService = new PushClientNotificationService(mockOutboundSwitchService, stubCdsLogger, mockCustomsNotificationsMetricsConnector, mockDateTimeService)

  override protected def beforeEach(): Unit = {
    reset(mockOutboundSwitchService, mockCustomsNotificationsMetricsConnector, mockDateTimeService)
  }

  "PushClientNotificationService" should {
    "return true and call metrics service when push is successful but no metrics start time exists" in {
      when(mockOutboundSwitchService.send(eqClientId(ClientIdOne), meq(pnrOne))(any[HasId])).thenReturn(Future.successful(Right(mockHttpResponse)))

      val result = await(pushService.send(ApiSubscriptionFieldsOne, ClientNotificationOneWithMetricsTime))

      verifyMetricsConnector()

      result shouldBe true
      eventually(verify(mockOutboundSwitchService).send(eqClientId(ClientIdOne), meq(pnrOne))(any[HasId]))
    }

    "return true and do not call metrics service when push is successful but no metrics start time exists" in {
      when(mockOutboundSwitchService.send(eqClientId(ClientIdOne), meq(pnrOne))(any[HasId])).thenReturn(Future.successful(Right(mockHttpResponse)))

      val result = await(pushService.send(ApiSubscriptionFieldsOne, ClientNotificationOne))

      verifyZeroInteractions(mockCustomsNotificationsMetricsConnector)

      result shouldBe true
      eventually(verify(mockOutboundSwitchService).send(eqClientId(ClientIdOne), meq(pnrOne))(any[HasId]))
    }

    "return false when push fails" in {
      when(mockOutboundSwitchService.send(eqClientId(ClientIdOne), meq(pnrOne))(any[HasId])).thenReturn(Future.successful(Left(httpResultError)))

      verifyZeroInteractions(mockCustomsNotificationsMetricsConnector)
      val result = await(pushService.send(ApiSubscriptionFieldsOne, ClientNotificationOne))
      result shouldBe false
    }

  }

  private def eqClientId(clientId: ClientId) = meq[String](clientId.id).asInstanceOf[ClientId]

  def verifyMetricsConnector(): Unit = {
    val metricsRequestCaptor: ArgumentCaptor[CustomsNotificationsMetricsRequest] = ArgumentCaptor.forClass(classOf[CustomsNotificationsMetricsRequest])
    Eventually.eventually(verify(mockCustomsNotificationsMetricsConnector, times(1)).post(metricsRequestCaptor.capture()))
    val metricsRequest = metricsRequestCaptor.getValue
    metricsRequest.conversationId.toString shouldBe ConversationIdOne.id.toString
    ()
  }
}
