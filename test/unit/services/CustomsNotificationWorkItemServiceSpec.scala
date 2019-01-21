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

import org.mockito.ArgumentMatchers.refEq
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time.{Millis, Span}
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.customs.notification.services._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import util.TestData._

import scala.concurrent.Future
import scala.xml.Elem

class CustomsNotificationWorkItemServiceSpec extends UnitSpec with MockitoSugar with BeforeAndAfterEach with Eventually {

  override implicit def patienceConfig: PatienceConfig =
    super.patienceConfig.copy(timeout = Span(defaultTimeout.toMillis, Millis))

  private val badgeIdValue = "test-badge-id"
  private implicit val hc: HeaderCarrier = HeaderCarrier(extraHeaders = Seq(
    X_CONVERSATION_ID_HEADER_NAME -> validConversationId,
    X_BADGE_ID_HEADER_NAME -> badgeIdValue,
    X_EORI_ID_HEADER_NAME -> eoriNumber,
    X_CDS_CLIENT_ID_HEADER_NAME -> validFieldsId,
    X_CORRELATION_ID_HEADER_NAME -> correlationId))


  val ValidXML: Elem = <foo1></foo1>
  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockNotificationWorkItemRepo = mock[NotificationWorkItemRepo]
  private val mockPushService = mock[PushClientNotificationWorkItemService]

   private val service = new CustomsNotificationWorkItemService(
    mockNotificationLogger,
    mockNotificationWorkItemRepo,
    mockPushService
  )

  override protected def beforeEach() {
    reset(mockNotificationWorkItemRepo)
    when(mockPushService.send(ApiSubscriptionFieldsResponseOne, NotificationWorkItemWithMetricsTime1)).thenReturn(Future.successful(true))
  }

  //TODO test more paths through service
  "CustomsNotificationWorkItemService" should {

    "first try to handle the notification" in {
      when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1))).thenReturn(Future.successful(WorkItem1))
      val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsResponseOne)
      await(result) shouldBe true
      eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1)))
      eventually(verify(mockPushService).send(ApiSubscriptionFieldsResponseOne, NotificationWorkItemWithMetricsTime1))
    }

    "fail when it was unable to save notification to repository" in {
      when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1))).thenReturn(Future.failed(emulatedServiceFailure))

      val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsResponseOne)

      await(result) shouldBe false
      eventually(verify(mockNotificationWorkItemRepo).saveWithLock(refEq(NotificationWorkItemWithMetricsTime1)))
      eventually(verifyZeroInteractions(mockPushService))
    }

    "return true when repo saves but push fails" in {
      when(mockNotificationWorkItemRepo.saveWithLock(refEq(NotificationWorkItemWithMetricsTime1))).thenReturn(Future.successful(WorkItem1))
      when(mockPushService.send(ApiSubscriptionFieldsResponseOne, NotificationWorkItemWithMetricsTime1)).thenReturn(Future.successful(false))

      val result = service.handleNotification(ValidXML, requestMetaData, ApiSubscriptionFieldsResponseOne)

      await(result) shouldBe true
    }
  }
}
