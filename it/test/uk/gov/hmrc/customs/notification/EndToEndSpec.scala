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

package uk.gov.hmrc.customs.notification

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.*
import org.scalatest.LoneElement.convertToCollectionLoneElementWrapper
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{DoNotDiscover, GivenWhenThen, Inside, Suite}
import org.scalatestplus.play.ConfiguredServer
import play.api.http.Status.*
import uk.gov.hmrc.customs.notification.config.RetryDelayConfig
import uk.gov.hmrc.customs.notification.models.ProcessingStatus
import uk.gov.hmrc.customs.notification.models.ProcessingStatus.{FailedAndBlocked, FailedButNotBlocked}
import uk.gov.hmrc.customs.notification.repositories.NotificationRepository
import uk.gov.hmrc.customs.notification.repositories.NotificationRepository.Dto
import uk.gov.hmrc.customs.notification.util.HeaderNames.*
import uk.gov.hmrc.customs.notification.util.IntegrationTestHelpers.*
import uk.gov.hmrc.customs.notification.util.IntegrationTestHelpers.PathFor.*
import uk.gov.hmrc.customs.notification.util.TestData.*
import uk.gov.hmrc.customs.notification.util.*
import uk.gov.hmrc.mongo.workitem.WorkItem

import java.util.UUID
import scala.concurrent.duration.DurationInt

@DoNotDiscover
private class TestOnlyEndToEndSpec extends IntegrationSpecBase {
  override def nestedSuites: IndexedSeq[Suite] =
    Vector(new EndToEndSpec(wireMockServer, mockDateTimeService, mockObjectIdService))
}

