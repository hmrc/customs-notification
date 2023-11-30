/*
 * Copyright 2023 HM Revenue & Customs
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

package integration

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import integration.RetryServiceSpec.anotherNotification
import org.scalatest.Inspectors.forAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.test.Helpers._
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import uk.gov.hmrc.customs.notification.models.{Notification, ProcessingStatus}
import uk.gov.hmrc.customs.notification.repo.Repository
import uk.gov.hmrc.customs.notification.services.RetryService
import util.IntegrationTestData.Stubs._
import util.IntegrationTestData._
import util.TestData

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class RetryServiceSpec extends AnyWordSpec
  with IntegrationBaseSpec
  with FutureAwaits
  with DefaultAwaitTimeout
  with Matchers {

  private val service = app.injector.instanceOf[RetryService]
  override protected val clearRepoBeforeEachTest: Boolean = true

  "retryFailedAndNotBlocked" should {
    "retry two outstanding FailedButNotBlocked notifications from the repo" in {
      stubApiSubscriptionFieldsOk()
      stubMetricsAccepted()
      stubExternalPush(ACCEPTED)
      val firstNotificationWorkItem = Repository.domainToRepo(
        notification = TestData.Notification,
        status = ProcessingStatus.FailedButNotBlocked,
        mostRecentPushPullHttpStatus = None,
        availableAt = TestData.TimeNow.toInstant
      )
      val secondNotificationWorkItem = Repository.domainToRepo(
        notification = anotherNotification,
        status = ProcessingStatus.FailedButNotBlocked,
        mostRecentPushPullHttpStatus = None,
        availableAt = TestData.TimeNow.toInstant
      )
      await(mockRepo.pushNew(firstNotificationWorkItem.item))
      await(mockRepo.pushNew(secondNotificationWorkItem.item))
      mockDateTimeService.travelForwardsInTime(1 second)

      await(service.retryFailedAndNotBlocked())
      val db = await(mockRepo.collection.find().toFuture())

      db should have size 2
      verify(WireMock.exactly(2), validExternalPushRequestFor(TestData.ClientCallbackUrl))
      forAll (db){_.status should be (ProcessingStatus.Succeeded.legacyStatus)}
    }
  }
}

object RetryServiceSpec{
  private val anotherNotification: Notification = TestData.Notification.copy(id = AnotherNotificationObjectId)
}