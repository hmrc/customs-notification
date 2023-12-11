/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.customs.notification.controllers.errors

import com.github.tomakehurst.wiremock.WireMockServer
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{DoNotDiscover, GivenWhenThen, Suite}
import org.scalatestplus.play.ConfiguredServer
import play.api.http.HeaderNames.ACCEPT
import play.api.http.MimeTypes
import play.api.libs.ws.WSClient
import play.api.test.Helpers.*
import uk.gov.hmrc.customs.notification.IntegrationSpecBase
import uk.gov.hmrc.customs.notification.util.HeaderNames.*
import uk.gov.hmrc.customs.notification.util.IntegrationTestHelpers.*
import uk.gov.hmrc.customs.notification.util.TestData.*
import uk.gov.hmrc.customs.notification.util.{MockObjectIdService, WireMockHelpers, WsClientHelpers}

/**
 * Convenience class to only test this suite, as no app is available when running suite directly
 */
@DoNotDiscover
private class TestOnlyControllerErrorSpec extends Suite with IntegrationSpecBase {
  override def nestedSuites: IndexedSeq[Suite] =
    Vector(new ControllerErrorSpec(wireMockServer, mockObjectIdService))
}

class ControllerErrorSpec(val wireMockServer: WireMockServer,
                          val mockObjectIdService: MockObjectIdService) extends AnyWordSpec
  with ConfiguredServer
  with WireMockHelpers
  with WsClientHelpers
  with ScalaFutures
  with TableDrivenPropertyChecks
  with Matchers
  with GivenWhenThen {

  override protected implicit lazy val wsClient: WSClient = app.injector.instanceOf[WSClient]

  private val invalidRequests = {
    val malformedXmlRequest = () =>
      wsUrl(Endpoints.Notify)
        .withHttpHeaders(validControllerRequestHeaders(UntranslatedCsid).toList *)
        .post("<xml><</xml>")
        .futureValue

    Table(
      (
        "description",
        "expected code",
        "expected message",
        "expected response status",
        "doRequest"
      ),
      (
        "a malformed XML payload",
        "BAD_REQUEST",
        "Request body does not contain well-formed XML.",
        BAD_REQUEST,
        malformedXmlRequest
      ),
      (
        "the wrong Content-Type header",
        "UNSUPPORTED_MEDIA_TYPE",
        "The Content-Type header is missing or invalid.",
        UNSUPPORTED_MEDIA_TYPE,
        () => makeValidNotifyRequestAdding(CONTENT_TYPE -> MimeTypes.JSON)
      ),
      (
        "an invalid Authorization header",
        "UNAUTHORIZED",
        "Basic token is missing or not authorized.",
        UNAUTHORIZED,
        () => makeValidNotifyRequestAdding(AUTHORIZATION -> "invalidToken")
      ),
      (
        "an invalid Accept header",
        "ACCEPT_HEADER_INVALID",
        "The Accept header is invalid.",
        NOT_ACCEPTABLE,
        () => makeValidNotifyRequestAdding(ACCEPT -> "invalid accept value")
      ),
      (
        "a missing X-Conversation-ID header",
        "BAD_REQUEST",
        "The X-Conversation-ID header is missing.",
        BAD_REQUEST,
        () => makeValidNotifyRequestWithout(X_CONVERSATION_ID_HEADER_NAME)
      ),
      (
        "a missing X-CDS-Client-ID header",
        "BAD_REQUEST",
        "The X-CDS-Client-ID header is missing.",
        BAD_REQUEST,
        () => makeValidNotifyRequestWithout(X_CLIENT_SUB_ID_HEADER_NAME)
      ),
      (
        "an invalid X-CDS-Client-ID header",
        "BAD_REQUEST",
        "The X-CDS-Client-ID header is invalid.",
        BAD_REQUEST,
        () => makeValidNotifyRequestAdding(X_CLIENT_SUB_ID_HEADER_NAME -> "invalid csid")
      ),
      (
        "an invalid X-Correlation-ID header",
        "BAD_REQUEST",
        "The X-Correlation-ID header is invalid.",
        BAD_REQUEST,
        () => makeValidNotifyRequestAdding(X_CORRELATION_ID_HEADER_NAME -> "1234567890123456789012345678901234567")
      )
    )
  }

  "POST /notify endpoint" should {
    forAll(invalidRequests) { (description, expectedCode, expectedMessage, expectedResponseStatus, doRequest) =>
      s"respond with a status of $expectedResponseStatus when a request is received with $description" in {
        val expectedBody = s"<errorResponse><code>$expectedCode</code><message>$expectedMessage</message></errorResponse>"

        val response = doRequest()
        response.status shouldBe expectedResponseStatus
        response.body shouldBe expectedBody
      }
    }
  }
}
