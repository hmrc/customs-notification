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
import uk.gov.hmrc.customs.notification.domain.ClientNotification
import uk.gov.hmrc.customs.notification.repo.MongoDbProvider
import uk.gov.hmrc.mongo.{MongoSpecSupport, ReactiveRepository}
import util.TestData._
import util._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

//TODO consider merging with CustomsNotificationSpec
class CustomsNotificationWorkItemSpec extends AcceptanceTestSpec
  with Matchers
  with OptionValues
  with ApiSubscriptionFieldsService
  with NotificationQueueService
  with PushNotificationService
  with GoogleAnalyticsSenderService
  with MongoSpecSupport {

  private val endpoint = "/customs-notification/notify-retry"
  private val googleAnalyticsTrackingId: String = "UA-12345678-2"
  private val googleAnalyticsClientId: String = "555"
  private val googleAnalyticsEventValue = "10"

  val repo = new ReactiveRepository[ClientNotification, BSONObjectID](
    collectionName = "notifications-work-item",
    mongo = app.injector.instanceOf[MongoDbProvider].mongo,
    domainFormat = ClientNotification.clientNotificationJF) {
  }

  override implicit lazy val app: Application = new GuiceApplicationBuilder().configure(
    acceptanceTestConfigs +
      ("googleAnalytics.trackingId" -> googleAnalyticsTrackingId) +
      ("googleAnalytics.clientId" -> googleAnalyticsClientId) +
      ("googleAnalytics.eventValue" -> googleAnalyticsEventValue)).build()


  private def callWasMadeToGoogleAnalyticsWith: (String, String) => Boolean =
    aCallWasMadeToGoogleAnalyticsWith(googleAnalyticsTrackingId, googleAnalyticsClientId, googleAnalyticsEventValue) _


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

  override protected def beforeEach(): Unit = {
    startApiSubscriptionFieldsService(validFieldsId,callbackData)
    setupApiSubscriptionFieldsServiceToReturn(NOT_FOUND, someFieldsId)
    setupPushNotificationServiceToReturn()
    setupGoogleAnalyticsEndpoint()
  }


  feature("Ensure call to push notification service is made when request is valid") {

    scenario("DMS/MDG submits a valid request") {
      setupGoogleAnalyticsEndpoint()

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

    scenario("DMS/MDG submits a valid request with incorrect callback details used") {
      setupPushNotificationServiceToReturn(NOT_FOUND)
      setupGoogleAnalyticsEndpoint()
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
