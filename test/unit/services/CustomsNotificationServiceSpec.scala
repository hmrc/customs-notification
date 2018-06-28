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

import java.util.UUID

import org.joda.time.DateTime
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, refEq, eq => meq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time.{Millis, Span}
import play.api.http.HeaderNames
import uk.gov.hmrc.customs.notification.connectors.{GoogleAnalyticsSenderConnector, NotificationQueueConnector, PublicNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, ClientSubscriptionId, DeclarantCallbackData, Notification}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.ClientNotificationRepo
import uk.gov.hmrc.customs.notification.services.{CustomsNotificationService, NotificationDispatcher, PublicNotificationRequestService}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.test.UnitSpec
import util.TestData
import util.TestData._

import scala.concurrent.Future

class CustomsNotificationServiceSpec extends UnitSpec with MockitoSugar with BeforeAndAfterEach with Eventually {

  override implicit def patienceConfig: PatienceConfig =
    super.patienceConfig.copy(timeout = Span(defaultTimeout.toMillis, Millis))

  private implicit val hc: HeaderCarrier = HeaderCarrier(extraHeaders = Seq(HeaderNames.CONTENT_TYPE -> "application/xml"))

  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockPublicNotificationRequestService = mock[PublicNotificationRequestService]
  private val mockPublicNotificationServiceConnector = mock[PublicNotificationServiceConnector]
  private val mockNotificationQueueConnector = mock[NotificationQueueConnector]
  private val validCallbackData = DeclarantCallbackData("callbackUrl", "securityToken")
  private val callbackDataWithEmptyCallbackUrl = DeclarantCallbackData("", "securityToken")
  private val requestMetaData = RequestMetaData(TestData.validFieldsId, validConversationIdUUID, None)
  private val mockGAConnector = mock[GoogleAnalyticsSenderConnector]
  private val mockClientNotificationRepo = mock[ClientNotificationRepo]
  private val mockNotificationDispatcher = mock[NotificationDispatcher]
  private val contentType = hc.headers.toMap[String, String].get(HeaderNames.CONTENT_TYPE).get
  private val notification = Notification(hc.headers.seq, publicNotificationRequest.body.xmlPayload, contentType)
  private val clientSubscriptionId = ClientSubscriptionId(UUID.fromString(publicNotificationRequest.clientSubscriptionId))
  private val clientNotification = ClientNotification(clientSubscriptionId, notification, DateTime.now())

  private val customsNotificationService = new CustomsNotificationService(
    mockNotificationLogger,
    mockPublicNotificationRequestService,
    mockPublicNotificationServiceConnector,
    mockNotificationQueueConnector,
    mockGAConnector,
    mockClientNotificationRepo,
    mockNotificationDispatcher
  )


  override protected def beforeEach() {
    reset(mockPublicNotificationRequestService, mockPublicNotificationServiceConnector, mockNotificationQueueConnector, mockGAConnector)
    when(mockPublicNotificationRequestService.createRequest(ValidXML, validCallbackData, requestMetaData)).thenReturn(Future.successful(publicNotificationRequest))
    when(mockPublicNotificationServiceConnector.send(publicNotificationRequest)).thenReturn(Future.successful(()))
    when(mockGAConnector.send(any(), any())(meq(hc))).thenReturn(Future.successful(()))
    when(mockNotificationQueueConnector.enqueue(publicNotificationRequest)).thenReturn(Future.successful(mock[HttpResponse]))
    when(mockClientNotificationRepo.save(refEq(clientNotification, "timestamp"))).thenReturn(Future.successful(true))
    when(mockNotificationDispatcher.process(meq(Set(clientSubscriptionId)))).thenReturn(Future.successful(()))
  }

  "CustomsNotificationService" should {

    "first try to Push the notification" in {
      await(customsNotificationService.handleNotification(ValidXML, validCallbackData, requestMetaData))


      eventually(verify(mockClientNotificationRepo).save(refEq(clientNotification, "timestamp")))
      eventually(verify(mockNotificationDispatcher).process(meq(Set(clientSubscriptionId))))
    }

    "enqueue notification to be pulled when subscription fields callbackUrl is empty" in {
      when(mockPublicNotificationRequestService.createRequest(ValidXML, callbackDataWithEmptyCallbackUrl, requestMetaData)).thenReturn(Future.successful(publicNotificationRequest))
      when(mockGAConnector.send(any(), any())(meq(hc))).thenReturn(Future.successful(()))

      await(customsNotificationService.handleNotification(ValidXML, callbackDataWithEmptyCallbackUrl, requestMetaData))

      eventually(verify(mockNotificationQueueConnector).enqueue(meq(publicNotificationRequest)))
      val eventNameCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
      val msgCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
      eventually(verify(mockGAConnector, times(2)).send(eventNameCaptor.capture(), msgCaptor.capture())(any()))
      val capturedEventNames = eventNameCaptor.getAllValues
      msgCaptor.getAllValues.get(capturedEventNames.indexOf("notificationRequestReceived")) shouldBe s"[ConversationId=${requestMetaData.conversationId}] A notification received for delivery"
      msgCaptor.getAllValues.get(capturedEventNames.indexOf("notificationLeftToBePulled")) shouldBe s"[ConversationId=${requestMetaData.conversationId}] A notification has been left to be pulled"
    }
  }
}
