/*
 * Copyright 2020 HM Revenue & Customs
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

import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemMongoRepo
import uk.gov.hmrc.mongo.MongoSpecSupport
import util.ExternalServicesConfiguration.{Host, Port}
import util.TestData._
import util._

class InternalNotificationSpec extends ComponentTestSpec
  with ApiSubscriptionFieldsService
  with NotificationQueueService
  with PushNotificationService
  with InternalPushNotificationService
  with MongoSpecSupport
  with AuditService {

  private implicit val ec = Helpers.stubControllerComponents().executionContext
  private lazy val repo = app.injector.instanceOf[NotificationWorkItemMongoRepo]
  
  override implicit lazy val app: Application = new GuiceApplicationBuilder().configure(
    acceptanceTestConfigs +
      ("internal.clientIds.0" -> "aThirdPartyApplicationId") +
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

  feature("Ensure call to callback endpoint are made internally (ie bypass the gateway)") {

    scenario("when notifications are present in the database") {
      startApiSubscriptionFieldsService(validFieldsId, internalCallbackData)
      setupInternalServiceToReturn()
      stubAuditService()
      runNotificationQueueService(CREATED)

      repo.insert(internalWorkItem)
      
      And("the callback endpoint was called internally, bypassing the gateway")
      eventually {
        verifyInternalServiceWasCalledWith(internalPushNotificationRequest)
        verifyPushNotificationServiceWasNotCalled()
        verifyNotificationQueueServiceWasNotCalled()
        verifyAuditWrite()
      }
    }
  }
}
