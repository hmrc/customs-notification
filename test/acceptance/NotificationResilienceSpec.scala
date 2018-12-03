/*
 * Copyright 2018 HM Revenue & Customs
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

import java.util.UUID

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{Matchers, OptionValues}
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.repo.MongoDbProvider
import uk.gov.hmrc.mongo.{MongoSpecSupport, ReactiveRepository}
import util.TestData._
import util._

import scala.concurrent.ExecutionContext.Implicits.global

class NotificationResilienceSpec extends AcceptanceTestSpec
  with Matchers with OptionValues
  with ApiSubscriptionFieldsService with NotificationQueueService
  with PushNotificationService
  with GoogleAnalyticsSenderService
  with MongoSpecSupport {

  private val endpoint = "/customs-notification/notify"
  private val googleAnalyticsTrackingId: String = "UA-12345678-2"
  private val googleAnalyticsClientId: String = "555"
  private val googleAnalyticsEventValue = "10"

  val repo = new ReactiveRepository[ClientNotification, BSONObjectID](
    collectionName = "notifications",
    mongo = app.injector.instanceOf[MongoDbProvider].mongo,
    domainFormat = ClientNotification.clientNotificationJF) {


  }

  override implicit lazy val app: Application = new GuiceApplicationBuilder().configure(
    acceptanceTestConfigs +
      ("googleAnalytics.trackingId" -> googleAnalyticsTrackingId) +
      ("googleAnalytics.clientId" -> googleAnalyticsClientId) +
      ("push.polling.delay.duration.milliseconds" -> 2) +
      ("googleAnalytics.eventValue" -> googleAnalyticsEventValue)).build()


  private def callWasMadeToGoogleAnalyticsWith: (String, String) => Boolean =
    aCallWasMadeToGoogleAnalyticsWith(googleAnalyticsTrackingId, googleAnalyticsClientId, googleAnalyticsEventValue) _


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
      setupGoogleAnalyticsEndpoint()
      runNotificationQueueService(CREATED)

      repo.insert(ClientNotification(ClientSubscriptionId(UUID.fromString(validFieldsId)),
        Notification(ConversationId(UUID.fromString(pushNotificationRequest.body.conversationId)), pushNotificationRequest.body.outboundCallHeaders, ValidXML.toString(), "application/xml"), Some(TimeReceivedDateTime), Some(MetricsStartTimeDateTime)))

      And("the notification gateway service was called correctly")
      eventually(verifyPushNotificationServiceWasCalledWith(pushNotificationRequest))
      eventually(verifyNotificationQueueServiceWasNotCalled())
      eventually(verifyNoOfGoogleAnalyticsCallsMadeWere(1))

      callWasMadeToGoogleAnalyticsWith("notificationPushRequestSuccess",
        s"[ConversationId=$validConversationId] A notification has been pushed successfully") shouldBe true
    }

    scenario("when notifications are present in the database and push fails") {
      startApiSubscriptionFieldsService(validFieldsId, callbackData)
      setupPushNotificationServiceToReturn(404)
      setupGoogleAnalyticsEndpoint()
      runNotificationQueueService(CREATED)

      repo.insert(ClientNotification(ClientSubscriptionId(UUID.fromString(validFieldsId)),
        Notification(ConversationId(UUID.fromString(pushNotificationRequest.body.conversationId)), pushNotificationRequest.body.outboundCallHeaders, ValidXML.toString(), "application/xml"), Some(TimeReceivedDateTime), Some(MetricsStartTimeDateTime)))

      And("the notification gateway service was called correctly")
      eventually(verifyPushNotificationServiceWasCalledWith(pushNotificationRequest))
      eventually(verifyNotificationQueueServiceWasCalledWith(pushNotificationRequest))

      eventually(verifyNoOfGoogleAnalyticsCallsMadeWere(2))

      callWasMadeToGoogleAnalyticsWith("notificationPushRequestFailed",
        s"[ConversationId=$validConversationId] A notification Push request failed") shouldBe true

      callWasMadeToGoogleAnalyticsWith("notificationLeftToBePulled",
        s"[ConversationId=$validConversationId] A notification has been left to be pulled") shouldBe true
    }
  }
}
