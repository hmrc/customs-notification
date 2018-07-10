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
import java.util.concurrent.atomic.AtomicInteger

import org.joda.time.DateTime
import org.scalatest.{Matchers, OptionValues}
import play.api.Application
import play.api.http.HeaderNames.{ACCEPT => _, CONTENT_TYPE => _}
import play.api.http.MimeTypes
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json.parse
import play.api.mvc.AnyContentAsXml
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.repo.ClientNotificationMongoRepo
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import util.TestData._
import util._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Random

class PushPullDBInsertedNotificationsSpec extends AcceptanceTestSpec
  with Matchers with OptionValues
  with ApiSubscriptionFieldsService
  with GoogleAnalyticsSenderService
  with StubForPushService
  with StubForPullService
  with MongoSpecSupport {

  private val endpoint = "/customs-notification/notify"
  private val googleAnalyticsTrackingId: String = "UA-12345678-2"
  private val googleAnalyticsClientId: String = "555"
  private val googleAnalyticsEventValue = "10"

  private val totalNotificationsToBeSent = 1000
  private val numberOfClientsToTest = 10

  val notificationRepo = app.injector.instanceOf[ClientNotificationMongoRepo]

  override implicit lazy val app: Application = new GuiceApplicationBuilder().configure(
    acceptanceTestConfigs +
      ("googleAnalytics.trackingId" -> googleAnalyticsTrackingId) +
      ("googleAnalytics.clientId" -> googleAnalyticsClientId) +
      //      ("push.polling.delay.duration.milliseconds" -> 2) +
      ("googleAnalytics.eventValue" -> googleAnalyticsEventValue)).build()

  private val httpClient = app.injector.instanceOf[HttpClient]

  private def callWasMadeToGoogleAnalyticsWith: (String, String) => Boolean =
    aCallWasMadeToGoogleAnalyticsWith(googleAnalyticsTrackingId, googleAnalyticsClientId, googleAnalyticsEventValue) _


  override protected def beforeAll() {
    startMockServer()
  }

  override protected def beforeEach(): Unit = {
    resetMockServer()
    await(notificationRepo.drop(scala.concurrent.ExecutionContext.Implicits.global))
    runNotificationQueueService()
    setupPushNotificationServiceToReturn()
    setupGoogleAnalyticsEndpoint()
  }

  override protected def afterAll() {
    stopMockServer()
  }

  private def createPoolOfPushEnabledDisabledClients(numbers: Int, clients: mutable.Set[Client]): mutable.Set[Client] = {
    if (numbers > 0) {
      val randomCSID = UUID.randomUUID()
      val isPushEnabled = Random.nextBoolean()
      var callbackData = DeclarantCallbackData(s"http://client-url/$randomCSID/service", s"auth-$randomCSID")
      if (isPushEnabled) {
        startApiSubscriptionFieldsService(randomCSID.toString(), callbackData)
      } else {
        callbackData = DeclarantCallbackData("", "")
        startApiSubscriptionFieldsService(randomCSID.toString(), callbackData)
      }
      clients.add(Client(randomCSID, isPushEnabled, callbackData))
      createPoolOfPushEnabledDisabledClients(numbers - 1, clients)
    }
    clients
  }

  private def fakeRequestToAPI(expectedCall: ExpectedCall): FakeRequest[AnyContentAsXml] = {
    val fixedHeaders: List[(String, String)] = List[(String, String)](
      X_CDS_CLIENT_ID_HEADER_NAME -> expectedCall.client.csid.toString,
      X_CONVERSATION_ID_HEADER_NAME -> expectedCall.conversationId.toString,
      RequestHeaders.CONTENT_TYPE_HEADER,
      RequestHeaders.ACCEPT_HEADER,
      RequestHeaders.BASIC_AUTH_HEADER) ::: expectedCall.maybeBadgeId.fold(List[(String, String)]())(x => List[(String, String)]((X_BADGE_ID_HEADER_NAME -> x)))

    lazy val requestToAPI: FakeRequest[AnyContentAsXml] = FakeRequest(method = POST, path = endpoint)
      .withHeaders(fixedHeaders: _*)
      .withXmlBody(expectedCall.xml)

    requestToAPI

  }

  private def callAPIAndMakeSureItReturns202(requestToAPI: FakeRequest[AnyContentAsXml]) = {
    status(route(app, requestToAPI).value) shouldBe ACCEPTED
  }

  private def callRemoteAPIAndMakeSureItReturns202(requestToAPI: FakeRequest[AnyContentAsXml]) = {
    implicit val hc = HeaderCarrier()
    val body = requestToAPI.body.asXml.get.toString()
    val headers = requestToAPI.headers.headers
    val result: HttpResponse = await(httpClient.POSTString("http://192.168.160.60:9821/customs-notification/notify", body, headers))

    result.status shouldBe ACCEPTED
  }

  private val makeAPICall: ExpectedCall => Unit = expectedCall => callAPIAndMakeSureItReturns202(fakeRequestToAPI(expectedCall))
  private val makeRemoteAPICall: ExpectedCall => Unit = expectedCall => callRemoteAPIAndMakeSureItReturns202(fakeRequestToAPI(expectedCall))

  private def insertIntoDB: ExpectedCall => Unit = { expectedCall =>
    val headers: Seq[Header] = expectedCall.maybeBadgeId.fold(Seq[Header]())(badgeId => Seq[Header](Header(X_BADGE_ID_HEADER_NAME, badgeId)))

    notificationRepo.save(
      ClientNotification(ClientSubscriptionId(expectedCall.client.csid),
        Notification(ConversationId(expectedCall.conversationId), headers, expectedCall.xml.toString(), MimeTypes.XML)))
  }


  feature("Ensure call to Push Pull are mode") {

    scenario(s"correctly with $numberOfClientsToTest clients and $totalNotificationsToBeSent requests") {

      val poolOfPushOrPullEnabledClientCSIDs: List[Client] = createPoolOfPushEnabledDisabledClients(numberOfClientsToTest, mutable.Set()).toList

      val expectedPushNotificationsByCSID: mutable.Map[Client, ListBuffer[ExpectedCall]] = mutable.Map()
      val expectedPullNotificationsByCSID: mutable.Map[Client, ListBuffer[ExpectedCall]] = mutable.Map()

      val expectedPushedNotificationsCounter = new AtomicInteger(0)
      val expectedPullNotificationsCounter = new AtomicInteger(0)
      val notificationsInsertedIntoDB = new AtomicInteger(0)

      val startTime = DateTime.now()

      insertTestData(poolOfPushOrPullEnabledClientCSIDs,
        expectedPushNotificationsByCSID,
        expectedPushedNotificationsCounter,
        expectedPullNotificationsByCSID,
        expectedPullNotificationsCounter,
        notificationsInsertedIntoDB)

      Then(s"totalExpectedPushedNotifications = $expectedPushedNotificationsCounter, totalExpectedPushedNotifications = $expectedPullNotificationsCounter")

      eventually(numberOfActualCallsMadeToPushService should be(expectedPushedNotificationsCounter.get()))
      eventually(numberOfActualCallsMadeToPullQ should be(expectedPullNotificationsCounter.get()))

      val notificationProcessingCompletionTime = DateTime.now() //by now, all requests have been processed

      actualCallsReceivedAtClientPushServiceAreSameAs(expectedPushNotificationsByCSID)
      actualCallsReceivedAtPullQAreSameAs(expectedPullNotificationsByCSID)

      val totalNotificationProcessed = expectedPushedNotificationsCounter.get() + expectedPullNotificationsCounter.get()
      val notificationProcessingTimeInMillis = notificationProcessingCompletionTime.getMillis - startTime.getMillis
      val notificationsProcessedInOneSecond = (totalNotificationProcessed.toFloat / notificationProcessingTimeInMillis) * 1000

      Then(s"totalRequestsProcessed = $totalNotificationProcessed, " +
        s"Pushed notifications = $expectedPushedNotificationsCounter, " +
        s"PullQ notifications = $expectedPullNotificationsCounter, " +
        s"totalTimeTakenToProcessRequests = $notificationProcessingTimeInMillis millis, " +
        s"notificationsPerSecond=$notificationsProcessedInOneSecond, " +
        s"notificationsInsertedIntoDB= $notificationsInsertedIntoDB," +
        s"totalTimeToTest= ${DateTime.now().getMillis - startTime.getMillis}")

      //      notificationsProcessedInOneSecond shouldBe > (70f)
    }
  }

  def insertTestData(poolOfPushOrPullEnabledClientCSIDs: List[Client],
                     expectedPushNotificationsByCSID: mutable.Map[Client, ListBuffer[ExpectedCall]],
                     expectedPushedNotificationsCounter: AtomicInteger,
                     expectedPullNotificationsByCSID: mutable.Map[Client, ListBuffer[ExpectedCall]],
                     expectedPullNotificationsCounter: AtomicInteger,
                     notificationsInsertedIntoDB: AtomicInteger): Unit = {
    for (a <- 1 to totalNotificationsToBeSent) {

      val client = poolOfPushOrPullEnabledClientCSIDs(Random.nextInt(numberOfClientsToTest))

      val insertNotificationInDB = false //Random.nextBoolean()
      val processor: ExpectedCall => Unit = if (insertNotificationInDB) insertIntoDB else makeAPICall

      if (client.isPushEnabled) {
        fireOffRandomRequest(client, processor).map { expectedCall =>
          addExpectedCallTo(expectedPushNotificationsByCSID, expectedCall)
          expectedPushedNotificationsCounter.getAndAdd(1)
          if (insertNotificationInDB) notificationsInsertedIntoDB.getAndAdd(1)
        }
      } else {
        fireOffRandomRequest(client, processor).map { expectedCall =>
          addExpectedCallTo(expectedPullNotificationsByCSID, expectedCall)
          expectedPullNotificationsCounter.getAndAdd(1)
          if (insertNotificationInDB) notificationsInsertedIntoDB.getAndAdd(1)
        }
      }
    }
  }

  def actualCallsReceivedAtPullQAreSameAs(expectedPullNotificationsByCSID: mutable.Map[Client, ListBuffer[ExpectedCall]]): Unit = {
    val allPullNotificationsByCSID = allPullQReceivedCallsByByCSID
    expectedPullNotificationsByCSID.size shouldBe allPullNotificationsByCSID.size

    expectedPullNotificationsByCSID.foreach[Unit] {
      case (client, expectedRequestsMadeForThisCSID) =>
        val actualRequestsMadeForThisCSID = allPullNotificationsByCSID.get(client.csid).get.toList

        Then(s"client ${client.csid} made total ${actualRequestsMadeForThisCSID.size} calls, expected were ${expectedRequestsMadeForThisCSID.size}")

        makeSureActualPullQRequestMadeWereAsExpected(expectedRequestsMadeForThisCSID.toList, actualRequestsMadeForThisCSID)
    }
  }

  def actualCallsReceivedAtClientPushServiceAreSameAs(expectedPushNotificationsByCSID: mutable.Map[Client, ListBuffer[ExpectedCall]]): Unit = {
    val allPushedNotificationsByCSID = allSuccessfullyPushedCallsByCSID

    expectedPushNotificationsByCSID.size shouldBe allPushedNotificationsByCSID.size

    expectedPushNotificationsByCSID.foreach[Unit] {
      case (client, expectedRequestsThisCSID) =>
        val actualRequestsMadeForThisCSID = allPushedNotificationsByCSID.get(client.csid).get.toList
        makeSureActualNotificationsRcvdWereAsExpected(expectedRequestsThisCSID.toList, actualRequestsMadeForThisCSID)
        Then(s"client ${client.csid} made total ${actualRequestsMadeForThisCSID.size} calls, expected were ${expectedRequestsThisCSID.size}")
    }
  }

  def fireOffRandomRequest(client: Client, callAPIOrInsertInDB: (ExpectedCall => Unit)): Future[ExpectedCall] = {
    Future.successful {
      val expectedCall = createARandomExpectedCallFor(client)
      callAPIOrInsertInDB(expectedCall)
      expectedCall
    }
  }

  def makeSureActualNotificationsRcvdWereAsExpected(expectedRequestsMadeForThisClient: List[ExpectedCall], actualRequestsMadeForThisClient: List[ActualCallMade]): Unit = {
    actualRequestsMadeForThisClient.size shouldBe expectedRequestsMadeForThisClient.size

    for (counter <- 0 to expectedRequestsMadeForThisClient.size - 1) {

      val expectedRequest = expectedRequestsMadeForThisClient(counter)
      val actualRequest = actualRequestsMadeForThisClient(counter)

      val actualReceivedHeaderNames = actualRequest.headers.map(_.name)
      // There must a better way to do following, dont have time now to search.
      // we are making sure that only allowed headers are sent, and only one value is sent for each header
      Set(actualReceivedHeaderNames: _*) shouldBe Set(
        "X-Request-Chain", ACCEPT, USER_AGENT, HOST, CONTENT_LENGTH, CONTENT_TYPE
      )
      actualReceivedHeaderNames.filter(_ == "X-Request-Chain").size shouldBe 1
      actualReceivedHeaderNames.filter(_ == USER_AGENT).size shouldBe 1
      actualReceivedHeaderNames.filter(_ == HOST).size shouldBe 1
      actualReceivedHeaderNames.filter(_ == CONTENT_LENGTH).size shouldBe 1

      actualRequest.headers.filter(_.name == ACCEPT) shouldBe List(ActualHeaderSent(ACCEPT, List(MimeTypes.JSON)))
      actualRequest.headers.filter(_.name == CONTENT_TYPE) shouldBe List(ActualHeaderSent(CONTENT_TYPE, List(MimeTypes.JSON)))

      parse(actualRequest.payload) should be(
        createPushNotificationRequestPayload(
          mayBeBadgeId = expectedRequest.maybeBadgeId,
          outboundUrl = expectedRequest.client.callbackData.callbackUrl,
          securityToken = expectedRequest.client.callbackData.securityToken,
          notificationPayload = expectedRequest.xml,
          conversationId = expectedRequest.conversationId.toString)

      )
    }
  }

  def makeSureActualPullQRequestMadeWereAsExpected(expectedPullQRequestsMadeForThisClient: List[ExpectedCall], actualRequestsMadeToPullQForThisClient: List[ActualCallMade]): Unit = {
    actualRequestsMadeToPullQForThisClient.size shouldBe expectedPullQRequestsMadeForThisClient.size

    for (counter <- 0 to expectedPullQRequestsMadeForThisClient.size - 1) {

      val expectedRequest = expectedPullQRequestsMadeForThisClient(counter)
      val actualRequest = actualRequestsMadeToPullQForThisClient(counter)

      val actualReceivedHeaderNames = actualRequest.headers.map(_.name)

      if (expectedRequest.maybeBadgeId.isDefined) {
        Set(actualReceivedHeaderNames: _*) shouldBe Set(
          "X-Request-Chain", ACCEPT, USER_AGENT, HOST, CONTENT_LENGTH, CONTENT_TYPE, "api-subscription-fields-id", "X-Conversation-ID", "X-Badge-Identifier"
        )
      } else {
        Set(actualReceivedHeaderNames: _*) shouldBe Set(
          "X-Request-Chain", ACCEPT, USER_AGENT, HOST, CONTENT_LENGTH, CONTENT_TYPE, "api-subscription-fields-id", "X-Conversation-ID"
        )
      }

      actualReceivedHeaderNames.filter(_ == "X-Request-Chain").size shouldBe 1
      actualReceivedHeaderNames.filter(_ == ACCEPT).size shouldBe 1
      actualReceivedHeaderNames.filter(_ == USER_AGENT).size shouldBe 1
      actualReceivedHeaderNames.filter(_ == HOST).size shouldBe 1
      actualReceivedHeaderNames.filter(_ == CONTENT_LENGTH).size shouldBe 1
      actualReceivedHeaderNames.filter(_ == CONTENT_TYPE).size shouldBe 1


      actualRequest.headers.filter(_.name == ACCEPT) shouldBe List(ActualHeaderSent(ACCEPT, List("*/*")))
      actualRequest.headers.filter(_.name == CONTENT_TYPE) shouldBe List(ActualHeaderSent(CONTENT_TYPE, List(MimeTypes.XML)))

      actualRequest.headers.filter(_.name == "api-subscription-fields-id") shouldBe List(ActualHeaderSent("api-subscription-fields-id", List(expectedRequest.client.csid.toString)))
      actualRequest.headers.filter(_.name == "X-Conversation-ID") shouldBe List(ActualHeaderSent("X-Conversation-ID", List(expectedRequest.conversationId.toString)))

      expectedRequest.maybeBadgeId.fold[Unit]()(badgeId =>
        actualRequest.headers.filter(_.name == "X-Badge-Identifier") shouldBe List(ActualHeaderSent("X-Badge-Identifier", List(badgeId)))
      )

      actualRequest.payload should be(expectedRequest.xml.toString())
    }
  }


  def createARandomExpectedCallFor(client: Client): ExpectedCall = {
    val maybeBadgeId = if (Random.nextBoolean()) Some("ABC" + Random.nextInt(100)) else None
    val conversationId = UUID.randomUUID()
    val notificationXML = <declaration>Some declaration for {client.csid.toString} </declaration>
    ExpectedCall(client, conversationId, maybeBadgeId, notificationXML)
  }

  def addExpectedCallTo(expectedCallsForCSIDs: mutable.Map[Client, ListBuffer[ExpectedCall]], expectedCall: ExpectedCall): Unit = {
    expectedCallsForCSIDs.get(expectedCall.client).fold[Unit](expectedCallsForCSIDs += (expectedCall.client -> ListBuffer(expectedCall)))(_ += expectedCall)
  }
}