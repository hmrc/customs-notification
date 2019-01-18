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

package util

import java.time.ZonedDateTime
import java.util.UUID

import com.typesafe.config.{Config, ConfigFactory}
import org.joda.time.DateTime
import play.api.http.HeaderNames._
import play.api.http.MimeTypes
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.AnyContentAsXml
import play.api.test.FakeRequest
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.controllers.CustomMimeType
import uk.gov.hmrc.customs.notification.domain.{NotificationWorkItem, _}
import uk.gov.hmrc.customs.notification.util.DateTimeHelpers._
import uk.gov.hmrc.workitem.{ToDo, WorkItem}
import util.CustomsNotificationMetricsTestData.UtcZoneId
import util.RequestHeaders._
import util.TestData._

import scala.xml.{Elem, NodeSeq}

object TestData {

  val validConversationId: String = "eaca01f9-ec3b-4ede-b263-61b626dde232"
  val validConversationIdUUID: UUID = UUID.fromString(validConversationId)
  val conversationId = ConversationId(validConversationIdUUID)
  val invalidConversationId: String = "I-am-not-a-valid-uuid"

  val validFieldsId = "ffff01f9-ec3b-4ede-b263-61b626dde232"
  val someFieldsId = "ccc9f676-c752-4e77-b86a-b27a3b33fceb"
  val clientSubscriptionId = ClientSubscriptionId(UUID.fromString(validFieldsId))
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
  val eoriNumber = "IAMEORI"
  val userAgent = "Customs Declaration Service"
  val correlationId = "CORRID2234"

  lazy val somePushNotificationRequest: Option[PushNotificationRequest] = Some(pushNotificationRequest)
  lazy val pushNotificationRequest: PushNotificationRequest = pushNotificationRequest(ValidXML)


  val Year = 2017
  val MonthOfYear = 7
  val DayOfMonth = 4
  val HourOfDay = 13
  val MinuteOfHour = 45
  val TimeReceivedZoned = ZonedDateTime.of(2016, 1, 30, 23, 46,
    59, 0, UtcZoneId)
  val TimeReceivedDateTime = TimeReceivedZoned.toDateTime

  val MetricsStartTimeZoned = ZonedDateTime.of(2016, 1, 30, 23, 44,
    59, 0, UtcZoneId)
  val MetricsStartTimeDateTime: DateTime = TimeReceivedZoned.toDateTime

  val validClientSubscriptionId1String: String = "eaca01f9-ec3b-4ede-b263-61b626dde232"
  val validClientSubscriptionId1UUID: UUID = UUID.fromString(validClientSubscriptionId1String)
  val validClientSubscriptionId1 = ClientSubscriptionId(validClientSubscriptionId1UUID)

  val validClientSubscriptionId2String: String = "eaca01f9-ec3b-4ede-b263-61b626dde233"
  val validClientSubscriptionId2UUID: UUID = UUID.fromString(validClientSubscriptionId2String)
  val validClientSubscriptionId2 = ClientSubscriptionId(validClientSubscriptionId2UUID)

  val payload1 = "<foo1></foo1>"
  val payload2 = "<foo2></foo2>"
  val payload3 = "<foo3></foo3>"

  val headers = Seq(Header("h1","v1"), Header("h2", "v2"))
  val notification1 = Notification(conversationId, headers, payload1, CustomMimeType.XmlCharsetUtf8)
  val notification2 = Notification(conversationId, headers, payload2, CustomMimeType.XmlCharsetUtf8)
  val notification3 = Notification(conversationId, headers, payload3, CustomMimeType.XmlCharsetUtf8)

  val client1Notification1 = ClientNotification(validClientSubscriptionId1, notification1, None, Some(TimeReceivedDateTime))
  val client1Notification2 = ClientNotification(validClientSubscriptionId1, notification2, None, Some(TimeReceivedDateTime))
  val client1Notification3 = ClientNotification(validClientSubscriptionId1, notification3, None, Some(TimeReceivedDateTime))
  val client2Notification1 = ClientNotification(validClientSubscriptionId2, notification1, None, Some(TimeReceivedDateTime))

