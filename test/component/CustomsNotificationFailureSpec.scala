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

package component

import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc._
import play.api.mvc.request.RequestTarget
import play.api.test.Helpers
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemMongoRepo
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.workitem._
import util.TestData._
import util._

import scala.concurrent.Future

class CustomsNotificationFailureSpec extends ComponentTestSpec
  with ApiSubscriptionFieldsService
  with NotificationQueueService
  with PushNotificationService
  with MongoSpecSupport {

  private val endpoint = "/customs-notification/notify"

  private lazy val repo = app.injector.instanceOf[NotificationWorkItemMongoRepo]

  private lazy val pollerConfigs = Map(
    "retry.poller.interval.milliseconds" -> 100,
    "retry.poller.retryAfterFailureInterval.seconds" -> 1,
    "retry.poller.inProgressRetryAfter.seconds" -> 1,
    "retry.poller.instances" -> 10,
    "unblock.poller.interval.milliseconds" -> 100
  )

  private implicit val ec = Helpers.stubControllerComponents().executionContext
  override implicit lazy val app: Application = new GuiceApplicationBuilder().configure(acceptanceTestConfigs ++ pollerConfigs).build()

  override protected def afterAll() {
    stopMockServer()
    await(repo.drop)
  }

  override protected def beforeEach() {
    startMockServer()
    await(repo.drop)
  }

  override protected def afterEach(): Unit = {
    resetMockServer()
    stopMockServer()
    await(repo.drop)
  }

  Feature("Ensure offline retry") {

    Scenario("backend submits a valid PUSH request with incorrect callback details used resulting in endpoint returning a 500") {

      startApiSubscriptionFieldsService(validFieldsId,callbackData)
      Given("the Push endpoint is setup to return NOT_FOUND")
      setupPushNotificationServiceToReturn(INTERNAL_SERVER_ERROR)

      And("the API is available")
      val request = ValidRequest.withMethod(POST).withTarget(RequestTarget(path = endpoint, uriString = ValidRequest.uri, queryString = ValidRequest.queryString))

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

      When("the Push endpoint is then setup to return a NO_CONTENT success response")
      setupPushNotificationServiceToReturn()

      Then("the notification is retried successfully and the status is set to success")
      eventually(assertOneWorkItemRepoWithStatus(Succeeded))

      And("pull queue was not called")
      eventually(verifyNotificationQueueServiceWasNotCalled())
    }

    Scenario("backend submits a valid PULL request but pull queue is unavailable") {

      startApiSubscriptionFieldsService(validFieldsId, DeclarantCallbackDataOneForPull)
      Given("the Push endpoint is setup to return NOT_FOUND")
      runNotificationQueueService(NOT_FOUND)

      And("the API is available")
      val request = ValidRequest.withMethod(POST).withTarget(RequestTarget(path = endpoint, uriString = ValidRequest.uri, queryString = ValidRequest.queryString))

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
