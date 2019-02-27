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
import play.api.mvc._
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemMongoRepo
import uk.gov.hmrc.customs.notification.services.WorkItemService
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.workitem._
import util.TestData._
import util._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class CustomsNotificationRetryFailureWithManualPollingSpec extends AcceptanceTestSpec
  with Matchers
  with OptionValues
  with ApiSubscriptionFieldsService
  with NotificationQueueService
  with PushNotificationService
  with CustomsNotificationMetricsService
  with MongoSpecSupport {

  private val endpoint = "/customs-notification/notify-retry"

  private lazy val repo = app.injector.instanceOf[NotificationWorkItemMongoRepo]

  private lazy val workItemService = app.injector.instanceOf[WorkItemService]

  private lazy val disablePollerConfigs = Map[String, AnyVal](
    "push.retry.enabled" -> false,
    "unblock.polling.enabled" -> false,
    "unblock.polling.delay.duration.milliseconds" -> 0
  )

  override implicit lazy val app: Application = new GuiceApplicationBuilder().configure(acceptanceTestConfigs ++ disablePollerConfigs).build()

  override protected def beforeEach() {
    await(repo.drop)
    startMockServer()
  }

  override protected def afterEach(): Unit = {
    resetMockServer()
    stopMockServer()
    await(repo.drop)
  }

  feature("Full offline retry lifecycle's for PUSH, PULL and subscription fields lookup failures") {
    scenario("Full PUSH failure lifecycle. Initially PUSH is down then becomes available and Notification is eventually pushed successfully") {

      setupCustomsNotificationMetricsServiceToReturn()
      startApiSubscriptionFieldsService(validFieldsId, callbackData)
      Given("the Push endpoint is setup to return NOT_FOUND")
      setupPushNotificationServiceToReturn(NOT_FOUND)

      And("the API is available")
      val request = ValidRequest.copyFakeRequest(method = POST, uri = endpoint)

      When("a POST request with data is sent to the API and Push endpoint is unavailable")
      val maybeEventualResult: Option[Future[Result]] = route(app = app, request)

      And("a 202 response is returned from customs notifications")
      maybeEventualResult shouldBe 'defined
      await(maybeEventualResult.value).header.status shouldBe ACCEPTED

      Then("there is one work item with status PermanentlyFailed")
      assertOneWorkItemRepo(PermanentlyFailed)

      And("the PUSH endpoint should have been called once")
      actualCallsMadeToClientsPushService should have size 1

      When("the unblocking poller runs")
      await(repo.unblock()) shouldBe 1

      Then("there is one work item with status Failed")
      assertOneWorkItemRepo(Failed)

      When("the offline retry poller runs and Push endpoint is still unavailable")
      await(workItemService.processOne()) shouldBe true

      Then("there is one work item with status PermanentlyFailed")
      assertOneWorkItemRepo(PermanentlyFailed)

      And("the PUSH endpoint should have been called twice")
      actualCallsMadeToClientsPushService should have size 2

      When("the unblocking poller runs")
      await(repo.unblock()) shouldBe 1

      Then("there is one work item with status Failed")
      assertOneWorkItemRepo(Failed)

      When("the Push endpoint is then setup to return a NO_CONTENT success response")

      setupPushNotificationServiceToReturn()

      And("the offline retry poller runs")
      await(workItemService.processOne()) shouldBe true

      Then("there is one work item with status Succeeded")
      assertOneWorkItemRepo(Succeeded)

      And("the PUSH endpoint should have been called 3 times")
      actualCallsMadeToClientsPushService should have size 3

      And("the PULL queue service was never called")
      verifyNotificationQueueServiceWasNotCalled()
    }

    scenario("Full PULL failure lifecycle. Initially PULL is down then becomes available and Notification is eventually enqueued successfully") {

      startApiSubscriptionFieldsService(validFieldsId, DeclarantCallbackDataOneForPull)
      Given("the Pull endpoint is setup to return NOT_FOUND")
      runNotificationQueueService(NOT_FOUND)

      And("the API is available")
      val request = ValidRequest.copyFakeRequest(method = POST, uri = endpoint)

      When("a POST request with data is sent to the API and Push endpoint is unavailable")
      val maybeEventualResult: Option[Future[Result]] = route(app = app, request)

      And("a 202 response is returned from customs notifications")
      maybeEventualResult shouldBe 'defined
      await(maybeEventualResult.value).header.status shouldBe ACCEPTED

      Then("there is one work item with status PermanentlyFailed")
      assertOneWorkItemRepo(PermanentlyFailed)

      And("the PULL service should have been called once")
      actualCallsMadeToPullQ should have size 1

      When("the unblocking poller runs")
      await(repo.unblock()) shouldBe 1

      Then("there is one work item with status Failed")
      assertOneWorkItemRepo(Failed)

      When("the offline retry poller runs and Push endpoint is still unavailable")
      await(workItemService.processOne()) shouldBe true

      Then("there is one work item with status PermanentlyFailed")
      assertOneWorkItemRepo(PermanentlyFailed)

      And("the PULL service should have been called twice")
      actualCallsMadeToPullQ should have size 2

      When("the unblocking poller runs")
      await(repo.unblock()) shouldBe 1

      Then("there is one work item with status Failed")
      assertOneWorkItemRepo(Failed)

      When("the PULL service is then setup to return a CREATED success response")
      runNotificationQueueService()

      And("the offline retry poller runs")
      await(workItemService.processOne()) shouldBe true

      Then("there is one work item with status Succeeded")
      assertOneWorkItemRepo(Succeeded)

      And("the PULL service should have been called 3 times")
      actualCallsMadeToPullQ should have size 3

      And("the PUSH service was never called")
      verifyPushNotificationServiceWasNotCalled()
    }

    scenario("Offline retry should cope with intermittent API subscription fields failure") {

      startApiSubscriptionFieldsService(validFieldsId, callbackData)
      Given("the Push endpoint is setup to return NOT_FOUND")
      setupPushNotificationServiceToReturn(NOT_FOUND)

      And("the API is available")
      val request = ValidRequest.copyFakeRequest(method = POST, uri = endpoint)

      When("a POST request with data is sent to the API and Push endpoint is unavailable")
      val maybeEventualResult: Option[Future[Result]] = route(app = app, request)

      And("a 202 response is returned from customs notifications")
      maybeEventualResult shouldBe 'defined
      await(maybeEventualResult.value).header.status shouldBe ACCEPTED

      Then("there is one work item with status PermanentlyFailed")
      assertOneWorkItemRepo(PermanentlyFailed)

      When("the unblocking poller runs")
      await(repo.unblock()) shouldBe 1

      Then("there is one work item with status Failed")
      assertOneWorkItemRepo(Failed)

      When("the offline retry poller runs the Push end point becomes available")
      setupPushNotificationServiceToReturn()
      And("Api Subscription fields endpoint is becomes unavailable")
      setupApiSubscriptionFieldsServiceToReturn(BAD_GATEWAY, validFieldsId)
      await(workItemService.processOne()) shouldBe true

      Then("there is one work item with status PermanentlyFailed")
      assertOneWorkItemRepo(PermanentlyFailed)

      When("the unblocking poller runs")
      await(repo.unblock()) shouldBe 1

      Then("there is one work item with status Failed")
      assertOneWorkItemRepo(Failed)

      When("Api Subscription fields endpoint becomes available")
      startApiSubscriptionFieldsService(validFieldsId, callbackData)

      And("the offline retry poller runs")
      await(workItemService.processOne()) shouldBe true

      Then("there is one work item with status Succeeded")
      assertOneWorkItemRepo(Succeeded)

      And("the PULL queue service was never called")
      verifyNotificationQueueServiceWasNotCalled()
    }

  }

  private def assertOneWorkItemRepo(status: ResultStatus) = {
    val workItems = await(repo.find())
    workItems should have size 1
    workItems.head.status shouldBe status
  }
}
