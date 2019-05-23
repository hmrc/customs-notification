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

import com.github.tomakehurst.wiremock.client.WireMock.{postRequestedFor, urlMatching, verify}
import org.joda.time.DateTime
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.test.Helpers._
import play.api.{Application, Configuration}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.repo.WorkItemFormat
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers._
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.workitem.{WorkItemFieldNames, WorkItemRepository}
import util.ExternalServicesConfiguration.{Host, Port}
import util.TestData._
import util._

class CustomsNotificationTxmSpec extends AcceptanceTestSpec
  with ApiSubscriptionFieldsService
  with NotificationQueueService
  with PushNotificationService
  with InternalPushNotificationService
  with MongoSpecSupport
  with AuditService {

  private val repo: WorkItemRepository[NotificationWorkItem, BSONObjectID] = new WorkItemRepository[NotificationWorkItem, BSONObjectID](
    collectionName = "notifications-work-item",
    mongo = app.injector.instanceOf[ReactiveMongoComponent].mongoConnector.db,
    itemFormat = WorkItemFormat.workItemMongoFormat[NotificationWorkItem],
    config = Configuration().underlying) {

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

  override implicit lazy val app: Application = new GuiceApplicationBuilder().configure(
    acceptanceTestConfigs +
      ("push.internal.clientIds.0" -> "aThirdPartyApplicationId") +
      ("auditing.enabled" -> "true") +
      ("auditing.consumer.baseUri.host" -> Host) +
      ("auditing.consumer.baseUri.port" -> Port) +
      ("customs-notification-metrics.host" -> Host) +
      ("customs-notification-metrics.port" -> Port)
  ).build()

  override protected def beforeAll() {
    startMockServer()
  }

  override protected def beforeEach(): Unit = {
    resetMockServer()
    await(repo.drop)
  }

  override protected def afterAll() {
    stopMockServer()
    await(repo.drop)
  }


  feature("Ensure Audit Service is made when call to callback endpoint are made internally (ie bypass the gateway)") {

    scenario("when notifications are present in the database") {
      startApiSubscriptionFieldsService(validFieldsId, internalCallbackData)
      setupInternalServiceToReturn()
      setupAuditServiceToReturn(NO_CONTENT)
      runNotificationQueueService(CREATED)

      repo.insert(internalWorkItem)

      And("the callback endpoint was called internally, bypassing the gateway")
      
      eventually {
        verifyInternalServiceWasCalledWith(internalPushNotificationRequest)
        verifyPushNotificationServiceWasNotCalled()
        verifyNotificationQueueServiceWasNotCalled()
      }

      And("A call is made to the audit service")
      eventually(verify(1, postRequestedFor(urlMatching("/write/audit"))))

    }
  }
}
