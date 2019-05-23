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
import play.api.Configuration
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.test.Helpers._
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.repo.WorkItemFormat
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers._
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.workitem.{WorkItemFieldNames, WorkItemRepository}
import util.TestData._
import util._


class NotificationResilienceSpec extends AcceptanceTestSpec
  with ApiSubscriptionFieldsService
  with NotificationQueueService
  with InternalPushNotificationService
  with PushNotificationService
  with MongoSpecSupport {

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

  override protected def beforeAll() {
    startMockServer()
  }

  override protected def beforeEach(): Unit = {
    resetMockServer()
    await(repo.drop)
  }

  override protected def afterAll() {
    stopMockServer()
  }

  feature("Ensure call to customs notification gateway are made") {

    scenario("when notifications are present in the database") {
      startApiSubscriptionFieldsService(validFieldsId, callbackData)
      setupPushNotificationServiceToReturn()
      runNotificationQueueService(CREATED)

      repo.insert(internalWorkItem)

      And("the notification gateway service was called correctly")
      eventually {
        verifyPushNotificationServiceWasCalledWith(pushNotificationRequest)
        verifyInternalServiceWasNotCalledWith(pushNotificationRequest)
        verifyNotificationQueueServiceWasNotCalled()
      }
    }
  }
}
