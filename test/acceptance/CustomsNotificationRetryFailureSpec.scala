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

package acceptance

import org.scalatest.{Matchers, OptionValues}
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc._
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemMongoRepo
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.workitem._
import util.TestData._
import util._

import scala.concurrent.Future

class CustomsNotificationRetryFailureSpec extends AcceptanceTestSpec
  with Matchers
  with OptionValues
  with ApiSubscriptionFieldsService
  with NotificationQueueService
  with PushNotificationService
  with MongoSpecSupport {

  private val endpoint = "/customs-notification/notify-retry"

  private lazy val repo = app.injector.instanceOf[NotificationWorkItemMongoRepo]


  private lazy val pollerConfigs = Map(
    "push.retry.initialPollingInterval.milliseconds" -> 100,
    "push.retry.retryAfterFailureInterval.seconds" -> 1,
    "push.retry.inProgressRetryAfter.seconds" -> 1,
    "push.retry.poller.instances" -> 10,
    "unblock.polling.delay.duration.milliseconds" -> 100
  )

  override implicit lazy val app: Application = new GuiceApplicationBuilder().configure(acceptanceTestConfigs ++ pollerConfigs).build()

  override protected def beforeEach() {
    await(repo.drop)
    startMockServer()
  }

  override protected def afterEach(): Unit = {
    resetMockServer()
    stopMockServer()
    await(repo.drop)
  }

  feature("Ensure offline retry") {
    scenario("backend submits a valid PUSH request with incorrect callback details used resulting in endpoint returning a 404") {

      startApiSubscriptionFieldsService(validFieldsId,callbackData)
      Given("the Push endpoint is setup to return NOT_FOUND")
      setupPushNotificationServiceToReturn(NOT_FOUND)

      And("the API is available")
      val request = ValidRequest.copyFakeRequest(method = POST, uri = endpoint)

      When("a POST request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, request)

      Then("a response with a 202 status is received")
      result shouldBe 'defined
      val resultFuture: Future[Result] = result.value
      status(resultFuture) shouldBe ACCEPTED
      And("the response body is empty")
      contentAsString(resultFuture) shouldBe 'empty

      When("the unblock poller and retry pollers have have a chance to run")

      Then("the status is set to InProgress")
      eventually(assertOneWorkItemRepoWithStatus(InProgress))

      Then("the status is set to PermanentlyFailed")
      eventually(assertOneWorkItemRepoWithStatus(PermanentlyFailed))

      Then("the status is set to Failed")
      eventually(assertOneWorkItemRepoWithStatus(Failed))

      Then("the status is set to PermanentlyFailed")
      eventually(assertOneWorkItemRepoWithStatus(PermanentlyFailed))

      When("the Push endpoint is then setup to return a NO_CONTENT success response")
      setupPushNotificationServiceToReturn()

      Then("the notification is retried successfully and the status is set to success")
      eventually(assertOneWorkItemRepoWithStatus(Succeeded))

      And("pull queue was not called")
      verifyNotificationQueueServiceWasNotCalled()
    }

    scenario("backend submits a valid PULL request but pull queue is unavailable") {

      startApiSubscriptionFieldsService(validFieldsId, DeclarantCallbackDataOneForPull)
      Given("the Push endpoint is setup to return NOT_FOUND")
      runNotificationQueueService(NOT_FOUND)

      And("the API is available")
      val request = ValidRequest.copyFakeRequest(method = POST, uri = endpoint)

      When("a POST request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, request)

      Then("a response with a 202 status is received")
      result shouldBe 'defined
      val resultFuture: Future[Result] = result.value
      status(resultFuture) shouldBe ACCEPTED
      And("the response body is empty")
      contentAsString(resultFuture) shouldBe 'empty

      When("the unblock poller and retry pollers have have a chance to run")

      Then("the status is set to InProgress")
      eventually(assertOneWorkItemRepoWithStatus(InProgress))

      Then("the status is set to Failed")
      eventually(assertOneWorkItemRepoWithStatus(Failed))

      Then("the status is set to PermanentlyFailed")
      eventually(assertOneWorkItemRepoWithStatus(PermanentlyFailed))

      When("the Pull endpoint is then setup to return a NO_CONTENT success response")
      runNotificationQueueService()

      Then("the notification is retried successfully and the status is set to success")
      eventually(assertOneWorkItemRepoWithStatus(Succeeded))

      And("Push queue was not called")
      verifyPushNotificationServiceWasNotCalled()
    }
  }

  private def assertOneWorkItemRepoWithStatus(status: ProcessingStatus) = {
    val workItems = await(repo.find())
    workItems should have size 1
    workItems.head.status shouldBe status
  }

}
