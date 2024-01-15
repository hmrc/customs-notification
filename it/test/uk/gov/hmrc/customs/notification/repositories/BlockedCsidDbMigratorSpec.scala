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

package uk.gov.hmrc.customs.notification.repositories

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import org.scalatest.LoneElement.convertToCollectionLoneElementWrapper
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{DoNotDiscover, GivenWhenThen, Suite}
import org.scalatestplus.play.ConfiguredServer
import play.api.http.Status.*
import uk.gov.hmrc.customs.notification.IntegrationSpecBase
import uk.gov.hmrc.customs.notification.models.ProcessingStatus.FailedAndBlocked
import uk.gov.hmrc.customs.notification.repositories.BlockedCsidRepository.BlockedCsid
import uk.gov.hmrc.customs.notification.repositories.NotificationRepository.Mapping
import uk.gov.hmrc.customs.notification.util.*
import uk.gov.hmrc.customs.notification.util.IntegrationTestHelpers.*
import uk.gov.hmrc.customs.notification.util.IntegrationTestHelpers.PathFor.*
import uk.gov.hmrc.customs.notification.util.TestData.*

import scala.concurrent.duration.DurationDouble
import scala.language.postfixOps

@DoNotDiscover
private class TestOnlyBlockedCsidDbMigratorSpec extends IntegrationSpecBase {
  override def nestedSuites: IndexedSeq[Suite] =
    Vector(new BlockedCsidDbMigratorSpec(wireMockServer, mockDateTimeService, mockObjectIdService))
}

class BlockedCsidDbMigratorSpec(protected val wireMockServer: WireMockServer,
                                protected val mockDateTimeService: MockDateTimeService,
                                protected val mockObjectIdService: MockObjectIdService) extends AnyFeatureSpec
  with ConfiguredServer
  with WireMockHelpers
  with WsClientHelpers
  with RepositoriesFixture
  with Matchers
  with GivenWhenThen {

  lazy val migrator: BlockedCsidDbMigrator = app.injector.instanceOf[BlockedCsidDbMigrator]

  Feature("Blocked Client Subscription ID Migrator") {
    Scenario("Migration is triggered for an un-migrated database") {

      Given("there are two FailedButNotBlocked notifications for another csid that is not migrated")
      val firstNotification = Mapping.domainToRepo(
        notification = Notification.copy(id = AnotherObjectId, csid = AnotherCsid),
        status = FailedAndBlocked,
        updatedAt = TimeNow.toInstant,
        failureCount = 0
      )

      val laterNotification = Mapping.domainToRepo(
        notification = Notification.copy(id = YetAnotherObjectId, csid = AnotherCsid),
        status = FailedAndBlocked,
        updatedAt = TimeNow.plusSeconds(1).toInstant,
        failureCount = 0
      )

      notificationRepo.underlying.collection.insertMany(List(
        firstNotification, laterNotification
      )).toFuture().futureValue

      And("there is already one migrated FailedAndBlocked notification (csid is saved as blocked)")
      stubGetClientDataOk()
      stub(post)(Metrics, ACCEPTED)
      stub(post)(ExternalPush, INTERNAL_SERVER_ERROR)
      makeValidNotifyRequest()
      val someAvailableAt = TimeNow.plusSeconds((2.5 minutes).toSeconds)
      val expectedInitialBlockedCsid = BlockedCsid(
        csid = TranslatedCsid,
        createdAt = TimeNow.toInstant,
        availableAt = someAvailableAt.toInstant,
        clientId = ClientId,
        notificationId = ObjectId,
        retryAttempted = false
      )
      val actualInitialBlockedCsid = blockedCsidRepo.collection.find().toFuture().futureValue.loneElement
      actualInitialBlockedCsid shouldBe expectedInitialBlockedCsid

      When("a migration is triggered")
      val migratedCount = migrator.migrate().futureValue

      Then("un-migrated CSID should be migrated with the later availableAt")
      migratedCount shouldBe 1
      val failedAndBlockedDelayInSeconds = 150 // defined in IntegrationSpecBase
      val expectedMigrated =
        BlockedCsid(
          csid = AnotherCsid,
          createdAt = TimeNow.toInstant,
          availableAt = TimeNow.plusSeconds(failedAndBlockedDelayInSeconds).toInstant,
          clientId = ClientId,
          notificationId = YetAnotherObjectId,
          retryAttempted = false
        )

      val actualBlockedCsidRepo = blockedCsidRepo.collection.find().toFuture().futureValue
      actualBlockedCsidRepo should contain(expectedMigrated)

      And("there is only one migrated csid along with the prior csid")
      actualBlockedCsidRepo should have size 2

      And("the prior migrated csid is unchanged")
      actualBlockedCsidRepo should contain(actualInitialBlockedCsid)
    }
  }
}