class EndToEndSpec(protected val wireMockServer: WireMockServer,
                   protected val mockDateTimeService: MockDateTimeService,
                   protected val mockObjectIdService: MockObjectIdService)
  extends AnyFeatureSpec
    with ConfiguredServer
    with WireMockHelpers
    with WsClientHelpers
    with RepositoriesFixture
    with GivenWhenThen
    with Matchers
    with Inside {

  private lazy val retryDelayConfig = app.injector.instanceOf[RetryDelayConfig]

  Feature("POST /notify endpoint") {

    Scenario("A request is made and all upstream services are working") {

      Given("all upstream services are working")
      stubGetClientDataOk()
      stub(post)(Metrics, ACCEPTED)
      stub(post)(ExternalPush, ACCEPTED)

      When("a request is made")
      val response = makeValidNotifyRequest()

      Then("it should save the succeeded notification in the database")
      val expectedNotification =
        NotificationRepository.Mapping.domainToRepo(
          notification = Notification,
          status = ProcessingStatus.Succeeded,
          updatedAt = TimeNow.toInstant,
          failureCount = 0)
      getDatabase().loneElement shouldBe expectedNotification

      And("it should send the notification only once")
      verify(WireMock.exactly(1), postRequestedFor(urlMatching(ExternalPush)))

      And("it should return 202 Accepted")
      response.status shouldBe ACCEPTED
    }

    Scenario("A request is made and the client callback service is down") {

      Given("client data and metrics services are working")
      stub(get)(clientDataWithCsid(AnotherCsid), OK, stubClientDataResponseBody(csid = AnotherCsid))
      stubGetClientDataOk()
      stub(post)(Metrics, ACCEPTED)

      And("there is a previous notification set as FailedButNotBlocked for another csid (e.g. because of a 400 Bad Request)")
      mockDateTimeService.travelBackInTime(1.second)
      stub(post)(ExternalPush, BAD_REQUEST)
      val objectIdForOtherCsid = AnotherObjectId
      makeValidNotifyRequest(AnotherCsid, objectIdForOtherCsid)

      And("there is a previous notification set as FailedButNotBlocked for this csid")
      stub(post)(ExternalPush, BAD_REQUEST)
      val otherObjectIdForThisCsid = YetAnotherObjectId
      makeValidNotifyRequest(toAssign = otherObjectIdForThisCsid)

      And("client callback service returns 500 Internal Server Error")
      stub(post)(ExternalPush, INTERNAL_SERVER_ERROR)

      When("a request is made")
      WireMock.resetAllRequests()
      mockDateTimeService.timeTravelToNow()
      val response = makeValidNotifyRequest()

      Then("it should attempt the push request")
      verify(
        WireMock.exactly(1),
        validExternalPushRequest()
      )

      And("it should save the notification as FailedAndBlocked in the database")
      val expectedWorkItem =
        NotificationRepository.Mapping.domainToRepo(
          notification = Notification,
          status = ProcessingStatus.FailedAndBlocked,
          updatedAt = TimeNow.toInstant,
          failureCount = 1)
          .copy(
            failureCount = 1,
            availableAt = TimeNow.toInstant
          )
      val db = getDatabase()
      inside(db.find(_.id == ObjectId)) { case Some(actual) =>
        actual shouldBe expectedWorkItem
      }

      And("it should set the previous notification for this csid to FailedAndBlocked")
      inside(db.find(_.id == otherObjectIdForThisCsid)) { case Some(actual) =>
        actual.status shouldBe FailedAndBlocked.internalStatus
      }

      And("it should not change the status for the notification for another csid")
      inside(db.find(_.id == objectIdForOtherCsid)) { case Some(actual) =>
        actual.status shouldBe FailedButNotBlocked.internalStatus
      }

      And("it should return 202 Accepted")
      response.status shouldBe ACCEPTED
    }

    Scenario("A request is made and the client callback service rejects the request") {

      Given("client data and metrics services are working")
      stub(get)(clientDataWithCsid(AnotherCsid), OK, stubClientDataResponseBody(csid = AnotherCsid))
      stubGetClientDataOk()
      stub(post)(Metrics, ACCEPTED)

      And("there is a previous notification set as FailedButNotBlocked for this csid")
      mockDateTimeService.travelBackInTime(1.second)
      stub(post)(ExternalPush, BAD_REQUEST)
      makeValidNotifyRequest(toAssign = AnotherObjectId)

      And("client callback service returns 400 Bad Request")
      stub(post)(ExternalPush, BAD_REQUEST)
      mockDateTimeService.timeTravelToNow()

      When("a request is made")
      WireMock.resetAllRequests()
      val response = makeValidNotifyRequest()

      Then("it should attempt the push request")
      verify(
        WireMock.exactly(1),
        validExternalPushRequest()
      )

      And("it should save the notification as FailedButNotBlocked in the database")
      val expectedWorkItem =
        NotificationRepository.Mapping.domainToRepo(
          notification = Notification,
          status = ProcessingStatus.FailedButNotBlocked,
          updatedAt = TimeNow.toInstant,
          failureCount = 1)
          .copy(
            failureCount = 1,
            availableAt = TimeNow.plusSeconds(retryDelayConfig.failedButNotBlocked.toSeconds).toInstant
          )
      val db = getDatabase()
      inside(db.find(_.id == ObjectId)) { case Some(actual) =>
        actual shouldBe expectedWorkItem
      }

      And("it should return 202 Accepted")
      response.status shouldBe ACCEPTED
    }

    Scenario("A request is made but the Client Data Service doesn't recognise the csid") {
      val unrecognisedCsid = models.ClientSubscriptionId(UUID.fromString("00000000-0000-0000-0000-000000000000"))
      stub(get)(clientDataWithCsid(unrecognisedCsid), NOT_FOUND)

      val expectedBody =
        "<errorResponse><code>BAD_REQUEST</code><message>The X-CDS-Client-ID header is invalid.</message></errorResponse>"
      val response = makeValidNotifyRequest(unrecognisedCsid)

      response.status shouldBe BAD_REQUEST
      response.body shouldBe expectedBody
    }

    Scenario("A request is made without a X-Correlation-ID header") {

      Given("all upstream services are working")
      stubGetClientDataOk()
      stub(post)(Metrics, ACCEPTED)
      stub(post)(ExternalPush, ACCEPTED)

      When("a request is received without a X-Correlation-ID header")
      val response = makeValidNotifyRequestWithout(X_CORRELATION_ID_HEADER_NAME)

      Then("it should return 202 Accepted")
      response.status shouldBe ACCEPTED
    }

    Scenario("A request is made and the Client Data Service is down") {

      Given("the client data service is down")
      stub(get)(clientDataWithCsid(TranslatedCsid), INTERNAL_SERVER_ERROR)

      When("a request is made")
      val response = makeValidNotifyRequest()

      Then("it should return 500 Internal Server Error and an error message")
      val expectedBody =
        "<errorResponse><code>INTERNAL_SERVER_ERROR</code><message>Internal server error.</message></errorResponse>"
      response.status shouldBe INTERNAL_SERVER_ERROR
      response.body shouldBe expectedBody
    }
  }

  Feature("GET /blocked-count endpoint") {

    Scenario("A valid request is made") {
      Given("there are some FailedAndBlocked notifications in the database")
      stubGetClientDataOk()
      stub(post)(Metrics, ACCEPTED)
      stub(post)(ExternalPush, INTERNAL_SERVER_ERROR)
      makeValidNotifyRequest()
      makeValidNotifyRequest(toAssign = AnotherObjectId)
      getDatabase().size shouldBe 2

      When("a request is made")
      val response =
        wsUrl(Endpoints.BlockedCount)
          .withHttpHeaders(X_CLIENT_ID_HEADER_NAME -> ClientId.id)
          .get()
          .futureValue

      Then("it should return 200 OK with the count of blocked")
      val expectedBody = "<pushNotificationBlockedCount>2</pushNotificationBlockedCount>"
      response.status shouldBe OK
      response.body shouldBe expectedBody
    }

    Scenario("A request without a X-Client-ID header is made") {
      When("a request without a X-Client-ID header is received")
      val response =
        wsUrl(Endpoints.BlockedCount)
          .get()
          .futureValue

      Then("it should return 400 Bad Request with an error message")
      val expectedBody =
        "<errorResponse><code>BAD_REQUEST</code><message>The X-Client-ID header is missing.</message></errorResponse>"

      response.status shouldBe BAD_REQUEST
      response.body shouldBe expectedBody
    }
  }

  Feature("DELETE /customs-notification/blocked-flag") {

    Scenario("A valid request is made when there are some FailedAndBlocked notifications for that client ID") {
      Given("there are some FailedAndBlocked notifications in the database for various client IDs")
      stubGetClientDataOk()
      stub(get)(clientDataWithCsid(AnotherCsid), OK, stubClientDataResponseBody(csid = AnotherCsid, clientId = AnotherClientId))
      stub(post)(Metrics, ACCEPTED)
      stub(post)(ExternalPush, INTERNAL_SERVER_ERROR)
      makeValidNotifyRequest()
      makeValidNotifyRequest(toAssign = AnotherObjectId)
      makeValidNotifyRequest(AnotherCsid, YetAnotherObjectId)

      When("a valid request is received")
      val response =
        wsUrl(Endpoints.BlockedFlag)
          .withHttpHeaders(X_CLIENT_ID_HEADER_NAME -> ClientId.id)
          .delete()
          .futureValue

      Then("it should set all notifications to FailedButNotBlocked just for that client ID")
      val db = getDatabase()
      db.size shouldBe 3
      val (workItemsForThisClient, otherWorkItems) = db.partition(_.item.clientId == ClientId)
      workItemsForThisClient.size shouldBe 2
      workItemsForThisClient.foreach(_.status shouldBe FailedButNotBlocked.internalStatus)
      otherWorkItems.size shouldBe 1
      otherWorkItems.head.status shouldBe FailedAndBlocked.internalStatus

      Then("it should return 204 No Content")
      response.status shouldBe NO_CONTENT
    }

    Scenario("A valid request is made when there are no FailedAndBlocked notifications for that csid") {
      When("a valid request is received")
      val response =
        wsUrl(Endpoints.BlockedFlag)
          .withHttpHeaders(X_CLIENT_ID_HEADER_NAME -> ClientId.id)
          .delete()
          .futureValue

      Then("it should return 404 Not Found")
      val expectedBody = "<errorResponse><code>NOT_FOUND</code><message>Resource was not found.</message></errorResponse>"
      response.status shouldBe NOT_FOUND
      response.body shouldBe expectedBody
    }
  }

  private def getDatabase(): Seq[WorkItem[Dto.NotificationWorkItem]] =
    notificationRepo.underlying.collection.find().toFuture().futureValue
}
