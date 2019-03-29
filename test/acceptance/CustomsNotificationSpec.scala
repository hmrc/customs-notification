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

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{Matchers, OptionValues}
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc._
import play.api.test.Helpers._
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.customs.notification.domain.ClientNotification
import uk.gov.hmrc.mongo.{MongoSpecSupport, ReactiveRepository}
import util.TestData._
import util._

import scala.concurrent.ExecutionContext.Implicits.global // contains blocking code so uses standard scala ExecutionContext
import scala.concurrent.Future
import scala.xml.NodeSeq
import scala.xml.Utility.trim
import scala.xml.XML.loadString

class CustomsNotificationSpec extends AcceptanceTestSpec
  with Matchers with OptionValues
  with ApiSubscriptionFieldsService with NotificationQueueService with TableDrivenPropertyChecks
  with PushNotificationService
  with MongoSpecSupport {

  private val endpoint = "/customs-notification/notify-legacy"

  private val repo = new ReactiveRepository[ClientNotification, BSONObjectID](
    collectionName = "notifications",
    mongo = app.injector.instanceOf[ReactiveMongoComponent].mongoConnector.db,
    domainFormat = ClientNotification.clientNotificationJF) {
  }

  override implicit lazy val app: Application = new GuiceApplicationBuilder().configure(
    acceptanceTestConfigs).build()

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
  }


  feature("Ensure call to push notification service is made when request is valid") {

    scenario("backend submits a valid request") {
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

  private val table =
    Table(
      ("description", "request", "expected response code", "expected response XML"),
      ("accept header missing", MissingAcceptHeaderRequest.copyFakeRequest(method = POST, uri = endpoint), NOT_ACCEPTABLE, errorResponseForMissingAcceptHeader),
      ("content type invalid", InvalidContentTypeHeaderRequest.copyFakeRequest(method = POST, uri = endpoint), UNSUPPORTED_MEDIA_TYPE, errorResponseForInvalidContentType),
      ("unauthorized", InvalidAuthorizationHeaderRequest.copyFakeRequest(method = POST, uri = endpoint), UNAUTHORIZED, errorResponseForUnauthorized),
      ("client id invalid", InvalidClientIdHeaderRequest.copyFakeRequest(method = POST, uri = endpoint), BAD_REQUEST, errorResponseForInvalidClientId),
      ("client id missing", MissingClientIdHeaderRequest.copyFakeRequest(method = POST, uri = endpoint), BAD_REQUEST, errorResponseForMissingClientId),
      ("conversation id invalid", InvalidConversationIdHeaderRequest.copyFakeRequest(method = POST, uri = endpoint), BAD_REQUEST, errorResponseForInvalidConversationId),
      ("conversation id missing", MissingConversationIdHeaderRequest.copyFakeRequest(method = POST, uri = endpoint), BAD_REQUEST, errorResponseForMissingConversationId),
      ("empty payload", ValidRequest.withXmlBody(NodeSeq.Empty).copyFakeRequest(method = POST, uri = endpoint), BAD_REQUEST, errorResponseForPlayXmlBodyParserError)
    )

  feature("For invalid requests, ensure error payloads are correct") {

    forAll(table) { (description, request, httpCode, responseXML) =>

      scenario(s"backend submits an invalid request with $description") {

        Given("the API is available")

        When("a POST request with data is sent to the API")
        val result: Option[Future[Result]] = route(app = app, request)

        Then(s"a response with a $httpCode status is received")
        result shouldBe 'defined
        val resultFuture: Future[Result] = result.value

        status(resultFuture) shouldBe httpCode

        And(s"the response body is $description XML")
        trim(loadString(contentAsString(resultFuture))) shouldBe trim(responseXML)
      }
    }

  }

}