  val client1Notification1WithTimeReceived = ClientNotification(validClientSubscriptionId1, notification1, Some(TimeReceivedDateTime), None)
  val client2Notification1WithTimeReceived = ClientNotification(validClientSubscriptionId2, notification1, Some(TimeReceivedDateTime), None)

  val NotificationWorkItem1 = NotificationWorkItem(validClientSubscriptionId1, notification = notification1)
  val NotificationWorkItem2 = NotificationWorkItem(validClientSubscriptionId2, notification = notification2)
  val WorkItem1 = WorkItem(BSONObjectID.generate(), TimeReceivedDateTime, TimeReceivedDateTime, TimeReceivedDateTime, ToDo, 0, NotificationWorkItem1)
  val WorkItem2 = WorkItem1.copy(item = NotificationWorkItem2)

  val NotificationWorkItemWithMetricsTime1 = NotificationWorkItem1.copy(metricsStartDateTime = Some(TimeReceivedDateTime))
  val PushNotificationRequest1 = PushNotificationRequest(validClientSubscriptionId1.id.toString, PushNotificationRequestBody("URL", "SECURITY_TOKEN", conversationId.id.toString, headers, payload1))

  lazy val badgeIdHeader = Header(X_BADGE_ID_HEADER_NAME, badgeId)

  def clientNotification(withBadgeId: Boolean = true, withCorrelationId: Boolean = true): ClientNotification = {

    lazy val correlationIdHeader = Header("x-cOrRelaTion-iD", correlationId)

    val finalHeaders = (withBadgeId, withCorrelationId) match {
      case (true, true) => Seq[Header](badgeIdHeader, correlationIdHeader)
      case (true, false) => Seq[Header](badgeIdHeader)
      case (false, true) => Seq[Header](correlationIdHeader)
      case _ => Seq.empty[Header]
    }

    ClientNotification(
      csid = clientSubscriptionId,
      Notification(
        conversationId = conversationId,
        headers = finalHeaders,
        payload = ValidXML.toString(),
        contentType = MimeTypes.XML
      ),
      None,
      Some(TimeReceivedDateTime)
    )
  }

  def createPushNotificationRequestPayload(outboundUrl: String = callbackData.callbackUrl, securityToken: String = callbackData.securityToken,
                                           mayBeBadgeId: Option[String] = Some(badgeId), notificationPayload: NodeSeq = ValidXML,
                                           conversationId: String = validConversationId): JsValue = Json.parse(
    s"""
       |{
       |   "url": "$outboundUrl",
       |   "conversationId": "$conversationId",
       |   "authHeaderToken": "$securityToken",
       |      "outboundCallHeaders": [""".stripMargin
      + mayBeBadgeId.fold("")(badge => s"""  {"name": "X-Badge-Identifier", "value": "$badge"}   """) +
      s"""
         |],
         |   "xmlPayload": "${notificationPayload.toString()}"
         |}
    """.stripMargin)

  def pushNotificationRequest(xml: NodeSeq): PushNotificationRequest = {
    val body = PushNotificationRequestBody(callbackData.callbackUrl, callbackData.securityToken, validConversationId, Seq(badgeIdHeader), xml.toString())
    PushNotificationRequest(validFieldsId, body)
  }

  def failedPushNotificationRequest(xml: NodeSeq): PushNotificationRequest = {
    val body = PushNotificationRequestBody(invalidCallbackData.callbackUrl, callbackData.securityToken, validConversationId, Seq(badgeIdHeader), xml.toString())
    PushNotificationRequest(validFieldsId, body)
  }

  val ValidXML: Elem = <Foo>Bar</Foo>

