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
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.customs.notification.domain.NotificationWorkItem
import uk.gov.hmrc.customs.notification.repo.MongoDbProvider
import uk.gov.hmrc.mongo.{MongoSpecSupport, ReactiveRepository}
import util.TestData._
import util._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class CustomsNotificationRetrySpec extends AcceptanceTestSpec
  with Matchers
  with OptionValues
  with ApiSubscriptionFieldsService
  with NotificationQueueService
  with PushNotificationService
  with MongoSpecSupport {

  private val endpoint = "/customs-notification/notify-retry"

  val repo = new ReactiveRepository[NotificationWorkItem, BSONObjectID](
    collectionName = "notifications-work-item",
    mongo = app.injector.instanceOf[MongoDbProvider].mongo,
    domainFormat = NotificationWorkItem.notificationWorkItemJF) {
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
}
