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

package unit.services

import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain.{Header, PushNotificationRequest, PushNotificationRequestBody}
import uk.gov.hmrc.customs.notification.services.PushNotificationRequestService
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import util.TestData._

class PushNotificationRequestServiceSpec extends UnitSpec with MockitoSugar {

  private val mockApiSubscriptionFieldsConnector = mock[ApiSubscriptionFieldsConnector]

  private val service = new PushNotificationRequestService(mockApiSubscriptionFieldsConnector)

  implicit val hc: HeaderCarrier = HeaderCarrier()
  val metaData = RequestMetaData(validFieldsId, validConversationIdUUID, Some(badgeId))

  "PushNotificationRequestService" should {

    "return valid request when badgeId is provided" in {
      val metaDataWithSomeBadgeId = RequestMetaData(validFieldsId, validConversationIdUUID, Some(badgeId))
      service.createRequest(ValidXML, callbackData, metaDataWithSomeBadgeId) shouldBe expectedRequest(Some(badgeId))
    }

    "request does not contain badgeId header when it is not provided" in {
      val metaDataWithNoBadgeId = RequestMetaData(validFieldsId, validConversationIdUUID, None)
      service.createRequest(ValidXML, callbackData, metaDataWithNoBadgeId) shouldBe expectedRequest(None)
    }

    "request does not contain badgeId header when it is provided as empty value" in {
      val metaDataWithEmptyBadgeId = RequestMetaData(validFieldsId, validConversationIdUUID, Some(""))
      service.createRequest(ValidXML, callbackData, metaDataWithEmptyBadgeId) shouldBe expectedRequest(None)
    }

  }

  private def expectedRequest(expectedBadgeId: Option[String]) = {
    val expectedHeaders: Seq[Header] = expectedBadgeId.fold(Seq[Header]())(badgeId => Seq(Header(X_BADGE_ID_HEADER_NAME, badgeId)))

    PushNotificationRequest(validFieldsId,
      PushNotificationRequestBody(callbackData.callbackUrl, callbackData.securityToken, validConversationId, expectedHeaders, ValidXML.toString()))
  }

}
