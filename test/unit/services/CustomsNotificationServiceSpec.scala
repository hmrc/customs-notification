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

package unit.services

import org.mockito.ArgumentMatchers.{eq => meq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time.{Millis, Span}
import play.api.mvc.Headers
import uk.gov.hmrc.customs.notification.connectors.{NotificationQueueConnector, PublicNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.{CustomsNotificationService, DeclarantCallbackDataNotFound, NotificationSent, PublicNotificationRequestService}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import util.RequestHeaders
import util.TestData._

import scala.concurrent.Future

class CustomsNotificationServiceSpec extends UnitSpec with MockitoSugar with BeforeAndAfterEach with Eventually {

  override implicit def patienceConfig: PatienceConfig =
    super.patienceConfig.copy(timeout = Span(defaultTimeout.toMillis, Millis))

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockPublicNotificationRequestService = mock[PublicNotificationRequestService]
  private val mockPublicNotificationServiceConnector = mock[PublicNotificationServiceConnector]
  private val mockNotificationQueueConnector = mock[NotificationQueueConnector]

  private val customsNotificationService = new CustomsNotificationService(
    mockNotificationLogger,
    mockPublicNotificationRequestService,
    mockPublicNotificationServiceConnector,
    mockNotificationQueueConnector
  )

  private val ValidInboundHeaders = Seq(
    RequestHeaders.X_CONVERSATION_ID_HEADER,
    RequestHeaders.X_CDS_CLIENT_ID_HEADER
  )
  val ValidHeaders = Headers(ValidInboundHeaders: _*)

  override protected def beforeEach() {
    reset(mockPublicNotificationRequestService, mockPublicNotificationServiceConnector, mockNotificationQueueConnector)
  }

  "CustomsNotificationService" should {
    "return NotificationSent for valid input" in {
      when(mockPublicNotificationRequestService.createRequest(ValidXML, ValidHeaders)).thenReturn(Future.successful(Some(publicNotificationRequest)))
      when(mockPublicNotificationServiceConnector.send(publicNotificationRequest)).thenReturn(Future.successful(()))

      val request = await(customsNotificationService.sendNotification(ValidXML, ValidHeaders))

      request shouldBe NotificationSent
      verifyZeroInteractions(mockNotificationQueueConnector)
    }

    "enqueue notification if push fails" in {
      when(mockPublicNotificationRequestService.createRequest(ValidXML, ValidHeaders)).thenReturn(Future.successful(Some(publicNotificationRequest)))
      when(mockPublicNotificationServiceConnector.send(publicNotificationRequest)).thenReturn(Future.failed(emulatedServiceFailure))

      await(customsNotificationService.sendNotification(ValidXML, ValidHeaders))

      eventually(verify(mockPublicNotificationServiceConnector).send(meq(publicNotificationRequest)))
      eventually(verify(mockNotificationQueueConnector).enqueue(meq(publicNotificationRequest)))
    }


    "return DeclarantCallbackDataNotFound when client Id not found" in {
      when(mockPublicNotificationRequestService.createRequest(ValidXML, ValidHeaders)).thenReturn(Future.successful(None))

      val request = await(customsNotificationService.sendNotification(ValidXML, ValidHeaders))

      request shouldBe DeclarantCallbackDataNotFound
      verifyZeroInteractions(mockPublicNotificationServiceConnector)
      verifyZeroInteractions(mockNotificationQueueConnector)
    }

    "propagate exception in PublicNotificationRequestService" in {
      when(mockPublicNotificationRequestService.createRequest(ValidXML, ValidHeaders)).thenReturn(Future.failed(emulatedServiceFailure))

      val caught = intercept[Throwable](await(customsNotificationService.sendNotification(ValidXML, ValidHeaders)))

      caught shouldBe emulatedServiceFailure
      verifyZeroInteractions(mockPublicNotificationServiceConnector)
      verifyZeroInteractions(mockNotificationQueueConnector)
    }

    "PublicNotificationServiceConnector runs in an independent Future so should not propagate exception" in {
      when(mockPublicNotificationRequestService.createRequest(ValidXML, ValidHeaders)).thenReturn(Future.successful(Some(publicNotificationRequest)))
      when(mockPublicNotificationServiceConnector.send(publicNotificationRequest)).thenReturn(Future.failed(emulatedServiceFailure))

      val result = await(customsNotificationService.sendNotification(ValidXML, ValidHeaders))

      result shouldBe NotificationSent
    }

  }

}
