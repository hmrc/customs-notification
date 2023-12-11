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

package uk.gov.hmrc.customs.notification.services

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.*
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{DoNotDiscover, GivenWhenThen, Suite}
import org.scalatestplus.play.ConfiguredServer
import play.api.http.Status.*
import play.api.libs.ws.WSClient
import uk.gov.hmrc.customs.notification.IntegrationSpecBase
import uk.gov.hmrc.customs.notification.config.RetryDelayConfig
import uk.gov.hmrc.customs.notification.models.ProcessingStatus.{FailedButNotBlocked, Succeeded}
import uk.gov.hmrc.customs.notification.repo.Repository
import uk.gov.hmrc.customs.notification.repo.Repository.Dto
import uk.gov.hmrc.customs.notification.util.*
import uk.gov.hmrc.customs.notification.util.IntegrationTestHelpers.*
import uk.gov.hmrc.customs.notification.util.IntegrationTestHelpers.PathFor.*
import uk.gov.hmrc.customs.notification.util.TestData.*
import uk.gov.hmrc.mongo.workitem.WorkItem

import scala.concurrent.duration.DurationInt

@DoNotDiscover
private class TestOnlyRetryServiceSpec extends IntegrationSpecBase {
  override def nestedSuites: IndexedSeq[Suite] =
    Vector(new RetryServiceSpec(wireMockServer, mockDateTimeService, mockObjectIdService))
}

class RetryServiceSpec(protected val wireMockServer: WireMockServer,
                       protected val mockDateTimeService: MockDateTimeService,
                       protected val mockObjectIdService: MockObjectIdService) extends AnyFeatureSpec
  with ConfiguredServer
  with WireMockHelpers
  with WsClientHelpers
  with RepositoryHelpers
  with Matchers
  with GivenWhenThen {

  override lazy implicit val wsClient: WSClient = app.injector.instanceOf[WSClient]
  protected lazy val repo: Repository = app.injector.instanceOf[Repository]
  private lazy val retryDelayConfig = app.injector.instanceOf[RetryDelayConfig]
  private lazy val retryService = app.injector.instanceOf[RetryService]

  Feature("Retry Service") {
    Scenario("Retries with existing outstanding FailedButNotBlocked notifications") {

      Given("there are two outstanding FailedButNotBlocked notifications")
      mockDateTimeService.travelBackInTime(retryDelayConfig.failedButNotBlocked + 1.second)
      stubGetClientDataOk()
      stub(post)(Metrics, ACCEPTED)
      stub(post)(ExternalPush, BAD_REQUEST)
      makeValidNotifyRequest()
      makeValidNotifyRequest(toAssign = AnotherObjectId)

      And("there is a FailedButNotBlocked notification that is not due for retry")
      mockDateTimeService.timeTravelToNow()
      makeValidNotifyRequest(toAssign = YetAnotherObjectId)

      And("the client callback service is working")
      stub(post)(ExternalPush, ACCEPTED)

      When("a retry for FailedButNotBlocked notifications is triggered")
      mockDateTimeService.timeTravelToNow()
      WireMock.resetAllRequests()
      retryService
        .retryFailedButNotBlocked()
        .futureValue

      Then("it should only make a single ClientData request for this csid")
      verify(
        WireMock.exactly(1),
        getRequestedFor(urlMatching(PathFor.clientDataWithCsid()))
      )

      Then("it should successfully retry only the outstanding notifications")
      val expectedStatusesByObjectId =
        List(
          ObjectId -> Succeeded.legacyStatus,
          AnotherObjectId -> Succeeded.legacyStatus,
          YetAnotherObjectId -> FailedButNotBlocked.legacyStatus
        )
      val db = getDatabase()
      db.map(w => w.id -> w.status) should contain theSameElementsAs expectedStatusesByObjectId
    }

    Scenario("Retries with existing FailedAndBlocked notifications") {

      Given("two csids with two FailedAndBlocked notifications for each one (four in total)")
      mockDateTimeService.travelBackInTime(retryDelayConfig.failedAndBlocked + 1.second)
      stubGetClientDataOk()
      stub(get)(clientDataWithCsid(AnotherCsid), OK, stubClientDataResponseBody(csid = AnotherCsid))
      stub(post)(Metrics, ACCEPTED)
      stub(post)(ExternalPush, INTERNAL_SERVER_ERROR)
      makeValidNotifyRequest()
      makeValidNotifyRequest(toAssign = AnotherObjectId)
      makeValidNotifyRequest(csid = AnotherCsid, toAssign = YetAnotherObjectId)
      makeValidNotifyRequest(csid = AnotherCsid, toAssign = FourthObjectId)

      And("the client callback service is working")
      stub(post)(ExternalPush, ACCEPTED)

      When("a retry for FailedAndBlocked notifications is triggered")
      mockDateTimeService.timeTravelToNow()
      WireMock.resetAllRequests()
      retryService
        .retryFailedAndBlocked()
        .futureValue

      Then("it should make two ClientData requests; one for each csid")
      verify(
        WireMock.exactly(1),
        getRequestedFor(urlMatching(PathFor.clientDataWithCsid()))
      )
      verify(
        WireMock.exactly(1),
        getRequestedFor(urlMatching(PathFor.clientDataWithCsid(AnotherCsid)))
      )

      And("retry only one notification per client subscription ID")
      verify(
        WireMock.exactly(2),
        validExternalPushRequest()
      )

      And("set the rest to FailedButNotBlocked")
      val expectedStatusesByCsid =
        List(
          TranslatedCsid -> Succeeded.legacyStatus,
          TranslatedCsid -> FailedButNotBlocked.legacyStatus,
          AnotherCsid -> Succeeded.legacyStatus,
          AnotherCsid -> FailedButNotBlocked.legacyStatus,
        )
      val db = getDatabase()
      db.map(w => w.item._id -> w.status) should contain theSameElementsAs expectedStatusesByCsid
    }

    Scenario("Retries while a notification was just recently saved as FailedAndBlocked") {

      Given("a notification is eligible for retry")
      mockDateTimeService.travelBackInTime(retryDelayConfig.failedAndBlocked + 1.second)
      stubGetClientDataOk()
      stub(post)(Metrics, ACCEPTED)
      stub(post)(ExternalPush, INTERNAL_SERVER_ERROR)
      makeValidNotifyRequest()

      And("another notification for the same csid was just saved as FailedAndBlocked")
      mockDateTimeService.timeTravelToNow()
      makeValidNotifyRequest(toAssign = AnotherObjectId)

      When("a retry for FailedAndBlocked notifications is triggered")
      WireMock.resetAllRequests()
      retryService
        .retryFailedAndBlocked()
        .futureValue

      Then("it does not attempt to retry any notifications for that csid")
      verify(
        WireMock.exactly(0),
        postRequestedFor(urlMatching(PathFor.ExternalPush))
      )
    }
  }

  private def getDatabase(): Seq[WorkItem[Dto.NotificationWorkItem]] =
    repo.collection.find().toFuture().futureValue
}