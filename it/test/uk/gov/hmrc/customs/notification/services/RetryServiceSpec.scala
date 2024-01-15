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
import uk.gov.hmrc.customs.notification.IntegrationSpecBase
import uk.gov.hmrc.customs.notification.config.RetryDelayConfig
import uk.gov.hmrc.customs.notification.models.ProcessingStatus.*
import uk.gov.hmrc.customs.notification.repositories.NotificationRepository.Dto
import uk.gov.hmrc.customs.notification.util.*
import uk.gov.hmrc.customs.notification.util.IntegrationTestHelpers.*
import uk.gov.hmrc.customs.notification.util.IntegrationTestHelpers.PathFor.*
import uk.gov.hmrc.customs.notification.util.TestData.*
import uk.gov.hmrc.customs.notification.util.TestData.Implicits.LogContext
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
  with RepositoriesFixture
  with Matchers
  with GivenWhenThen {

  private lazy val retryDelayConfig = app.injector.instanceOf[RetryDelayConfig]
  private lazy val retryService = app.injector.instanceOf[RetryService]

  Feature("retryFailedButNotBlocked") {
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

      Then("it should successfully retry only the outstanding notifications")
      val expectedStatusesByObjectId =
        List(
          ObjectId -> Succeeded.internalStatus,
          AnotherObjectId -> Succeeded.internalStatus,
          YetAnotherObjectId -> FailedButNotBlocked.internalStatus
        )
      val db = getDatabase()
      db.map(w => w.id -> w.status) should contain theSameElementsAs expectedStatusesByObjectId
    }

  }

  Feature("retryFailedAndBlocked") {

    Scenario("Retries with existing FailedAndBlocked notifications") {

      Given("two blocked (and available to retry) csids with two FailedAndBlocked notifications for each one (four in total)")
      mockDateTimeService.travelBackInTime(retryDelayConfig.failedAndBlocked + 1.second)
      stubGetClientDataOk()
      stub(get)(clientDataWithCsid(AnotherCsid), OK, stubClientDataResponseBody(csid = AnotherCsid))
      stub(post)(Metrics, ACCEPTED)
      stub(post)(ExternalPush, INTERNAL_SERVER_ERROR)
      makeValidNotifyRequest()
      makeValidNotifyRequest(toAssign = AnotherObjectId)
      makeValidNotifyRequest(csid = AnotherCsid, toAssign = YetAnotherObjectId)
      makeValidNotifyRequest(csid = AnotherCsid, toAssign = FourthObjectId)

      And("a blocked (and unavailable to retry) csid with a FailedAndBlock notification")
      mockDateTimeService.travelForwardsInTime(2.seconds)
      stub(get)(clientDataWithCsid(YetAnotherCsid), OK, stubClientDataResponseBody(csid = YetAnotherCsid))
      makeValidNotifyRequest(csid = YetAnotherCsid, toAssign = FifthObjectId)

      And("the client callback service is working")
      stub(post)(ExternalPush, ACCEPTED)

      When("a retry for FailedAndBlocked notifications is triggered")
      mockDateTimeService.timeTravelToNow()
      WireMock.resetAllRequests()
      retryService
        .retryFailedAndBlocked()
        .futureValue

      Then("it should retry only one notification per csid that is available to unblock")
      verify(
        WireMock.exactly(2),
        validExternalPushRequest()
      )

      And("set the rest to FailedButNotBlocked")
      val expectedStatusByCsid =
        List(
          TranslatedCsid -> Succeeded.internalStatus,
          TranslatedCsid -> FailedButNotBlocked.internalStatus,
          AnotherCsid -> Succeeded.internalStatus,
          AnotherCsid -> FailedButNotBlocked.internalStatus
        )
      val db = getDatabase()
      val actualStatusByCsid = db.map(w => w.item._id -> w.status)
      actualStatusByCsid should contain allElementsOf expectedStatusByCsid

      And("not update the notification that is unavailable to try")
      val stillBlockedStatusByCsid = List(
        YetAnotherCsid -> FailedAndBlocked.internalStatus
      )
      actualStatusByCsid should contain allElementsOf stillBlockedStatusByCsid

      And("a retry that happens immediately afterwards should not attempt anything")
      WireMock.resetAllRequests()
      retryService
        .retryFailedAndBlocked()
        .futureValue
      verify(
        WireMock.exactly(0),
        validExternalPushRequest()
      )
    }

    Scenario("Retries again when the client callback service is down") {

      Given("there is a blocked csid with a FailedAndBlocked notification")
      mockDateTimeService.travelBackInTime(retryDelayConfig.failedAndBlocked + 1.second)
      stubGetClientDataOk()
      stub(post)(Metrics, ACCEPTED)
      stub(post)(ExternalPush, INTERNAL_SERVER_ERROR)
      makeValidNotifyRequest()

      And("a retry was attempted but the client callback service was still down")
      mockDateTimeService.timeTravelToNow()
      retryService
        .retryFailedAndBlocked()
        .futureValue

      When("another retry is attempted again before it is available for retry")
      WireMock.resetAllRequests()
      retryService
        .retryFailedAndBlocked()
        .futureValue

      Then("it should not attempt any calls to the client callback service")
      verify(
        WireMock.exactly(0),
        validExternalPushRequest()
      )

    }
  }

  private def getDatabase(): Seq[WorkItem[Dto.NotificationWorkItem]] =
    notificationRepo.underlying.collection.find().toFuture().futureValue
}