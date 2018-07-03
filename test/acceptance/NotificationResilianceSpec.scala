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
import java.util.UUID.randomUUID

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{Matchers, OptionValues}
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc._
import play.api.test.Helpers._
import reactivemongo.api.FailoverStrategy
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.mongo.{MongoSpecSupport, ReactiveRepository}
import util.TestData._
import util._

import scala.concurrent.Future
import scala.xml.NodeSeq
import scala.xml.Utility.trim
import scala.xml.XML.loadString
import scala.concurrent.ExecutionContext.Implicits.global

class NotificationResilianceSpec extends AcceptanceTestSpec
  with Matchers with OptionValues
  with ApiSubscriptionFieldsService with NotificationQueueService with TableDrivenPropertyChecks
  with PublicNotificationService
  with GoogleAnalyticsSenderService
  with MongoSpecSupport
{

  private val endpoint = "/customs-notification/notify"
  private val googleAnalyticsTrackingId: String = "UA-43414424-2"
  private val googleAnalyticsClientId: String = "555"
  private val googleAnalyticsEventValue = "10"

  val repo = new ReactiveRepository[ClientNotification, BSONObjectID](
    collectionName = "notifications",
    mongo = mongo,
    domainFormat = ClientNotification.clientNotificationJF) {


  }

  override implicit lazy val app: Application = new GuiceApplicationBuilder().configure(
    acceptanceTestConfigs +
      ("googleAnalytics.trackingId" -> googleAnalyticsTrackingId) +
      ("googleAnalytics.clientId" -> googleAnalyticsClientId) +
      ("googleAnalytics.eventValue" -> googleAnalyticsEventValue)).build()


  private def callWasMadeToGoogleAnalyticsWith: (String, String) => Boolean =
    aCallWasMadeToGoogleAnalyticsWith(googleAnalyticsTrackingId, googleAnalyticsClientId, googleAnalyticsEventValue) _


  override protected def beforeAll() {
    startMockServer()
    setupPublicNotificationServiceToReturn()
  }

  override protected def beforeEach(): Unit = {
    await(repo.drop)
  }

  override protected def afterAll() {
    stopMockServer()
  }

  override protected def afterEach(): Unit = {
    resetMockServer()
  }

  feature("Ensure call to customs notification gateway are made") {
    // TODO once code is in place
    ignore("when notifications are present in the database") {

      repo.insert(ClientNotification(ClientSubscriptionId(UUID.fromString(validFieldsId)),
        Notification(ConversationId(validConversationIdUUID), Seq[Header](), ValidXML.toString(), "application/xml")))

      startApiSubscriptionFieldsService(validFieldsId, callbackData)
      setupGoogleAnalyticsEndpoint()
      runNotificationQueueService(CREATED)


      And("the notification gateway service was called correctly")
      eventually(verifyPublicNotificationServiceWasCalledWith(createPushNotificationRequestPayload()))
      eventually(verifyNotificationQueueServiceWasNotCalled())
      eventually(verifyNoOfGoogleAnalyticsCallsMadeWere(2))

      callWasMadeToGoogleAnalyticsWith("notificationPushRequestSuccess",
        s"[ConversationId=$validConversationId] A notification has been pushed successfully") shouldBe true
    }
    // TODO once code is in place
    ignore("when notifications are present in the database and push fails") {

      repo.insert(ClientNotification(ClientSubscriptionId(UUID.fromString(validFieldsId)),
        Notification(ConversationId(validConversationIdUUID), Seq[Header](), ValidXML.toString(), "application/xml")))

      startApiSubscriptionFieldsService(validFieldsId, callbackData)
      setupPublicNotificationServiceToReturn(404)
      setupGoogleAnalyticsEndpoint()

      And("the notification gateway service was called correctly")

      eventually(verifyPublicNotificationServiceWasCalledWith(createPushNotificationRequestPayload()))
      eventually(verifyNotificationQueueServiceWasCalledWith(
        PublicNotificationRequest(validFieldsId,
        PublicNotificationRequestBody(callbackUrl,
          basicAuthTokenValue,
          validConversationId,
          Seq[Header](),
          ValidXML.toString()))))

      eventually(verifyNoOfGoogleAnalyticsCallsMadeWere(2))

      callWasMadeToGoogleAnalyticsWith("notificationPushRequestFailed",
        s"[ConversationId=$validConversationId] A notification Push request failed") shouldBe true

      callWasMadeToGoogleAnalyticsWith("notificationLeftToBePulled",
        s"[ConversationId=$validConversationId] A notification has been left to be pulled") shouldBe true
    }
  }
}
