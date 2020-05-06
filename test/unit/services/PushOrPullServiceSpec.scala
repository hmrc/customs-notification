/*
 * Copyright 2020 HM Revenue & Customs
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

import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import play.api.test.Helpers
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, NotificationQueueConnector}
import uk.gov.hmrc.customs.notification.domain.{ApiSubscriptionFields => _, _}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import util.UnitSpec
import util.TestData._

import scala.concurrent.Future

class PushOrPullServiceSpec extends UnitSpec with MockitoSugar {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  trait SetUp {
    private implicit val ec = Helpers.stubControllerComponents().executionContext
    private[PushOrPullServiceSpec] val mockApiSubscriptionFieldsConnector = mock[ApiSubscriptionFieldsConnector]
    private[PushOrPullServiceSpec] val mockOutboundSwitchService = mock[OutboundSwitchService]
    private[PushOrPullServiceSpec] val mockNotificationQueueConnector = mock[NotificationQueueConnector]
    private[PushOrPullServiceSpec] val mockNotificationLogger = mock[NotificationLogger]

    private[PushOrPullServiceSpec] val service = new PushOrPullService(
      mockApiSubscriptionFieldsConnector,
      mockOutboundSwitchService,
      mockNotificationQueueConnector,
      mockNotificationLogger)

    private[PushOrPullServiceSpec] val mockResultError = mock[ResultError]
    private[PushOrPullServiceSpec] val eventualHttpResponse = Future.successful(mock[HttpResponse])
    private[PushOrPullServiceSpec] val eventualRightHttpResponse = Future.successful(Right(mock[HttpResponse]))
    private[PushOrPullServiceSpec] val eventualLeftResultError = Future.successful(Left(mockResultError))
    private[PushOrPullServiceSpec] val eventualEmulatedServiceFailure = Future.failed(emulatedServiceFailure)

    private[PushOrPullServiceSpec] val eventuallyNone = Future.successful(Future.successful(None))
    private[PushOrPullServiceSpec] val eventuallySomePushClientCallbackData = Future.successful(Some(ApiSubscriptionFieldsOneForPush))
    private[PushOrPullServiceSpec] val eventuallySomePullClientCallbackData = Future.successful(Some(ApiSubscriptionFieldsOneForPull))
    private[PushOrPullServiceSpec] val clientNotification = ClientNotification(NotificationWorkItem1.id, NotificationWorkItem1.notification, None, None, BSONObjectID.parse(NotUsedBsonId).get)
    private[PushOrPullServiceSpec] val pnr = PushNotificationRequest(
      NotificationWorkItem1.id.id.toString,
      PushNotificationRequestBody(
        ApiSubscriptionFieldsOneForPush.fields.callbackUrl,
        ApiSubscriptionFieldsOneForPush.fields.securityToken,
        NotificationWorkItem1.notification.conversationId.id.toString,
        NotificationWorkItem1.notification.headers,
        NotificationWorkItem1.notification.payload
      ))
  }

  "PushOrPullService" should {
    "PUSH when callback details are found and callbackUrl is present" in new SetUp {
      when(mockApiSubscriptionFieldsConnector.getClientData(NotificationWorkItem1.id.toString)).thenReturn(eventuallySomePushClientCallbackData)
      when(mockOutboundSwitchService.send(clientId1, pnr)(NotificationWorkItem1, hc)).thenReturn(eventualRightHttpResponse)

      val actual: Either[PushOrPullError, ConnectorSource] = await(service.send(NotificationWorkItem1))

      actual shouldBe Right(Push)
      verifyNoInteractions(mockNotificationQueueConnector)
    }
    "PULL when callback details are empty and callbackUrl is present" in new SetUp {
      when(mockApiSubscriptionFieldsConnector.getClientData(NotificationWorkItem1.id.toString)).thenReturn(eventuallySomePullClientCallbackData)
      when(mockNotificationQueueConnector.enqueue(clientNotification)).thenReturn(eventualHttpResponse)

      val actual: Either[PushOrPullError, ConnectorSource] = await(service.send(NotificationWorkItem1))

      actual shouldBe Right(Pull)
      verifyNoInteractions(mockOutboundSwitchService)
    }
    "return Left with source of ApiSubscriptionFields when callback details are not found" in new SetUp {
      when(mockApiSubscriptionFieldsConnector.getClientData(NotificationWorkItem1.id.toString)).thenReturn(eventuallyNone)

      val Left(pushOrPullError) = await(service.send(NotificationWorkItem1))

      pushOrPullError.source shouldBe GetApiSubscriptionFields
      pushOrPullError.resultError.cause.getMessage shouldBe "Error getting client subscription fields data"
      verifyNoInteractions(mockOutboundSwitchService)
      verifyNoInteractions(mockNotificationQueueConnector)
    }
    "return Left with source of ApiSubscriptionFields when ApiSubscriptionFieldsConnector throws an exception" in new SetUp {
      when(mockApiSubscriptionFieldsConnector.getClientData(NotificationWorkItem1.id.toString)).thenReturn(eventualEmulatedServiceFailure)

      val Left(pushOrPullError) = await(service.send(NotificationWorkItem1))

      pushOrPullError.source shouldBe GetApiSubscriptionFields
      pushOrPullError.resultError.cause.getMessage shouldBe "Emulated service failure."
      verifyNoInteractions(mockOutboundSwitchService)
      verifyNoInteractions(mockNotificationQueueConnector)
    }
    "when some callback URL is returned, return Left with source of Push when OutboundSwitchService throws an exception" in new SetUp {
      when(mockApiSubscriptionFieldsConnector.getClientData(NotificationWorkItem1.id.toString)).thenReturn(eventuallySomePushClientCallbackData)
      when(mockOutboundSwitchService.send(clientId1, pnr)(NotificationWorkItem1, hc)).thenReturn(eventualEmulatedServiceFailure)

      val Left(pushOrPullError) = await(service.send(NotificationWorkItem1))

      pushOrPullError.source shouldBe Push
      pushOrPullError.resultError.cause.getMessage shouldBe "Emulated service failure."
      verifyNoInteractions(mockNotificationQueueConnector)
    }
    "when some callback URL is returned, return Left with source of Push when OutboundSwitchService returns Left" in new SetUp {
      when(mockApiSubscriptionFieldsConnector.getClientData(NotificationWorkItem1.id.toString)).thenReturn(eventuallySomePushClientCallbackData)
      when(mockOutboundSwitchService.send(clientId1, pnr)(NotificationWorkItem1, hc)).thenReturn(eventualLeftResultError)

      val Left(pushOrPullError) = await(service.send(NotificationWorkItem1))

      pushOrPullError.source shouldBe Push
      pushOrPullError.resultError shouldBe mockResultError
      verifyNoInteractions(mockNotificationQueueConnector)
    }
    "when callback URL is empty, return Left with source of Pull when NotificationQueueConnector throws EmulatedService exception" in new SetUp {
      when(mockApiSubscriptionFieldsConnector.getClientData(NotificationWorkItem1.id.toString)).thenReturn(eventuallySomePullClientCallbackData)
      when(mockNotificationQueueConnector.enqueue(clientNotification)).thenReturn(eventualEmulatedServiceFailure)

      val Left(pushOrPullError) = await(service.send(NotificationWorkItem1))

      pushOrPullError.source shouldBe Pull
      pushOrPullError.resultError.cause.getMessage shouldBe "Emulated service failure."
      verifyNoInteractions(mockOutboundSwitchService)
    }
  }

}
