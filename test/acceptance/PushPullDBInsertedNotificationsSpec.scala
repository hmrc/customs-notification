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
import java.util.concurrent.TimeUnit

import com.github.tomakehurst.wiremock.verification.LoggedRequest
import org.joda.time.DateTime
import org.scalatest.{Matchers, OptionValues}
import play.api.Application
import play.api.http.HeaderNames.{ACCEPT => _, CONTENT_TYPE => _}
import play.api.http.MimeTypes
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json.parse
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.{AnyContentAsXml, Result}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.repo.MongoDbProvider
import uk.gov.hmrc.mongo.{MongoSpecSupport, ReactiveRepository}
import util.TestData._
import util._

import scala.collection.mutable.ListBuffer
import scala.collection.{JavaConversions, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Random
import scala.xml.NodeSeq

class PushPullDBInsertedNotificationsSpec extends AcceptanceTestSpec
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
      //      ("push.polling.delay.duration.milliseconds" -> 2) +
      ("googleAnalytics.eventValue" -> googleAnalyticsEventValue)).build()


  private def callWasMadeToGoogleAnalyticsWith: (String, String) => Boolean =
    aCallWasMadeToGoogleAnalyticsWith(googleAnalyticsTrackingId, googleAnalyticsClientId, googleAnalyticsEventValue) _


  override protected def beforeAll() {
    startMockServer()
  }

  override protected def beforeEach(): Unit = {
    resetMockServer()
    await(repo.drop)

    setupPushNotificationServiceToReturn()
    setupGoogleAnalyticsEndpoint()
    //    runNotificationQueueService(CREATED)
    //    repo.insert(ClientNotification(ClientSubscriptionId(UUID.fromString(validFieldsId)),
    //      Notification(ConversationId(UUID.fromString(pushNotificationRequest.body.conversationId)), pushNotificationRequest.body.outboundCallHeaders, ValidXML.toString(), "application/xml")))

  }

  override protected def afterAll() {
    stopMockServer()
  }

  private def createRandomCSIDAlongWithRandomPushEnabledFlag(numbers: Int, ids: mutable.Set[(UUID, Boolean)]): mutable.Set[(UUID, Boolean)] = {
    if (numbers > 0) {
      ids.add(UUID.randomUUID(), Random.nextBoolean())
      createRandomCSIDAlongWithRandomPushEnabledFlag(numbers - 1, ids)
    }
    ids
  }

  private def fakeRequestToAPI(expectedCall: ExpectedCall): FakeRequest[AnyContentAsXml] = {
    val fixedHeaders: List[(String, String)] = List[(String, String)](
      X_CDS_CLIENT_ID_HEADER_NAME -> expectedCall.csid.toString,
      X_CONVERSATION_ID_HEADER_NAME -> expectedCall.conversationId.toString,
      RequestHeaders.CONTENT_TYPE_HEADER,
      RequestHeaders.ACCEPT_HEADER,
      RequestHeaders.BASIC_AUTH_HEADER) ::: expectedCall.maybeBadgeId.fold(List[(String, String)]())(x => List[(String, String)]((X_BADGE_ID_HEADER_NAME -> x)))

    lazy val requestToAPI: FakeRequest[AnyContentAsXml] = FakeRequest(method = POST, path = endpoint)
      .withHeaders(fixedHeaders: _*)
      .withXmlBody(expectedCall.xml)

    requestToAPI

  }

  private def makeACallToAPIAndMakeSureItReturns202(requestToAPI: FakeRequest[AnyContentAsXml]) = {
    val result: Option[Future[Result]] = route(app, requestToAPI)

    status(result.value) shouldBe ACCEPTED

  }

  case class ExpectedCall(csid: UUID, conversationId: UUID, callbackData: DeclarantCallbackData, maybeBadgeId: Option[String], xml: NodeSeq)

  feature("Ensure call to Push Pull are mode") {

    scenario("correctly") {

      val totalNotificationsToBeSent = 100

      val startTime = DateTime.now()
      val numberOfClientsToTest = 10

      val expectedPushNotificationsByCSID: mutable.Map[UUID, ListBuffer[ExpectedCall]] = mutable.Map()
      val expectedPullNotificationsByCSID: mutable.Map[UUID, ListBuffer[ExpectedCall]] = mutable.Map()
      val csidsWithPushEnabledFlag: List[(UUID, Boolean)] = createRandomCSIDAlongWithRandomPushEnabledFlag(numberOfClientsToTest, mutable.Set()).toList
      var totalPushRequestsExpected = 0
      var totalPullRequestsExpected = 0

      for (a <- 1 to totalNotificationsToBeSent) {

        val chosenRandomCSID = csidsWithPushEnabledFlag(Random.nextInt(numberOfClientsToTest))
        val shouldThisNotificationBePushed = chosenRandomCSID._2

        if (shouldThisNotificationBePushed) {
          makeCallToApiWithPushEnabled(expectedPushNotificationsByCSID, chosenRandomCSID)
          totalPushRequestsExpected += 1
        } else {
          makeCallToAPIWithPushDisabled(expectedPullNotificationsByCSID, chosenRandomCSID)
          totalPullRequestsExpected += 1
        }
      }

      Then(s"totalPushRequestsExpected = $totalPushRequestsExpected, totalPullRequestsExpected = $totalPullRequestsExpected")
      eventually(allCallsMadeToClientsPushService().size() should be(totalPushRequestsExpected))
      eventually(allCallsMadeToPullQ().size()) should be(totalPullRequestsExpected)
      val sentNotificationFinishedAt = DateTime.now()
      val allPushedNotificationsByCSID = allSuccessfullyPushedNotificationsByCSID()
      expectedPushNotificationsByCSID.size shouldBe allPushedNotificationsByCSID.size

      expectedPushNotificationsByCSID.foreach[Unit] { expectedClientRecord: (UUID, ListBuffer[ExpectedCall]) =>
        val expectedRequestsThisCSID = expectedClientRecord._2.toList
        val actualRequestsMadeForThisCSID = allPushedNotificationsByCSID.get(expectedClientRecord._1).get.toList

        makeSureActualNotificationsRcvdWereAsExpected(expectedRequestsThisCSID, actualRequestsMadeForThisCSID)

        Then(s"client ${expectedClientRecord._1} made total ${actualRequestsMadeForThisCSID.size} calls, expected were ${expectedRequestsThisCSID.size}")
      }

      val allPullNotificationsByCSID = allSuccessfullySentToPullNotificationsByCSID()
      expectedPullNotificationsByCSID.size shouldBe allPullNotificationsByCSID.size

      expectedPullNotificationsByCSID.foreach[Unit] { expectedClientRecord: (UUID, ListBuffer[ExpectedCall]) =>
        val expectedRequestsMadeForThisCSID = expectedClientRecord._2.toList
        val actualRequestsMadeForThisCSID = allPullNotificationsByCSID.get(expectedClientRecord._1).get.toList

        makeSureActualPullQRequestMadeWereAsExpected(expectedRequestsMadeForThisCSID, actualRequestsMadeForThisCSID)

        Then(s"client ${expectedClientRecord._1} made total ${actualRequestsMadeForThisCSID.size} calls, expected were ${expectedRequestsMadeForThisCSID.size}")
      }

      val totalTimeTaken = Duration(DateTime.now().getMillis - startTime.getMillis, TimeUnit.MILLISECONDS)
      val totalNotificationProcessed = totalPushRequestsExpected + totalPullRequestsExpected
      val notificationProcessingTimeInMillis = sentNotificationFinishedAt.getMillis - startTime.getMillis
      val notificationsProcessedInOneSecond = (totalNotificationProcessed.toFloat/notificationProcessingTimeInMillis) * 1000
      Then(s"Total push request were $totalPushRequestsExpected, pull requests were $totalPullRequestsExpected, totalTimeTaken = $notificationProcessingTimeInMillis, notificationsPerSecond=$notificationsProcessedInOneSecond")
//      notificationsProcessedInOneSecond shouldBe > (70f)
    }
  }

  def makeCallToAPIWithPushDisabled(expectedPullNotificationsByCSID: mutable.Map[UUID, ListBuffer[ExpectedCall]], chosenRandomCSID: (UUID, Boolean)): Future[Unit] = {
    Future.successful {
      val expectedCall = createARandomExpectedCallFor(chosenRandomCSID._1)
      startApiSubscriptionFieldsService(expectedCall.csid.toString, DeclarantCallbackData("", ""))
      runNotificationQueueService(CREATED)
      makeACallToAPIAndMakeSureItReturns202(fakeRequestToAPI(expectedCall))
      addExpectedCallTo(expectedPullNotificationsByCSID, expectedCall)
    }
  }

  def makeCallToApiWithPushEnabled(expectedPushNotificationsByCSID: mutable.Map[UUID, ListBuffer[ExpectedCall]], chosenRandomCSID: (UUID, Boolean)): Future[Unit] = {
    Future.successful {
      val expectedCall = createARandomExpectedCallFor(chosenRandomCSID._1)
      startApiSubscriptionFieldsService(expectedCall.csid.toString, expectedCall.callbackData)
      setupPushNotificationServiceToReturn()
      makeACallToAPIAndMakeSureItReturns202(fakeRequestToAPI(expectedCall))
      addExpectedCallTo(expectedPushNotificationsByCSID, expectedCall)
    }
  }

  def makeSureActualNotificationsRcvdWereAsExpected(expectedRequestsMadeForThisClient: List[ExpectedCall], actualRequestsMadeForThisClient: List[LoggedRequest]): Unit = {
    actualRequestsMadeForThisClient.size shouldBe expectedRequestsMadeForThisClient.size

    for (counter <- 0 to expectedRequestsMadeForThisClient.size - 1) {

      val expectedRequest = expectedRequestsMadeForThisClient(counter)
      val actualRequest = actualRequestsMadeForThisClient(counter)

      actualRequest.getAllHeaderKeys.toArray() shouldBe Array(
        "X-Request-Chain",
        "Accept",
        "User-Agent",
        "Host",
        "Content-Length",
        "Content-Type")

      actualRequest.getHeader(ACCEPT) should be(MimeTypes.JSON)
      actualRequest.getHeader(CONTENT_TYPE) should be(MimeTypes.JSON)

      parse(actualRequest.getBodyAsString) should be(

        createPushNotificationRequestPayload(
          mayBeBadgeId = expectedRequest.maybeBadgeId,
          outboundUrl = expectedRequest.callbackData.callbackUrl,
          securityToken = expectedRequest.callbackData.securityToken,
          notificationPayload = expectedRequest.xml,
          conversationId = expectedRequest.conversationId.toString)

      )
    }
  }

    def makeSureActualPullQRequestMadeWereAsExpected(expectedPullQRequestsMadeForThisClient: List[ExpectedCall], actualRequestsMadeToPullQForThisClient: List[LoggedRequest]): Unit = {
      actualRequestsMadeToPullQForThisClient.size shouldBe expectedPullQRequestsMadeForThisClient.size

      for (counter <- 0 to expectedPullQRequestsMadeForThisClient.size - 1) {

        val expectedRequest = expectedPullQRequestsMadeForThisClient(counter)
        val actualRequest = actualRequestsMadeToPullQForThisClient(counter)


        if (expectedRequest.maybeBadgeId.isDefined) {
          actualRequest.getAllHeaderKeys.toArray() shouldBe Array("X-Request-Chain", "Accept", "X-Badge-Identifier", "User-Agent", "api-subscription-fields-id", "Host", "X-Conversation-ID", "Content-Length", "Content-Type")
        } else {
          actualRequest.getAllHeaderKeys.toArray() shouldBe Array("X-Request-Chain", "Accept", "User-Agent", "api-subscription-fields-id", "Host", "X-Conversation-ID", "Content-Length", "Content-Type")
        }

        actualRequest.getHeader(ACCEPT) should be("*/*")
        actualRequest.getHeader(CONTENT_TYPE) should be(MimeTypes.XML)
        actualRequest.getHeader("api-subscription-fields-id") should be(expectedRequest.csid.toString)
        actualRequest.getHeader("X-Conversation-ID") should be(expectedRequest.conversationId.toString)

        expectedRequest.maybeBadgeId.fold[Unit]()(actualRequest.getHeader("X-Badge-Identifier") should be(_))
        actualRequest.getBodyAsString should be(expectedRequest.xml.toString())
      }
    }


    def allSuccessfullyPushedNotificationsByCSID(): mutable.Map[UUID, ListBuffer[LoggedRequest]] = {
      val allPushedCallsByCSID = mutable.Map[UUID, ListBuffer[LoggedRequest]]()

      JavaConversions.asScalaBuffer(allCallsMadeToClientsPushService()).foreach { loggedRequest =>
        val authToken = Json.parse(loggedRequest.getBodyAsString).as[JsObject].value.get("authHeaderToken").get.toString()
        val csid = UUID.fromString(authToken.substring(6, 42))

        allPushedCallsByCSID.get(csid).fold[Unit](allPushedCallsByCSID += (csid -> ListBuffer(loggedRequest)))(_ += loggedRequest)
      }
      allPushedCallsByCSID
    }

    def allSuccessfullySentToPullNotificationsByCSID(): mutable.Map[UUID, ListBuffer[LoggedRequest]] = {
      val notificationsByCSID = mutable.Map[UUID, ListBuffer[LoggedRequest]]()

      JavaConversions.asScalaBuffer(allCallsMadeToPullQ()).foreach { loggedRequest =>
        val csid = UUID.fromString(loggedRequest.getHeader(CustomHeaderNames.SUBSCRIPTION_FIELDS_ID_HEADER_NAME))
        notificationsByCSID.get(csid).fold[Unit](notificationsByCSID += (csid -> ListBuffer(loggedRequest)))(_ += loggedRequest)
      }
      notificationsByCSID
    }

    def createARandomExpectedCallFor(chosenRandomCSID: UUID): ExpectedCall = {
      val maybeBadgeId = if (Random.nextBoolean()) Some("ABC" + Random.nextInt(100)) else None
      val conversationId = UUID.randomUUID()
      val notificationXML = <declaration>Some declaration for {chosenRandomCSID.toString}</declaration>
      val callbackData = DeclarantCallbackData(s"http://client-url/$chosenRandomCSID/service", s"auth-$chosenRandomCSID")
      ExpectedCall(chosenRandomCSID, conversationId, callbackData, maybeBadgeId, notificationXML)
    }

    def addExpectedCallTo(pushExpectation: mutable.Map[UUID, ListBuffer[ExpectedCall]], expectedCall: ExpectedCall): Unit = {
      pushExpectation.get(expectedCall.csid).fold[Unit](pushExpectation += (expectedCall.csid -> ListBuffer(expectedCall)))(_ += expectedCall)
    }
  }
