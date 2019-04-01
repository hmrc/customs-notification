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

import java.time.Clock

import org.joda.time.DateTime
import org.scalatest.{Matchers, OptionValues}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc._
import play.api.test.Helpers._
import play.api.{Application, Configuration}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.customs.notification.domain.NotificationWorkItem
import uk.gov.hmrc.customs.notification.repo.WorkItemFormat
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers._
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.workitem.{PermanentlyFailed, ProcessingStatus, WorkItemFieldNames, WorkItemRepository}
import util.TestData._
import util._

import scala.concurrent.Future

class CustomsNotificationRetrySpec extends AcceptanceTestSpec
  with Matchers
  with OptionValues
  with ApiSubscriptionFieldsService
  with NotificationQueueService
  with PushNotificationService
  with CustomsNotificationMetricsService
  with MongoSpecSupport {

  private val endpoint = "/customs-notification/notify"

  private def permanentlyFailed(item: NotificationWorkItem): ProcessingStatus = PermanentlyFailed

  private val repo: WorkItemRepository[NotificationWorkItem, BSONObjectID] = new WorkItemRepository[NotificationWorkItem, BSONObjectID](
    collectionName = "notifications-work-item",
    mongo = app.injector.instanceOf[ReactiveMongoComponent].mongoConnector.db,
    itemFormat = WorkItemFormat.workItemMongoFormat[NotificationWorkItem],
    config = Configuration().underlying) {

    override def workItemFields: WorkItemFieldNames = new WorkItemFieldNames {
      val receivedAt = "createdAt"
      val updatedAt = "lastUpdated"
      val availableAt = "availableAt"
      val status = "status"
      val id = "_id"
      val failureCount = "failures"
    }

    override def now: DateTime = Clock.systemUTC().nowAsJoda

    override def inProgressRetryAfterProperty: String = ???
  }

  override implicit lazy val app: Application = new GuiceApplicationBuilder().configure(acceptanceTestConfigs).build()

  override protected def beforeAll() {
    await(repo.drop)
    startMockServer()
  }

  override protected def afterAll() {
    stopMockServer()
    await(repo.drop)
  }

  override protected def afterEach(): Unit = {
    resetMockServer()
  }

  feature("Ensure call to push notification service is made when request is valid") {

    scenario("backend submits a valid request destined for push") {
      startApiSubscriptionFieldsService(validFieldsId,callbackData)
      setupPushNotificationServiceToReturn()

      Given("the API is available")
      val request = ValidRequest.copyFakeRequest(method = POST, uri = endpoint)

      When("a POST request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, request)

      Then("a response with a 202 status is received")
      result shouldBe 'defined
      val resultFuture: Future[Result] = result.value

      status(resultFuture) shouldBe ACCEPTED

      And("the response body is empty")
      contentAsString(resultFuture) shouldBe 'empty
    }

    scenario("backend submits a valid request destined for pull") {
      runNotificationQueueService(CREATED)
      startApiSubscriptionFieldsService(validFieldsId, DeclarantCallbackDataOneForPull)

      Given("the API is available")
      val request = ValidRequest.copyFakeRequest(method = POST, uri = endpoint)

      When("a POST request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, request)

      Then("a response with a 202 status is received")
      result shouldBe 'defined
      val resultFuture: Future[Result] = result.value

      status(resultFuture) shouldBe ACCEPTED

      And("the response body is empty")
      contentAsString(resultFuture) shouldBe 'empty
    }

    scenario("backend submits a valid request with incorrect callback details used") {
      startApiSubscriptionFieldsService(validFieldsId,callbackData)
      setupPushNotificationServiceToReturn(NOT_FOUND)
      runNotificationQueueService(CREATED)

      Given("the API is available")
      val request = ValidRequest.copyFakeRequest(method = POST, uri = endpoint)

      When("a POST request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, request)

      Then("a response with a 202 status is received")
      result shouldBe 'defined
      val resultFuture: Future[Result] = result.value

      status(resultFuture) shouldBe ACCEPTED

      And("the response body is empty")
      contentAsString(resultFuture) shouldBe 'empty
    }
  }



  feature("Ensure work item is saved as permanently failed when existing work item is permanently failed") {

    scenario("backend submits a valid request") {

      await(repo.drop)
      await(repo.pushNew(NotificationWorkItem1, DateTime.now(), permanentlyFailed _))

      startApiSubscriptionFieldsService(validFieldsId, callbackData)
      setupPushNotificationServiceToReturn()

      Given("There is an existing work item that is permanently failed")
      eventually(assertWorkItemRepoWithStatus(PermanentlyFailed, 1))

      Given("the API is available")
      val request = ValidRequest.copyFakeRequest(method = POST, uri = endpoint)

      When("a POST request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, request)

      Then("a response with a 202 status is received")
      result shouldBe 'defined
      val resultFuture: Future[Result] = result.value

      status(resultFuture) shouldBe ACCEPTED

      And("the response body is empty")
      contentAsString(resultFuture) shouldBe 'empty

      Then("the status is set to PermanentlyFailed")
      eventually(assertWorkItemRepoWithStatus(PermanentlyFailed, 2))
    }
  }

  private def assertWorkItemRepoWithStatus(status: ProcessingStatus, count: Int) = {
    val workItems = await(repo.find())
    workItems should have size count
    workItems.head.status shouldBe status
  }
}
