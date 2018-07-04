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

package util

import java.util.UUID

import com.typesafe.config.{Config, ConfigFactory}
import play.api.http.HeaderNames._
import play.api.http.MimeTypes
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.AnyContentAsXml
import play.api.test.FakeRequest
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.controllers.CustomMimeType
import uk.gov.hmrc.customs.notification.domain._
import util.RequestHeaders._
import util.TestData._

import scala.xml.{Elem, NodeSeq}

object TestData {

  val validConversationId: String = "eaca01f9-ec3b-4ede-b263-61b626dde232"
  val validConversationIdUUID = UUID.fromString(validConversationId)
  val conversationId = ConversationId(validConversationIdUUID)
  val invalidConversationId: String = "I-am-not-a-valid-uuid"

  val validFieldsId = "ffff01f9-ec3b-4ede-b263-61b626dde232"
  val invalidFieldsId = "I-am-not-a-valid-type-4-uuid"

  val basicAuthTokenValue = "YmFzaWN1c2VyOmJhc2ljcGFzc3dvcmQ="
  val validBasicAuthToken = s"Basic $basicAuthTokenValue"
  val invalidBasicAuthToken = "I-am-not-a-valid-auth-token"
  val overwrittenBasicAuthToken = "value-not-logged"

  type EmulatedServiceFailure = UnsupportedOperationException
  val emulatedServiceFailure = new EmulatedServiceFailure("Emulated service failure.")

  val callbackUrl = "http://callback"
  val invalidCallbackUrl = "Im-Invalid"
  val securityToken = "securityToken"
  val callbackData = DeclarantCallbackData(callbackUrl, securityToken)
  val invalidCallbackData = DeclarantCallbackData(invalidCallbackUrl, securityToken)

  val url = "http://some-url"
  val errorMsg = "ERROR"
  val warnMsg = "WARN"
  val infoMsg = "INFO"
  val debugMsg = "DEBUG"

  val badgeId = "ABCDEF1234"
  val userAgent = "Customs Declaration Service"

  lazy val somePublicNotificationRequest: Option[PublicNotificationRequest] = Some(publicNotificationRequest)
  lazy val publicNotificationRequest: PublicNotificationRequest = publicNotificationRequest(ValidXML)

  def createPushNotificationRequestPayload(outboundUrl: String = callbackData.callbackUrl, securityToken: String = callbackData.securityToken,
                                           badgeId: String = badgeId, notificationPayload: NodeSeq = ValidXML,
                                           conversationId: String = validConversationId): JsValue = Json.parse(
    s"""
       |{
       |   "url": "$outboundUrl",
       |   "conversationId": "$conversationId",
       |   "authHeaderToken": "$securityToken",
       |   "outboundCallHeaders": [{"name": "X-Badge-Identifier", "value": "$badgeId"}],
       |   "xmlPayload": "${notificationPayload.toString()}"
       |}
    """.stripMargin)

  def publicNotificationRequest(xml: NodeSeq): PublicNotificationRequest = {
    val body = PublicNotificationRequestBody(callbackData.callbackUrl, callbackData.securityToken, validConversationId, Seq(Header(X_BADGE_ID_HEADER_NAME, badgeId)), xml.toString())
    PublicNotificationRequest(validFieldsId, body)
  }

  def failedPublicNotificationRequest(xml: NodeSeq): PublicNotificationRequest = {
    val body = PublicNotificationRequestBody(invalidCallbackData.callbackUrl, callbackData.securityToken, validConversationId, Seq(Header(X_BADGE_ID_HEADER_NAME, badgeId)), xml.toString())
    PublicNotificationRequest(validFieldsId, body)
  }

  val ValidXML: Elem = <Foo>Bar</Foo>

