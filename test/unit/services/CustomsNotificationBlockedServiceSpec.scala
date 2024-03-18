/*
 * Copyright 2024 HM Revenue & Customs
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
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatestplus.mockito.MockitoSugar
import play.api.test.Helpers
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.customs.notification.services.CustomsNotificationBlockedService
import util.UnitSpec
import unit.logging.StubCdsLogger
import util.TestData._

import scala.concurrent.Future

class CustomsNotificationBlockedServiceSpec extends UnitSpec
  with MockitoSugar
  with Eventually
  with BeforeAndAfterEach {

  private implicit val ec = Helpers.stubControllerComponents().executionContext
  private val stubCdsLogger = StubCdsLogger()
  private val mockRepo = mock[NotificationWorkItemRepo]
  private val service = new CustomsNotificationBlockedService(stubCdsLogger, mockRepo)

  override protected def beforeEach(): Unit = {
    reset(mockRepo)
  }

  "CustomsNotificationBlockedService" should {
    "return count when repo called" in {
      when(mockRepo.blockedCount(clientId1)).thenReturn(Future.successful(2))

      val result = await(service.blockedCount(clientId1))
      result shouldBe 2

      verify(mockRepo).blockedCount(clientId1)
    }

    "return true when notifications are unblocked" in {
      when(mockRepo.deleteBlocked(clientId1)).thenReturn(Future.successful(2))

      val result = await(service.deleteBlocked(clientId1))
      result shouldBe true

      verify(mockRepo).deleteBlocked(clientId1)
    }

    "return false when no notifications are unblocked" in {
      when(mockRepo.deleteBlocked(clientId1)).thenReturn(Future.successful(0))

      val result = await(service.deleteBlocked(clientId1))
      result shouldBe false

      verify(mockRepo).deleteBlocked(clientId1)
    }
  }
}
