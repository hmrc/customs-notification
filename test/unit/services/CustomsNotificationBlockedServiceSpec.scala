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

import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo
import uk.gov.hmrc.customs.notification.services.CustomsNotificationBlockedService
import uk.gov.hmrc.play.test.UnitSpec
import unit.logging.StubNotificationLogger
import util.TestData._

import scala.concurrent.Future

class CustomsNotificationBlockedServiceSpec extends UnitSpec
  with MockitoSugar
  with Eventually
  with BeforeAndAfterEach {

  private val notificationLogger = new StubNotificationLogger(mock[CdsLogger])
  private val mockRepo = mock[NotificationWorkItemRepo]
  private val service = new CustomsNotificationBlockedService(notificationLogger, mockRepo)

  "CustomsNotificationBlockedService" should {
    "return count when repo called" in {
      when(mockRepo.blockedCount(clientId1)).thenReturn(Future.successful(2))

      val result = await(service.blockedCount(clientId1))
      result shouldBe 2

      eventually(verify(mockRepo).blockedCount(clientId1))
    }
  }
}