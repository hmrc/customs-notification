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

package unit.services

import java.time.{ZoneId, ZonedDateTime}

import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.services.PushNotificationRequestService
import uk.gov.hmrc.play.test.UnitSpec
import util.TestData._

class PushNotificationRequestServiceSpec extends UnitSpec with MockitoSugar {

  private val mockApiSubscriptionFieldsConnector = mock[ApiSubscriptionFieldsConnector]

  private val service = new PushNotificationRequestService(mockApiSubscriptionFieldsConnector)
  val testDate: ZonedDateTime = ZonedDateTime.now(ZoneId.of("UTC"))

  val metaData = RequestMetaData(clientSubscriptionId, conversationId, requestId, notificationId, Some(clientId1), Some(BadgeId(badgeId)),
    Some(Submitter(submitterNumber)), None, Some(FunctionCode(functionCode)), Some(IssueDateTime(issueDateTime)), Some(Mrn(mrn)), testDate)

  "PushNotificationRequestService" should {

    "return valid request when badgeId is provided" in {
      val metaDataWithSomeBadgeId = RequestMetaData(clientSubscriptionId, conversationId, requestId, notificationId, Some(clientId1), Some(BadgeId(badgeId)),
        None, None, Some(FunctionCode(functionCode)), Some(IssueDateTime(issueDateTime)), Some(Mrn(mrn)), testDate)
      service.createRequest(ValidXML, callbackData, metaDataWithSomeBadgeId) shouldBe expectedRequest(Some(badgeId), None)
    }

    "request does not contain badgeId or submitterNumber headers when not provided" in {
      val metaDataWithNoBadgeId = RequestMetaData(clientSubscriptionId, conversationId, requestId, notificationId, Some(clientId1),
        None, None, None, Some(FunctionCode(functionCode)), Some(IssueDateTime(issueDateTime)), Some(Mrn(mrn)), testDate)
      service.createRequest(ValidXML, callbackData, metaDataWithNoBadgeId) shouldBe expectedRequest(None, None)
    }

    "return valid request when submitterNumber is provided" in {
      val metaDataWithSomeSubmitterNumber = RequestMetaData(clientSubscriptionId, conversationId, requestId, notificationId, Some(clientId1),
        None, Some(Submitter(submitterNumber)), None, Some(FunctionCode(functionCode)), Some(IssueDateTime(issueDateTime)), Some(Mrn(mrn)), testDate)
      service.createRequest(ValidXML, callbackData, metaDataWithSomeSubmitterNumber) shouldBe expectedRequest(None, Some(submitterNumber))
    }
  }

  private def expectedRequest(expectedBadgeId: Option[String], expectedSubmitterNumber: Option[String]) = {
    val expectedHeaders: Seq[Header] = expectedBadgeId.fold(Seq[Header]())(badgeId => Seq(Header(X_BADGE_ID_HEADER_NAME, badgeId))) ++
      expectedSubmitterNumber.fold(Seq[Header]())(submitterNumber => Seq(Header(X_SUBMITTER_ID_HEADER_NAME, submitterNumber)))
    PushNotificationRequest(validFieldsId,
      PushNotificationRequestBody(callbackData.callbackUrl, callbackData.securityToken, validConversationId, expectedHeaders, ValidXML.toString()))
  }

}
