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

import com.github.tomakehurst.wiremock.http.HttpHeader
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import play.api.libs.json.{JsObject, Json}
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames
import uk.gov.hmrc.customs.notification.domain.DeclarantCallbackData
import util.{NotificationQueueService, PushNotificationService}

import scala.collection.mutable.ListBuffer
import scala.collection.{JavaConversions, mutable}
import scala.xml.NodeSeq



trait StubForPushService extends PushServiceStub {
  self: PushNotificationService =>

  override def numberOfActualCallsMadeToPushService(): Int = actualCallsMadeToClientsPushService().size

  override def allSuccessfullyPushedCallsByCSID(): Map[UUID, List[ActualCallMade]] = {
    val allPushedCallsByCSID = mutable.Map[UUID, ListBuffer[ActualCallMade]]()

    JavaConversions.asScalaBuffer(actualCallsMadeToClientsPushService()).foreach { loggedRequest =>
      val authToken = Json.parse(loggedRequest.getBodyAsString).as[JsObject].value.get("authHeaderToken").get.toString()

      // 6 & 42 constants are based on the badgeId creation in PushPullDBInsertionTestDataFeeder.createARandomExpectedCallFor
      val csid = UUID.fromString(authToken.substring(6, 42))
      val actualCallMade = convertToActualCallMade(loggedRequest)

      allPushedCallsByCSID.get(csid).fold[Unit](allPushedCallsByCSID += (csid -> ListBuffer(actualCallMade)))(_ += actualCallMade)
    }
    allPushedCallsByCSID.map(x => (x._1, x._2.toList)).toMap
  }

  private def convertToActualCallMade(lr: LoggedRequest) = {
    val headers: List[ActualHeaderSent] = JavaConversions.asScalaIterator[HttpHeader](lr.getHeaders().all().iterator()).map {
      x => ActualHeaderSent(x.key(), List(JavaConversions.asScalaBuffer(x.values()): _*))
    }.toList
    ActualCallMade(headers, lr.getBodyAsString())
  }

}

trait StubForPullService extends PullQStub {
  self: NotificationQueueService =>

  override def numberOfActualCallsMadeToPullQ(): Int = actualCallsMadeToPullQ().size

  override def allPullQReceivedCallsByByCSID(): Map[UUID, List[ActualCallMade]] = {
    val notificationsByCSID = mutable.Map[UUID, ListBuffer[ActualCallMade]]()

    JavaConversions.asScalaBuffer(actualCallsMadeToPullQ()).foreach { loggedRequest =>
      val csid = UUID.fromString(loggedRequest.getHeader(CustomHeaderNames.SUBSCRIPTION_FIELDS_ID_HEADER_NAME))
      val acm = convertToActualCallMade(loggedRequest)
      notificationsByCSID.get(csid).fold[Unit](notificationsByCSID += (csid -> ListBuffer(acm)))(_ += acm)
    }
    notificationsByCSID.map(x => (x._1, x._2.toList)).toMap
  }

  private def convertToActualCallMade(lr: LoggedRequest) = {
    val headers: List[ActualHeaderSent] = JavaConversions.asScalaIterator[HttpHeader](lr.getHeaders().all().iterator()).map {
      x => ActualHeaderSent(x.key(), List(JavaConversions.asScalaBuffer(x.values()): _*))
    }.toList
    ActualCallMade(headers, lr.getBodyAsString())
  }

}
