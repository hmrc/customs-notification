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

import play.api.mvc.Result
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.models.repo.NotificationWorkItem
import uk.gov.hmrc.mongo.workitem.ProcessingStatus
import uk.gov.hmrc.mongo.workitem.ProcessingStatus._
import util.TestData._

import scala.concurrent.Future
import scala.xml.Utility.trim
import scala.xml.XML.loadString

class CustomsNotificationBlockedSpec extends ComponentTestSpec {

  private def permanentlyFailed(item: NotificationWorkItem): ProcessingStatus = PermanentlyFailed
  private val missingClientIdError =
    <errorResponse>
      <code>BAD_REQUEST</code>
      <message>X-Client-ID required</message>
    </errorResponse>

  private val notFoundError =
    <errorResponse>
      <code>NOT_FOUND</code>
      <message>Resource was not found</message>
    </errorResponse>

  override protected def beforeEach(): Unit = {
    emptyCollection()
  }

  Feature("Ensure requests for blocked count are processed correctly") {
    Scenario("a valid request returns the correct blocked count") {
      Given("the API is available")
      And("there is data in the database")
      await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem2, repository.now(), permanentlyFailed))

      When("a GET request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, ValidBlockedCountRequest)

      Then("a response with a 200 status is received")
      result shouldBe Symbol("defined")
      val resultFuture: Future[Result] = result.value
      status(resultFuture) shouldBe OK

      And("the response body contains the expected count as xml")
      trim(loadString(contentAsString(resultFuture))) shouldBe trim(<pushNotificationBlockedCount>2</pushNotificationBlockedCount>)
    }

    Scenario("a request without a client id header returns the correct error response") {
      Given("the API is available")
      And("there is data in the database")
      await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem2, repository.now(), permanentlyFailed))

      When("a GET request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, InvalidBlockedCountRequest)

      Then("a response with a 400 status is received")
      result shouldBe Symbol("defined")
      val resultFuture: Future[Result] = result.value
      status(resultFuture) shouldBe BAD_REQUEST

      And("the response body contains the expected error text")
      trim(loadString(contentAsString(resultFuture))) shouldBe trim(missingClientIdError)
    }
  }

  Feature("Ensure requests for deleting blocked flags are processed correctly") {

    Scenario("a request that removes blocks returns the correct response") {
      Given("the API is available")
      And("there is data in the database")
      await(repository.pushNew(NotificationWorkItem1, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem2, repository.now(), permanentlyFailed))
      await(repository.pushNew(NotificationWorkItem3, repository.now(), permanentlyFailed))

      When("a DELETE request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, ValidDeleteBlockedRequest)

      Then("a response with a 204 status is received")
      result shouldBe Symbol("defined")
      val resultFuture: Future[Result] = result.value
      status(resultFuture) shouldBe NO_CONTENT

      And("the response body is empty")
      contentAsString(resultFuture) shouldBe empty
    }

    Scenario("a request that removes no blocks returns the correct response") {
      Given("the API is available")
      And("there is no data in the database")

      When("a DELETE request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, ValidDeleteBlockedRequest)

      Then("a response with a 404 status is received")
      result shouldBe Symbol("defined")
      val resultFuture: Future[Result] = result.value
      status(resultFuture) shouldBe NOT_FOUND

      And("the response body is empty")
      trim(loadString(contentAsString(resultFuture))) shouldBe trim(notFoundError)
    }

  }

}
