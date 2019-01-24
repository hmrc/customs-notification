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

import java.util.UUID

import com.github.tomakehurst.wiremock.client.WireMock.{postRequestedFor, urlMatching, verify}
import org.scalatest.{Matchers, OptionValues}
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.repo.MongoDbProvider
import uk.gov.hmrc.mongo.{MongoSpecSupport, ReactiveRepository}
import util.ExternalServicesConfiguration.{Host, Port}
import util.TestData._
import util._

import scala.concurrent.ExecutionContext.Implicits.global

class CustomsNotificationTxmSpec extends AcceptanceTestSpec
  with Matchers with OptionValues
  with ApiSubscriptionFieldsService with NotificationQueueService
  with PushNotificationService
  with InternalPushNotificationService
  with GoogleAnalyticsSenderService
  with MongoSpecSupport
  with AuditService {

  private val googleAnalyticsTrackingId: String = "UA-12345678-2"
  private val googleAnalyticsClientId: String = "555"
  private val googleAnalyticsEventValue = "10"

  val repo: ReactiveRepository[ClientNotification, BSONObjectID] = new ReactiveRepository[ClientNotification, BSONObjectID](
    collectionName = "notifications",
    mongo = app.injector.instanceOf[MongoDbProvider].mongo,
    domainFormat = ClientNotification.clientNotificationJF) {
  }

  override implicit lazy val app: Application = new GuiceApplicationBuilder().configure(
    acceptanceTestConfigs +
      ("googleAnalytics.trackingId" -> googleAnalyticsTrackingId) +
      ("googleAnalytics.clientId" -> googleAnalyticsClientId) +
      ("push.polling.delay.duration.milliseconds" -> 2) +
      ("googleAnalytics.eventValue" -> googleAnalyticsEventValue) +
      ("push.internal.clientIds.0" -> "aThirdPartyApplicationId") +
      ("auditing.enabled" -> "true") +
      ("auditing.consumer.baseUri.host" -> Host) +
      ("auditing.consumer.baseUri.port" -> Port) +
      ("customs-notification-metrics.host" -> Host) +
      ("customs-notification-metrics.port" -> Port)
  ).build()


  private def callWasMadeToGoogleAnalyticsWith: (String, String) => Boolean =
    aCallWasMadeToGoogleAnalyticsWith(googleAnalyticsTrackingId, googleAnalyticsClientId, googleAnalyticsEventValue)


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


  feature("Ensure Audit Service is made when call to callback endpoint are made internally (ie bypass the gateway)") {

    scenario("when notifications are present in the database") {
      startApiSubscriptionFieldsService(validFieldsId, internalCallbackData)
      setupInternalServiceToReturn()
      setupGoogleAnalyticsEndpoint()
      setupAuditServiceToReturn(NO_CONTENT)
      runNotificationQueueService(CREATED)

      repo.insert(ClientNotification(ClientSubscriptionId(UUID.fromString(validFieldsId)),
        Notification(ConversationId(UUID.fromString(internalPushNotificationRequest.body.conversationId)), internalPushNotificationRequest.body.outboundCallHeaders, ValidXML.toString(), "application/xml"), Some(TimeReceivedDateTime), Some(MetricsStartTimeDateTime)))

      And("the callback endpoint was called internally, bypassing the gateway")
      eventually(verifyInternalServiceWasCalledWith(internalPushNotificationRequest))
      eventually(verifyPushNotificationServiceWasNotCalled())
      eventually(verifyNotificationQueueServiceWasNotCalled())
      eventually(verifyNoOfGoogleAnalyticsCallsMadeWere(1))

      callWasMadeToGoogleAnalyticsWith("notificationPushRequestSuccess",
        s"[ConversationId=$validConversationId] A notification has been pushed successfully") shouldBe true
;
      And("A call is made to the audit service")
      verify(1, postRequestedFor(urlMatching("/write/audit")))

    }
  }
}
