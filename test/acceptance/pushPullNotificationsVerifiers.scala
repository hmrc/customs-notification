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

import org.scalatest.Matchers
import org.scalatest.concurrent.Eventually
import play.api.http.MimeTypes
import play.api.libs.json.Json._
import play.api.test.Helpers._
import util.TestData._

case class ActualHeaderSent(name: String, value: List[String])

case class ActualCallMade(headers: List[ActualHeaderSent], payload: String)

trait PushServiceStub {
  def numberOfActualCallsMadeToPushService(): Int
  def allSuccessfullyPushedCallsByCSID(): Map[UUID, List[ActualCallMade]]
}

trait PullQStub {
  def numberOfActualCallsMadeToPullQ(): Int
  def allPullQReceivedCallsByByCSID(): Map[UUID, List[ActualCallMade]]
}


trait PushPullNotificationsVerifier extends Matchers with Eventually {

  def pushServiceStub: PushServiceStub

  def pullQStub: PullQStub

  def verifyActualNotificationsAreSameAs(pushedNotificationExpectations: Map[Client, List[ExpectedCall]], pullNotificationExpectations: Map[Client, List[ExpectedCall]]): Unit = {

    eventually(pushServiceStub.numberOfActualCallsMadeToPushService() should be(pushedNotificationExpectations.values.flatten.size))
    eventually(pullQStub.numberOfActualCallsMadeToPullQ() should be(pullNotificationExpectations.values.flatten.size))

    actualCallsReceivedAtClientPushServiceAreSameAs(pushedNotificationExpectations, pushServiceStub.allSuccessfullyPushedCallsByCSID())
    actualCallsReceivedAtPullQAreSameAs(pullNotificationExpectations, pullQStub.allPullQReceivedCallsByByCSID())
  }

  private def actualCallsReceivedAtClientPushServiceAreSameAs(expectedPushNotificationsByCSID: Map[Client, List[ExpectedCall]],
                                                              allPushedNotificationsByCSID: Map[UUID, List[ActualCallMade]]): Unit = {

    expectedPushNotificationsByCSID.foreach[Unit] {

      case (client, expectedRequestsThisCSID) =>
        val actualRequestsMadeForThisCSID = allPushedNotificationsByCSID.get(client.csid).get
        makeSureActualNotificationsRcvdWereAsExpected(expectedRequestsThisCSID, actualRequestsMadeForThisCSID)

    }
  }

  private def actualCallsReceivedAtPullQAreSameAs(expectedPullNotificationsByCSID: Map[Client, List[ExpectedCall]],
                                                  allPullNotificationsByCSID: Map[UUID, List[ActualCallMade]]): Unit = {

    expectedPullNotificationsByCSID.foreach[Unit] {

      case (client, expectedRequestsMadeForThisCSID) =>
        val actualRequestsMadeForThisCSID = allPullNotificationsByCSID.get(client.csid).get

        makeSureActualPullQRequestMadeWereAsExpected(expectedRequestsMadeForThisCSID, actualRequestsMadeForThisCSID)
    }
  }

  private def makeSureActualPullQRequestMadeWereAsExpected(expectedPullQRequestsMadeForThisClient: List[ExpectedCall], actualRequestsMadeToPullQForThisClient: List[ActualCallMade]): Unit = {
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

  private def makeSureActualNotificationsRcvdWereAsExpected(expectedRequestsMadeForThisClient: List[ExpectedCall], actualRequestsMadeForThisClient: List[ActualCallMade]): Unit = {
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
}
