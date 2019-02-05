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
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.Result
import play.api.test.Helpers._
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.customs.notification.domain.NotificationWorkItem
import uk.gov.hmrc.customs.notification.repo.{MongoDbProvider, WorkItemFormat}
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers.ClockJodaExtensions
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.workitem._
import util.TestData._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.Utility.trim
import scala.xml.XML.loadString

class CustomsNotificationBlockedSpec extends AcceptanceTestSpec
  with Matchers
  with OptionValues
  with MongoSpecSupport {

  private def permanentlyFailed(item: NotificationWorkItem): ProcessingStatus = PermanentlyFailed
  private val missingClientIdError =
    <errorResponse>
      <code>BAD_REQUEST</code>
      <message>X-Client-ID required</message>
    </errorResponse>

  val repo = new WorkItemRepository[NotificationWorkItem, BSONObjectID](
    collectionName = "notifications-work-item",
    mongo = app.injector.instanceOf[MongoDbProvider].mongo,
    itemFormat = WorkItemFormat.workItemMongoFormat[NotificationWorkItem]) {

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
  }

  override protected def afterAll() {
    await(repo.drop)
  }

  feature("Ensure requests for blocked data is made when request is valid") {
    scenario("a valid request returns the correct blocked count") {
      Given("the API is available")
      And("there is data in the database")
      await(repo.pushNew(NotificationWorkItem1, DateTime.now(), permanentlyFailed _))
      await(repo.pushNew(NotificationWorkItem2, DateTime.now(), permanentlyFailed _))

      When("a GET request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, ValidBlockedCountRequest)

      Then("a response with a 200 status is received")
      result shouldBe 'defined
      val resultFuture: Future[Result] = result.value

      status(resultFuture) shouldBe OK

      And("the response body contains the expected count as xml")
      trim(loadString(contentAsString(resultFuture))) shouldBe trim(<pushNotificationBlockedCount>2</pushNotificationBlockedCount>)
    }
  }

  feature("Ensure error responses are correct for invalid requests") {
    scenario("a request without a client id header returns the correct error response") {
      Given("the API is available")
      And("there is data in the database")
      await(repo.pushNew(NotificationWorkItem1, DateTime.now(), permanentlyFailed _))
      await(repo.pushNew(NotificationWorkItem2, DateTime.now(), permanentlyFailed _))

      When("a GET request with data is sent to the API")
      val result: Option[Future[Result]] = route(app = app, InvalidBlockedCountRequest)

      Then("a response with a 400 status is received")
      result shouldBe 'defined
      val resultFuture: Future[Result] = result.value
      status(resultFuture) shouldBe BAD_REQUEST

      And("the response body contains the expected error text")
      trim(loadString(contentAsString(resultFuture))) shouldBe trim(missingClientIdError)
    }
  }

}
