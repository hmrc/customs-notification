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
import org.mockito.ArgumentMatchers.{eq => meq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.connectors.CustomsNotificationMetricsConnector
import uk.gov.hmrc.customs.notification.domain.{ClientId, CustomsNotificationsMetricsRequest}
import uk.gov.hmrc.customs.notification.services.{DateTimeService, OutboundSwitchService, PushClientNotificationService}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import unit.logging.StubNotificationLogger
import unit.services.ClientWorkerTestData._
import util.TestData.emulatedServiceFailure

import scala.concurrent.Future

class PushClientNotificationServiceSpec extends UnitSpec with MockitoSugar with Eventually with BeforeAndAfterEach {

  private val mockOutboundSwitchService = mock[OutboundSwitchService]
  private val notificationLogger = new StubNotificationLogger(mock[CdsLogger])
  private val mockCustomsNotificationsMetricsConnector = mock[CustomsNotificationMetricsConnector]
  private val mockDateTimeService = mock[DateTimeService]
  private implicit val hc = HeaderCarrier()

  private val pushService = new PushClientNotificationService(mockOutboundSwitchService, notificationLogger, mockCustomsNotificationsMetricsConnector, mockDateTimeService)

  override protected def beforeEach(): Unit = {
    reset(mockOutboundSwitchService, mockCustomsNotificationsMetricsConnector, mockDateTimeService)
  }

  "PushClientNotificationService" should {
    "return true and call metrics service when push is successful but no metrics start time exists" in {
      when(mockOutboundSwitchService.send(eqClientId(ClientIdOne), meq(pnrOne))).thenReturn(Future.successful(()))

      val result = await(pushService.send(ApiSubscriptionFieldsOne, ClientNotificationOneWithMetricsTime))

      verifyMetricsConnector()

      result shouldBe true
      eventually(verify(mockOutboundSwitchService).send(eqClientId(ClientIdOne), meq(pnrOne)))
    }

    "return true and do not call mnetrics service when push is successful but no metrics start time exists" in {
      when(mockOutboundSwitchService.send(eqClientId(ClientIdOne), meq(pnrOne))).thenReturn(Future.successful(()))

      val result = await(pushService.send(ApiSubscriptionFieldsOne, ClientNotificationOne))

      verifyZeroInteractions(mockCustomsNotificationsMetricsConnector)

      result shouldBe true
      eventually(verify(mockOutboundSwitchService).send(eqClientId(ClientIdOne), meq(pnrOne)))
    }

    "return false when push fails" in {
      when(mockOutboundSwitchService.send(eqClientId(ClientIdOne), meq(pnrOne))).thenReturn(Future.failed(emulatedServiceFailure))

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
