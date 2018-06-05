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
import uk.gov.hmrc.customs.notification.connectors.{NotificationQueueConnector, PublicNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain.DeclarantCallbackData
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.{CustomsNotificationService, PublicNotificationRequestService}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.test.UnitSpec
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
  private val mockCallbackData = mock[DeclarantCallbackData]
  private val mockRequestMetaData = mock[RequestMetaData]

  private val customsNotificationService = new CustomsNotificationService(
    mockNotificationLogger,
    mockPublicNotificationRequestService,
    mockPublicNotificationServiceConnector,
    mockNotificationQueueConnector
  )


  override protected def beforeEach() {
    reset(mockPublicNotificationRequestService, mockPublicNotificationServiceConnector, mockNotificationQueueConnector)
  }

  "CustomsNotificationService" should {

    "first try to Push the notification" in {
      when(mockPublicNotificationRequestService.createRequest(ValidXML, mockCallbackData, mockRequestMetaData)).thenReturn(Future.successful(publicNotificationRequest))
      when(mockPublicNotificationServiceConnector.send(publicNotificationRequest)).thenReturn(Future.successful(()))

      await(customsNotificationService.handleNotification(ValidXML, mockCallbackData, mockRequestMetaData))

      eventually(verify(mockPublicNotificationServiceConnector).send(meq(publicNotificationRequest)))
      verifyZeroInteractions(mockNotificationQueueConnector)
    }

    "enqueue notification to be pulled when push fails" in {
      when(mockPublicNotificationRequestService.createRequest(ValidXML, mockCallbackData, mockRequestMetaData)).thenReturn(Future.successful(publicNotificationRequest))
      when(mockPublicNotificationServiceConnector.send(publicNotificationRequest)).thenReturn(Future.failed(emulatedServiceFailure))
      when(mockNotificationQueueConnector.enqueue(publicNotificationRequest)).thenReturn(Future.successful(mock[HttpResponse]))

      await(customsNotificationService.handleNotification(ValidXML, mockCallbackData, mockRequestMetaData))

      eventually(verify(mockPublicNotificationServiceConnector).send(meq(publicNotificationRequest)))
      eventually(verify(mockNotificationQueueConnector).enqueue(meq(publicNotificationRequest)))
    }
  }

}
