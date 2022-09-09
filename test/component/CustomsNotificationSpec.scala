/*
 * Copyright 2022 HM Revenue & Customs
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

import org.mongodb.scala.bson.BsonDocument
import play.api.mvc._
import play.api.mvc.request.RequestTarget
import play.api.test.Helpers
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.domain.NotificationWorkItem
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemMongoRepo
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.PermanentlyFailed
import uk.gov.hmrc.mongo.workitem.ProcessingStatus
import util.TestData._
import util._

import scala.concurrent.Future

class CustomsNotificationSpec extends ComponentTestSpec
  with ApiSubscriptionFieldsService
  with NotificationQueueService
  with PushNotificationService
  with InternalPushNotificationService
  with CustomsNotificationMetricsService {

  private val endpoint = "/customs-notification/notify"

  private def permanentlyFailed(item: NotificationWorkItem): ProcessingStatus = PermanentlyFailed

  private implicit val ec = Helpers.stubControllerComponents().executionContext

  override protected def beforeAll() {
    dropCollection()
    startMockServer()
  }

  override protected def afterAll() {
    stopMockServer()
//    dropCollection()
  }

  override protected def afterEach(): Unit = {
    resetMockServer()
  }

  Feature("Ensure call to push notification service is made when request is valid") {

    Scenario("backend submits a valid request destined for push") {
      startApiSubscriptionFieldsService(validFieldsId,callbackData)
      setupPushNotificationServiceToReturn()

      Given("the API is available")
      val request = ValidRequest.withMethod(POST).withTarget(RequestTarget(path = endpoint, uriString = ValidRequest.uri, queryString = ValidRequest.queryString))

      When("a POST request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, request)

      Then("a response with a 202 status is received")
      result shouldBe 'defined
      val resultFuture: Future[Result] = result.value

      status(resultFuture) shouldBe ACCEPTED

      And("the response body is empty")
      contentAsString(resultFuture) shouldBe 'empty
      
      eventually (verifyNotificationQueueServiceWasNotCalled())
    }

    Scenario("backend submits a valid request destined for pull") {
      runNotificationQueueService(CREATED)
      startApiSubscriptionFieldsService(validFieldsId, DeclarantCallbackDataOneForPull)

      Given("the API is available")
      val request = ValidRequest.withMethod(POST).withTarget(RequestTarget(path = endpoint, uriString = ValidRequest.uri, queryString = ValidRequest.queryString))

      When("a POST request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, request)

      Then("a response with a 202 status is received")
      result shouldBe 'defined
      val resultFuture: Future[Result] = result.value

      status(resultFuture) shouldBe ACCEPTED

      And("the response body is empty")
      contentAsString(resultFuture) shouldBe 'empty
    }

    Scenario("backend submits a valid request with incorrect callback details used") {
      startApiSubscriptionFieldsService(validFieldsId,callbackData)
      setupPushNotificationServiceToReturn(NOT_FOUND)
      runNotificationQueueService(CREATED)

      Given("the API is available")
      val request = ValidRequest.withMethod(POST).withTarget(RequestTarget(path = endpoint, uriString = ValidRequest.uri, queryString = ValidRequest.queryString))

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

  Feature("Ensure work item is saved as permanently failed when existing work item is permanently failed") {

    Scenario("backend submits a valid request") {

      await(collection.deleteMany(BsonDocument()).toFuture())
      await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))

      startApiSubscriptionFieldsService(validFieldsId, callbackData)
      setupPushNotificationServiceToReturn()

      Given("There is an existing work item that is permanently failed")
      eventually(assertWorkItemRepoWithStatus(PermanentlyFailed, 1))

      Given("the API is available")
      val request = ValidRequest.withMethod(POST).withTarget(RequestTarget(path = endpoint, uriString = ValidRequest.uri, queryString = ValidRequest.queryString))

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

  Feature("Ensure call to customs notification gateway are made") {

    Scenario("when notifications are present in the database") {
      startApiSubscriptionFieldsService(validFieldsId, callbackData)
      setupPushNotificationServiceToReturn()
      runNotificationQueueService(CREATED)

      collection.insertOne(internalWorkItem).toFuture()

      And("the notification gateway service was called correctly")
      eventually {
        verifyPushNotificationServiceWasCalledWith(externalPushNotificationRequest)
        verifyInternalServiceWasNotCalledWith(externalPushNotificationRequest)
        verifyNotificationQueueServiceWasNotCalled()
      }
    }
  }
  
  private def assertWorkItemRepoWithStatus(status: ProcessingStatus, count: Int) = {
    val workItems = await(collection.find().toFuture())
    workItems should have size count
    workItems.head.status shouldBe status
  }
}
