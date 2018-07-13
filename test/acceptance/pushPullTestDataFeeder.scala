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

import org.scalatest.Matchers
import org.scalatest.concurrent.Eventually
import uk.gov.hmrc.customs.notification.domain.DeclarantCallbackData
import util.ApiSubscriptionFieldsService

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Random
import scala.xml.NodeSeq

case class Client(csid: UUID, isPushEnabled: Boolean, callbackData: DeclarantCallbackData)

case class ExpectedCall(client: Client, conversationId: UUID, maybeBadgeId: Option[String], xml: NodeSeq)

trait PushPullDBInsertionTestDataFeeder extends Matchers with Eventually {

  def makeAPICall: (ExpectedCall => Unit)

  def apiSubscriptionFieldsService: ApiSubscriptionFieldsService

  def insertTestData(totalNotificationsToBeSent: Int,
                     numberOfClientsToTest: Int): (Map[Client, List[ExpectedCall]], Map[Client, List[ExpectedCall]]) = {

    val expectedPushNotificationsByClient: mutable.Map[Client, ListBuffer[ExpectedCall]] = mutable.Map()
    val expectedPullNotificationsByClient: mutable.Map[Client, ListBuffer[ExpectedCall]] = mutable.Map()

    val poolOfPushOrPullEnabledClients: List[Client] = createPoolOfPushEnabledDisabledClients(numberOfClientsToTest, mutable.Set())(apiSubscriptionFieldsService).toList

    createTestData(totalNotificationsToBeSent - 1,
      sendNotificationToCustomsNotificationFor(aRandomlySelectedClient(poolOfPushOrPullEnabledClients)()),
      expectedPushNotificationsByClient,
      expectedPullNotificationsByClient,
      aRandomlySelectedClient(poolOfPushOrPullEnabledClients) _)


    //Wait until all requests are made i.e. all above Futures are completed.
    //If this starts failing then increase the PatienceConfig.
    eventually(expectedPushNotificationsByClient.values.flatten.size + expectedPullNotificationsByClient.values.flatten.size should be(totalNotificationsToBeSent))

    (convertToMutableMap(expectedPushNotificationsByClient), convertToMutableMap(expectedPullNotificationsByClient))
  }

  private def aRandomlySelectedClient(poolOfPushOrPullEnabledClientCSIDs: List[Client])() =
    poolOfPushOrPullEnabledClientCSIDs(Random.nextInt(poolOfPushOrPullEnabledClientCSIDs.size))

  private def createTestData(totalNotificationsToBeSent: Int,
                             lastCallFuture: Future[ExpectedCall],
                             expectedPushNotificationsByClient: mutable.Map[Client, ListBuffer[ExpectedCall]],
                             expectedPullNotificationsByClient: mutable.Map[Client, ListBuffer[ExpectedCall]],
                             aRandomlySelectedClient: () => Client): Future[ExpectedCall] = {

    if (totalNotificationsToBeSent == 0) {
      lastCallFuture.map { expectedCall =>
        addExpectedCallToPullOrPullExpectedCollection(expectedPushNotificationsByClient, expectedPullNotificationsByClient, expectedCall)
        expectedCall
      }
    } else {
      lastCallFuture.flatMap { lastExpectedCall =>

        addExpectedCallToPullOrPullExpectedCollection(expectedPushNotificationsByClient, expectedPullNotificationsByClient, lastExpectedCall)

        createTestData(
          totalNotificationsToBeSent - 1,
          sendNotificationToCustomsNotificationFor(aRandomlySelectedClient()),
          expectedPushNotificationsByClient,
          expectedPullNotificationsByClient,
          aRandomlySelectedClient)
      }

    }
  }

  private def addExpectedCallToPullOrPullExpectedCollection(expectedPushNotificationsByCSID: mutable.Map[Client, ListBuffer[ExpectedCall]], expectedPullNotificationsByCSID: mutable.Map[Client, ListBuffer[ExpectedCall]], expectecCall: ExpectedCall): Unit = {
    if (expectecCall.client.isPushEnabled) {
      addExpectedCallTo(expectedPushNotificationsByCSID, expectecCall)
    } else {
      addExpectedCallTo(expectedPullNotificationsByCSID, expectecCall)
    }
  }

  private def convertToMutableMap(mutableMap: mutable.Map[Client, ListBuffer[ExpectedCall]]) =
    mutableMap.map(x => (x._1, x._2.toList)).toMap

  private def createPoolOfPushEnabledDisabledClients(numbers: Int, clients: mutable.Set[Client])(implicit apiSubscriptionFieldsService: ApiSubscriptionFieldsService): mutable.Set[Client] = {
    if (numbers > 0) {
      val randomCSID = UUID.randomUUID()
      val isPushEnabled = Random.nextBoolean()
      var callbackData = DeclarantCallbackData(s"http://client-url/$randomCSID/service", s"auth-$randomCSID")
      if (isPushEnabled) {
        apiSubscriptionFieldsService.startApiSubscriptionFieldsService(randomCSID.toString(), callbackData)
      } else {
        callbackData = DeclarantCallbackData("", "")
        apiSubscriptionFieldsService.startApiSubscriptionFieldsService(randomCSID.toString(), callbackData)
      }
      clients.add(Client(randomCSID, isPushEnabled, callbackData))
      createPoolOfPushEnabledDisabledClients(numbers - 1, clients)
    }
    clients
  }

  private def sendNotificationToCustomsNotificationFor(client: Client): Future[ExpectedCall] = {
    Future.successful {
      val expectedCall = createARandomExpectedCallFor(client)
      makeAPICall(expectedCall)
      expectedCall
    }
  }

  private def createARandomExpectedCallFor(client: Client): ExpectedCall = {
    val maybeBadgeId = if (Random.nextBoolean()) Some("ABC" + Random.nextInt(100)) else None
    val conversationId = UUID.randomUUID()
    val notificationXML = <declaration>Some notification Data</declaration>
    ExpectedCall(client, conversationId, maybeBadgeId, notificationXML)
  }

  private def addExpectedCallTo(expectedCallsForCSIDs: mutable.Map[Client, ListBuffer[ExpectedCall]], expectedCall: ExpectedCall): Unit = {
    expectedCallsForCSIDs.get(expectedCall.client).fold[Unit](expectedCallsForCSIDs += (expectedCall.client -> ListBuffer(expectedCall)))(_ += expectedCall)
  }
}