  lazy val ValidRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER, BASIC_AUTH_HEADER, X_BADGE_ID_HEADER, X_EORI_ID_HEADER)
    .withXmlBody(ValidXML)

  lazy val ValidRequestWithMixedCaseCorrelationId: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER, BASIC_AUTH_HEADER, X_BADGE_ID_HEADER, X_EORI_ID_HEADER, "X-coRRelaTion-iD" -> correlationId)
    .withXmlBody(ValidXML)

  lazy val ValidRequestWithClientIdAbsentInDatabase: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_ABSENT_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER, BASIC_AUTH_HEADER, X_BADGE_ID_HEADER)
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

  lazy val InvalidAuthorizationHeaderRequestWithCorrelationId: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER, RequestHeaders.BASIC_AUTH_HEADER_INVALID, X_CORRELATION_ID_HEADER)
    .withXmlBody(ValidXML)

  lazy val MissingAuthorizationHeaderRequestWithCorrelationId: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER, X_CONVERSATION_ID_HEADER, X_CORRELATION_ID_HEADER)
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

  lazy val X_CDS_CLIENT_ID_HEADER_MixedCase: (String, String) = "X-CdS-ClIenT-iD" -> validFieldsId

  lazy val X_ABSENT_CDS_CLIENT_ID_HEADER: (String, String) = X_CDS_CLIENT_ID_HEADER_NAME -> someFieldsId

  lazy val X_CDS_CLIENT_ID_INVALID: (String, String) = X_CDS_CLIENT_ID_HEADER_NAME -> invalidFieldsId

  lazy val X_CONVERSATION_ID_HEADER: (String, String) = X_CONVERSATION_ID_HEADER_NAME -> validConversationId

  lazy val X_CONVERSATION_ID_INVALID: (String, String) = X_CONVERSATION_ID_HEADER_NAME -> invalidConversationId

  lazy val X_BADGE_ID_HEADER: (String, String) = X_BADGE_ID_HEADER_NAME -> badgeId

  lazy val X_EORI_ID_HEADER: (String, String) = X_EORI_ID_HEADER_NAME -> eoriNumber

  lazy val X_CORRELATION_ID_HEADER: (String, String) = X_CORRELATION_ID_HEADER_NAME -> correlationId

  lazy val CONTENT_TYPE_HEADER: (String, String) = CONTENT_TYPE -> CustomMimeType.XmlCharsetUtf8

  lazy val CONTENT_TYPE_HEADER_LOWERCASE: (String, String) = CONTENT_TYPE -> CustomMimeType.XmlCharsetUtf8.toLowerCase

  lazy val CONTENT_TYPE_HEADER_INVALID: (String, String) = CONTENT_TYPE -> MimeTypes.BINARY

  lazy val ACCEPT_HEADER: (String, String) = ACCEPT -> MimeTypes.XML

  lazy val ACCEPT_HEADER_INVALID: (String, String) = ACCEPT -> MimeTypes.BINARY

  lazy val BASIC_AUTH_HEADER: (String, String) = AUTHORIZATION -> validBasicAuthToken

  lazy val BASIC_AUTH_HEADER_INVALID: (String, String) = AUTHORIZATION -> invalidBasicAuthToken

  lazy val BASIC_AUTH_HEADER_OVERWRITTEN: (String, String) = AUTHORIZATION -> overwrittenBasicAuthToken

  lazy val ValidHeaders: Map[String, String] = Map(
    X_CDS_CLIENT_ID_HEADER,
    X_CONVERSATION_ID_HEADER,
    CONTENT_TYPE_HEADER,
    ACCEPT_HEADER,
    BASIC_AUTH_HEADER,
    X_BADGE_ID_HEADER,
    X_EORI_ID_HEADER
  )

  val LoggingHeaders: Seq[(String, String)] = Seq(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER)
  val LoggingHeadersMixedCase: Seq[(String, String)] = Seq(X_CDS_CLIENT_ID_HEADER_MixedCase, X_CONVERSATION_ID_HEADER)
  val LoggingHeadersWithAuth: Seq[(String, String)] = Seq(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, BASIC_AUTH_HEADER)
  val LoggingHeadersWithAuthOverwritten: Seq[(String, String)] = Seq(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, BASIC_AUTH_HEADER_OVERWRITTEN)

  val NoHeaders: Map[String, String] = Map[String, String]()
}
