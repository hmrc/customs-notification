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

import org.mockito.ArgumentMatchers.{eq => meq, _}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import play.api.mvc.Headers
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.services.PublicNotificationRequestService
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import util.RequestHeaders
import util.TestData._

import scala.concurrent.Future

class PublicNotificationRequestServiceSpec extends UnitSpec with MockitoSugar {

  private val mockApiSubscriptionFieldsConnector = mock[ApiSubscriptionFieldsConnector]

  private val service = new PublicNotificationRequestService(mockApiSubscriptionFieldsConnector)

  private val ValidInboundHeaders = Seq(
    RequestHeaders.X_CONVERSATION_ID_HEADER,
    RequestHeaders.X_CDS_CLIENT_ID_HEADER
  )

  implicit val hc: HeaderCarrier = HeaderCarrier()

  private val emulatedClientDataFailure = new IllegalStateException("boom")

  "PublicNotificationRequestService" should {
    "return Some request for valid input" in {
      when(mockApiSubscriptionFieldsConnector.getClientData(meq(validFieldsId))(any[HeaderCarrier])).thenReturn(Some(callbackData))

      val maybeRequest = await(service.createRequest(ValidXML, Headers(ValidInboundHeaders :_*)))

      maybeRequest shouldBe somePublicNotificationRequest
    }

    "return None when client id not found" in {
      when(mockApiSubscriptionFieldsConnector.getClientData(meq(validFieldsId))(any[HeaderCarrier])).thenReturn(None)

      val maybeRequest = await(service.createRequest(ValidXML, Headers(ValidInboundHeaders :_*)))

      maybeRequest shouldBe None
    }

    "propagate exception in ApiSubscriptionFieldsConnector" in {
      when(mockApiSubscriptionFieldsConnector.getClientData(meq(validFieldsId))(any[HeaderCarrier])).thenReturn(Future.failed(emulatedClientDataFailure))

      val caught = intercept[IllegalStateException] {
        await(service.createRequest(ValidXML, Headers(ValidInboundHeaders :_*)))
      }

      caught shouldBe emulatedClientDataFailure
    }


  }

}