  lazy val ValidRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER, BASIC_AUTH_HEADER, X_BADGE_ID_HEADER)
    .withXmlBody(ValidXML)

  lazy val InvalidConversationIdHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, RequestHeaders.X_CONVERSATION_ID_INVALID, CONTENT_TYPE_HEADER, ACCEPT_HEADER)
    .withXmlBody(ValidXML)

  lazy val MissingConversationIdHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER)
    .withXmlBody(ValidXML)

  lazy val InvalidAuthorizationHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER, RequestHeaders.BASIC_AUTH_HEADER_INVALID)
    .withXmlBody(ValidXML)

  lazy val MissingAuthorizationHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER, X_CONVERSATION_ID_HEADER)
    .withXmlBody(ValidXML)

  lazy val InvalidClientIdHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(RequestHeaders.X_CDS_CLIENT_ID_INVALID, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER)
    .withXmlBody(ValidXML)

  lazy val MissingClientIdHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(RequestHeaders.X_CONVERSATION_ID_INVALID, CONTENT_TYPE_HEADER, ACCEPT_HEADER)
    .withXmlBody(ValidXML)

  lazy val InvalidContentTypeHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, RequestHeaders.CONTENT_TYPE_HEADER_INVALID, ACCEPT_HEADER)
    .withXmlBody(ValidXML)

  lazy val MissingAcceptHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER)
    .withXmlBody(ValidXML)

  lazy val InvalidAcceptHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER, RequestHeaders.ACCEPT_HEADER_INVALID)
    .withXmlBody(ValidXML)

  val errorResponseForMissingAcceptHeader: Elem =
    <errorResponse>
      <code>ACCEPT_HEADER_INVALID</code>
      <message>The accept header is missing or invalid</message>
    </errorResponse>

  val errorResponseForInvalidContentType: Elem =
    <errorResponse>
      <code>UNSUPPORTED_MEDIA_TYPE</code>
      <message>The content type header is missing or invalid</message>
    </errorResponse>

  val errorResponseForUnauthorized: Elem =
    <errorResponse>
      <code>UNAUTHORIZED</code>
      <message>Basic token is missing or not authorized</message>
    </errorResponse>

  val errorResponseForInvalidClientId: Elem =
    <errorResponse>
      <code>BAD_REQUEST</code>
      <message>The X-CDS-Client-ID header value is invalid</message>
    </errorResponse>

  val errorResponseForMissingClientId: Elem =
    <errorResponse>
      <code>BAD_REQUEST</code>
      <message>The X-CDS-Client-ID header is missing</message>
    </errorResponse>

  val errorResponseForInvalidConversationId: Elem =
    <errorResponse>
      <code>BAD_REQUEST</code>
      <message>The X-Conversation-ID header value is invalid</message>
    </errorResponse>

  val errorResponseForMissingConversationId: Elem =
    <errorResponse>
      <code>BAD_REQUEST</code>
      <message>The X-Conversation-ID header is missing</message>
    </errorResponse>

  val errorResponseForPlayXmlBodyParserError: Elem =
    <errorResponse>
      <code>BAD_REQUEST</code>
      <message>Invalid XML: Premature end of file.</message>
    </errorResponse>

  val errorResponseForClientIdNotFound: Elem = errorResponseForInvalidClientId

  lazy val invalidConfigMissingBasicAuthToken: Config = ConfigFactory.parseString("")
}

object RequestHeaders {

  lazy val X_CDS_CLIENT_ID_HEADER: (String, String) = X_CDS_CLIENT_ID_HEADER_NAME -> validFieldsId

  lazy val X_CDS_CLIENT_ID_INVALID: (String, String) = X_CDS_CLIENT_ID_HEADER_NAME -> invalidFieldsId

  lazy val X_CONVERSATION_ID_HEADER: (String, String) = X_CONVERSATION_ID_HEADER_NAME -> validConversationId

  lazy val X_CONVERSATION_ID_INVALID: (String, String) = X_CONVERSATION_ID_HEADER_NAME -> invalidConversationId

  lazy val X_BADGE_ID_HEADER: (String, String) = X_BADGE_ID_HEADER_NAME -> badgeId

  lazy val CONTENT_TYPE_HEADER: (String, String) = CONTENT_TYPE -> CustomMimeType.XmlCharsetUtf8

  lazy val CONTENT_TYPE_HEADER_LOWERCASE: (String, String) = CONTENT_TYPE -> CustomMimeType.XmlCharsetUtf8.toLowerCase

  lazy val CONTENT_TYPE_HEADER_INVALID: (String, String) = CONTENT_TYPE -> MimeTypes.BINARY

  lazy val ACCEPT_HEADER: (String, String) = ACCEPT -> MimeTypes.XML

  lazy val ACCEPT_HEADER_INVALID: (String, String) = ACCEPT -> MimeTypes.BINARY

  lazy val BASIC_AUTH_HEADER: (String, String) = AUTHORIZATION -> validBasicAuthToken

  lazy val BASIC_AUTH_HEADER_INVALID: (String, String) = AUTHORIZATION -> invalidBasicAuthToken

  lazy val BASIC_AUTH_HEADER_OVERWRITTEN: (String, String) = AUTHORIZATION -> overwrittenBasicAuthToken

  lazy val ValidHeaders = Map(
    X_CDS_CLIENT_ID_HEADER,
    X_CONVERSATION_ID_HEADER,
    CONTENT_TYPE_HEADER,
    ACCEPT_HEADER,
    BASIC_AUTH_HEADER,
    X_BADGE_ID_HEADER
  )

  val LoggingHeaders = Seq(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER)
  val LoggingHeadersWithAuth = Seq(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, BASIC_AUTH_HEADER)
  val LoggingHeadersWithAuthOverwritten = Seq(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, BASIC_AUTH_HEADER_OVERWRITTEN)

  val NoHeaders: Map[String, String] = Map[String, String]()
}
