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

package util

import java.net.URL
import java.time.ZonedDateTime
import java.util.UUID
import com.typesafe.config.{Config, ConfigFactory}
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.http.HeaderNames._
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AnyContentAsEmpty, AnyContentAsXml, Headers}
import play.api.test.FakeRequest
import play.api.test.Helpers.{DELETE, GET}
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse
import uk.gov.hmrc.customs.notification.models
import uk.gov.hmrc.customs.notification.models.repo.NotificationWorkItem
import uk.gov.hmrc.customs.notification.models.requests.{MetaDataRequest, PushNotificationRequest, PushNotificationRequestBody}
import uk.gov.hmrc.customs.notification.models.{ApiSubscriptionFields, BadgeId, CallbackUrl, ClientId, ClientNotification, ClientSubscriptionId, ConversationId, CorrelationId, DeclarantCallbackData, FunctionCode, Header, IssueDateTime, Mrn, Notification, NotificationId, Submitter, repo}
import uk.gov.hmrc.customs.notification.util.DateTimeHelper
import uk.gov.hmrc.customs.notification.util.HeaderNames.{ISSUE_DATE_TIME_HEADER, X_BADGE_ID_HEADER_NAME, X_CDS_CLIENT_ID_HEADER_NAME, X_CLIENT_ID_HEADER_NAME, X_CONVERSATION_ID_HEADER_NAME, X_CORRELATION_ID_HEADER_NAME, X_SUBMITTER_ID_HEADER_NAME}
import uk.gov.hmrc.mongo.workitem.ProcessingStatus._
import uk.gov.hmrc.mongo.workitem.WorkItem
import util.CustomsNotificationMetricsTestData.UtcZoneId
import util.RequestHeaders._
import util.TestData._
import scala.xml.{Elem, NodeSeq}

object TestData {
  val validConversationId: String = "eaca01f9-ec3b-4ede-b263-61b626dde231"
  val validConversationIdUUID: UUID = UUID.fromString(validConversationId)
  val conversationId = ConversationId(validConversationIdUUID)
  val invalidConversationId: String = "I-am-not-a-valid-uuid"
  val validFieldsId = "ffff01f9-ec3b-4ede-b263-61b626dde232"
  val someFieldsId = "ccc9f676-c752-4e77-b86a-b27a3b33fceb"
  val clientSubscriptionId = ClientSubscriptionId(UUID.fromString(validFieldsId))
  val CsidOne = ClientSubscriptionId(UUID.fromString("eaca01f9-ec3b-4ede-b263-61b626dde231"))
  val invalidFieldsId = "I-am-not-a-valid-type-4-uuid"
  val validNotificationId = "58373a04-2c45-4f43-9ea2-74e56be2c6d7"
  val notificationId = NotificationId(UUID.fromString(validNotificationId))
  val basicAuthTokenValue = "YmFzaWN1c2VyOmJhc2ljcGFzc3dvcmQ="
  val validBasicAuthToken = s"Basic $basicAuthTokenValue"
  val invalidBasicAuthToken = "I-am-not-a-valid-auth-token"
  val overwrittenBasicAuthToken = "value-not-logged"
  val clientIdString1 = "ClientId"
  val clientId1 = ClientId(clientIdString1)
  val clientIdString2 = "ClientId2"
  val clientId2 = ClientId(clientIdString2)
  val ClientIdStringOne = "ClientIdOne"
  val ClientIdOne = ClientId(ClientIdStringOne)
  type EmulatedServiceFailure = UnsupportedOperationException
  val emulatedServiceFailure = new EmulatedServiceFailure("Emulated service failure.")
  val callbackUrl = CallbackUrl(Some(new URL("http://callback")))
  val internalCallbackUrl = new URL("http://localhost:11111" + ExternalServicesConfiguration.InternalPushServiceContext)
  val invalidCallbackUrl = "Im-Invalid"
  val securityToken = "securityToken"
  val callbackData = DeclarantCallbackData(callbackUrl, securityToken)
  val internalCallbackData = DeclarantCallbackData(CallbackUrl(Some(internalCallbackUrl)), securityToken)
  val badgeIdHeader = Header(X_BADGE_ID_HEADER_NAME, badgeId)
  val dateHeader = Header(ISSUE_DATE_TIME_HEADER, issueDateTime)
  val url = "http://some-url"
  val errorMsg = "ERROR"
  val warnMsg = "WARN"
  val infoMsg = "INFO"
  val debugMsg = "DEBUG"
  val badgeId = "ABCDEF1234"
  val submitterNumber = "IAMSUBMITTER"
  val userAgent = "customs-notification"
  val correlationId = "CORRID2234"
  val functionCode = "01"
  val issueDateTime = "20190925104103Z"
  val mrn = "19GB3955NQ36213969"
  val externalPushNotificationRequestBodyHeaders: Seq[Header] = Seq(badgeIdHeader)
  val internalPushNotificationRequestBodyHeaders: Seq[Header] = Seq(badgeIdHeader, dateHeader)
  val ValidXML: Elem = <Foo>Bar</Foo>
  val externalPushNotificationRequest: PushNotificationRequest = pushNotificationRequest(ValidXML, headers = externalPushNotificationRequestBodyHeaders)
  val internalPushNotificationRequest: PushNotificationRequest = pushNotificationRequest(ValidXML, internalCallbackData, internalPushNotificationRequestBodyHeaders)
  val somePushNotificationRequest: Option[PushNotificationRequest] = Some(externalPushNotificationRequest)
  val Year = 2017
  val MonthOfYear = 7
  val DayOfMonth = 4
  val HourOfDay = 13
  val MinuteOfHour = 45
  val TimeReceivedZoned = ZonedDateTime.of(2016, 1, 30, 23, 46, 59, 0, UtcZoneId)
  val TimeReceivedDateTime = DateTimeHelper.toDateTime(TimeReceivedZoned)
  val TimeReceivedInstant = TimeReceivedZoned.toInstant
  val MetricsStartTimeZoned = ZonedDateTime.of(2016, 1, 30, 23, 44, 59, 0, UtcZoneId)
  val MetricsStartTimeDateTime: DateTime =  DateTimeHelper.toDateTime(TimeReceivedZoned)
  val validClientSubscriptionId1String: String = "eaca01f9-ec3b-4ede-b263-61b626dde232"
  val validClientSubscriptionId1UUID: UUID = UUID.fromString(validClientSubscriptionId1String)
  val validClientSubscriptionId1 = ClientSubscriptionId(validClientSubscriptionId1UUID)
  val validClientSubscriptionId2String: String = "eaca01f9-ec3b-4ede-b263-61b626dde233"
  val validClientSubscriptionId2UUID: UUID = UUID.fromString(validClientSubscriptionId2String)
  val validClientSubscriptionId2 = ClientSubscriptionId(validClientSubscriptionId2UUID)
  val payload1 = "<foo1></foo1>"
  val payload2 = "<foo2></foo2>"
  val payload3 = "<foo3></foo3>"
  val requestMetaDataHeaders = Seq(Header(X_BADGE_ID_HEADER_NAME, badgeId), Header(X_SUBMITTER_ID_HEADER_NAME, submitterNumber), Header(X_CORRELATION_ID_HEADER_NAME, correlationId), Header(ISSUE_DATE_TIME_HEADER, issueDateTime))
  val headers = Seq(Header("h1","v1"), Header("h2", "v2"))
  val notification1 = Notification(Some(notificationId), conversationId, requestMetaDataHeaders, payload1, MimeTypes.XML)
  private val XmlCharsetUtf8 = MimeTypes.XML + "; charset=UTF-8"
  val notification2 = Notification(Some(notificationId), conversationId, headers, payload2, XmlCharsetUtf8)
  val notification3 = Notification(Some(notificationId), conversationId, headers, payload3, XmlCharsetUtf8)
  val client1Notification1 = ClientNotification(validClientSubscriptionId1, notification1, None, Some(TimeReceivedDateTime))
  val client1Notification2 = models.ClientNotification(validClientSubscriptionId1, notification2, None, Some(TimeReceivedDateTime))
  val client1Notification3 = models.ClientNotification(validClientSubscriptionId1, notification3, None, Some(TimeReceivedDateTime))
  val client2Notification1 = models.ClientNotification(validClientSubscriptionId2, notification1, None, Some(TimeReceivedDateTime))
  val client1Notification1WithTimeReceived = models.ClientNotification(validClientSubscriptionId1, notification1, Some(TimeReceivedDateTime), None)
  val client2Notification1WithTimeReceived = models.ClientNotification(validClientSubscriptionId2, notification1, Some(TimeReceivedDateTime), None)
  val requestMetaData = MetaDataRequest(validClientSubscriptionId1, conversationId, notificationId, Some(clientId1),Some(BadgeId(badgeId)),
    Some(Submitter(submitterNumber)), Some(CorrelationId(correlationId)), Some(FunctionCode(functionCode)), Some(IssueDateTime(issueDateTime)), Some(Mrn(mrn)), TimeReceivedZoned)
  val NotificationWorkItem1 = repo.NotificationWorkItem(validClientSubscriptionId1, clientId1, None, notification = notification1)
  val NotificationWorkItem2 = NotificationWorkItem(validClientSubscriptionId2, clientId1, notification = notification2)
  val NotificationWorkItem3 = repo.NotificationWorkItem(validClientSubscriptionId2, clientId2, notification = notification2)
  val NotificationWorkItemWithMetricsTime1 = NotificationWorkItem1.copy(metricsStartDateTime = Some(TimeReceivedDateTime))
  val WorkItem1 = WorkItem(new ObjectId("5c46f7d70100000100ef835a"), TimeReceivedInstant, TimeReceivedInstant, TimeReceivedInstant, ToDo, 0, NotificationWorkItemWithMetricsTime1)
  val WorkItem2 = WorkItem1.copy(item = NotificationWorkItem2)
  val WorkItem3 = WorkItem1.copy(failureCount = 1)
  val internalNotification = Notification(Some(notificationId), ConversationId(UUID.fromString(internalPushNotificationRequest.body.conversationId)), internalPushNotificationRequest.body.outboundCallHeaders, ValidXML.toString(), "application/xml")
  val internalNotificationWorkItem = repo.NotificationWorkItem(clientSubscriptionId, clientId1, None, internalNotification)
  val internalWorkItem = WorkItem(new ObjectId("5c46f7d70100000100ef835a"), TimeReceivedInstant, TimeReceivedInstant, TimeReceivedInstant, ToDo, 0, internalNotificationWorkItem)
  val NotUsedBsonId = "123456789012345678901234"
  val DeclarantCallbackDataOneForPush = DeclarantCallbackData(CallbackUrl(Some(new URL("http://URL"))), "SECURITY_TOKEN")
  val DeclarantCallbackDataOneForPull = DeclarantCallbackData(CallbackUrl(None), "SECURITY_TOKEN")
  val ApiSubscriptionFieldsOneForPush = ApiSubscriptionFields(clientId1.toString, DeclarantCallbackDataOneForPush)
  val ApiSubscriptionFieldsOneForPull = models.ApiSubscriptionFields(clientId1.toString, DeclarantCallbackDataOneForPull)
  val PushNotificationRequest1 = PushNotificationRequest(validClientSubscriptionId1.id.toString, PushNotificationRequestBody(CallbackUrl(Some(new URL("http://URL"))), "SECURITY_TOKEN", conversationId.id.toString, requestMetaDataHeaders, payload1))

  def clientNotification(withBadgeId: Boolean = true, withCorrelationId: Boolean = true, withNotificationId: Boolean = true): ClientNotification = {
    val correlationIdHeader = Header("x-cOrRelaTion-iD", correlationId)
    val finalHeaders = (withBadgeId, withCorrelationId) match {
      case (true, true) => Seq[Header](badgeIdHeader, correlationIdHeader, dateHeader)
      case (true, false) => Seq[Header](badgeIdHeader)
      case (false, true) => Seq[Header](correlationIdHeader)
      case _ => Seq.empty[Header]
    }
    val maybeNotificationId = if (withNotificationId) Some(notificationId) else None
    models.ClientNotification(
      csid = clientSubscriptionId,
      Notification(
        maybeNotificationId,
        conversationId = conversationId,
        headers = finalHeaders,
        payload = ValidXML.toString(),
        contentType = MimeTypes.XML
      ),
      None,
      Some(TimeReceivedDateTime)
    )
  }

  def createPushNotificationRequestPayload(outboundUrl: CallbackUrl = callbackData.callbackUrl, securityToken: String = callbackData.securityToken,
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

  def pushNotificationRequest(xml: NodeSeq, cd: DeclarantCallbackData = callbackData, headers: Seq[Header]): PushNotificationRequest = {
    val body = PushNotificationRequestBody(
      cd.callbackUrl,
      cd.securityToken,
      validConversationId,
      headers,
      xml.toString())
    PushNotificationRequest(validFieldsId, body)
  }
  val ValidRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER, BASIC_AUTH_HEADER, X_BADGE_ID_HEADER, X_SUBMITTER_ID_HEADER)
    .withXmlBody(ValidXML)
  val ValidRequestWithMixedCaseCorrelationId: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER, BASIC_AUTH_HEADER, X_BADGE_ID_HEADER, X_SUBMITTER_ID_HEADER, "X-coRRelaTion-iD" -> correlationId)
    .withXmlBody(ValidXML)
  val ValidRequestWithClientIdAbsentInDatabase: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_ABSENT_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER, BASIC_AUTH_HEADER, X_BADGE_ID_HEADER)
    .withXmlBody(ValidXML)
  val InvalidConversationIdHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, RequestHeaders.X_CONVERSATION_ID_INVALID, CONTENT_TYPE_HEADER, ACCEPT_HEADER)
    .withXmlBody(ValidXML)
  val MissingConversationIdHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER)
    .withXmlBody(ValidXML)
  val InvalidAuthorizationHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER, RequestHeaders.BASIC_AUTH_HEADER_INVALID)
    .withXmlBody(ValidXML)
  val InvalidAuthorizationHeaderRequestWithCorrelationId: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER, RequestHeaders.BASIC_AUTH_HEADER_INVALID, X_CORRELATION_ID_HEADER)
    .withXmlBody(ValidXML)
  val MissingAuthorizationHeaderRequestWithCorrelationId: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER, X_CONVERSATION_ID_HEADER, X_CORRELATION_ID_HEADER)
    .withXmlBody(ValidXML)
  val MissingAuthorizationHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER, X_CONVERSATION_ID_HEADER)
    .withXmlBody(ValidXML)
  val InvalidClientIdHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(RequestHeaders.X_CDS_CLIENT_ID_INVALID, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER, ACCEPT_HEADER)
    .withXmlBody(ValidXML)
  val MissingClientIdHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(RequestHeaders.X_CONVERSATION_ID_INVALID, CONTENT_TYPE_HEADER, ACCEPT_HEADER)
    .withXmlBody(ValidXML)
  val InvalidContentTypeHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, RequestHeaders.CONTENT_TYPE_HEADER_INVALID, ACCEPT_HEADER)
    .withXmlBody(ValidXML)
  val MissingAcceptHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER)
    .withXmlBody(ValidXML)
  val InvalidAcceptHeaderRequest: FakeRequest[AnyContentAsXml] = FakeRequest()
    .withHeaders(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, CONTENT_TYPE_HEADER, RequestHeaders.ACCEPT_HEADER_INVALID)
    .withXmlBody(ValidXML)
  val ValidBlockedCountRequest = FakeRequest(GET, "/customs-notification/blocked-count", Headers(X_CLIENT_ID_HEADER), AnyContentAsEmpty)
  val InvalidBlockedCountRequest = FakeRequest(GET, "/customs-notification/blocked-count", Headers(), AnyContentAsEmpty)
  val ValidDeleteBlockedRequest = FakeRequest(DELETE, "/customs-notification/blocked-flag", Headers(X_CLIENT_ID_HEADER), AnyContentAsEmpty)
  val InvalidDeleteBlockedRequest = FakeRequest(DELETE, "/customs-notification/blocked-flag", Headers(), AnyContentAsEmpty)
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
  val internalServerError = ErrorResponse.errorInternalServerError("Internal Server Error").XmlResult
  val invalidConfigMissingBasicAuthToken: Config = ConfigFactory.parseString("")
}

object RequestHeaders {
  private val XmlCharsetUtf8 = MimeTypes.XML + "; charset=UTF-8"
  val X_CDS_CLIENT_ID_HEADER: (String, String) = X_CDS_CLIENT_ID_HEADER_NAME -> validFieldsId
  val X_CDS_CLIENT_ID_HEADER_MixedCase: (String, String) = "X-CdS-ClIenT-iD" -> validFieldsId
  val X_ABSENT_CDS_CLIENT_ID_HEADER: (String, String) = X_CDS_CLIENT_ID_HEADER_NAME -> someFieldsId
  val X_CDS_CLIENT_ID_INVALID: (String, String) = X_CDS_CLIENT_ID_HEADER_NAME -> invalidFieldsId
  val X_CONVERSATION_ID_HEADER: (String, String) = X_CONVERSATION_ID_HEADER_NAME -> validConversationId
  val X_CONVERSATION_ID_INVALID: (String, String) = X_CONVERSATION_ID_HEADER_NAME -> invalidConversationId
  val X_BADGE_ID_HEADER: (String, String) = X_BADGE_ID_HEADER_NAME -> badgeId
  val X_SUBMITTER_ID_HEADER: (String, String) = X_SUBMITTER_ID_HEADER_NAME -> submitterNumber
  val X_CORRELATION_ID_HEADER: (String, String) = X_CORRELATION_ID_HEADER_NAME -> correlationId
  val CONTENT_TYPE_HEADER: (String, String) = CONTENT_TYPE -> XmlCharsetUtf8
  val CONTENT_TYPE_HEADER_LOWERCASE: (String, String) = CONTENT_TYPE -> XmlCharsetUtf8.toLowerCase
  val CONTENT_TYPE_HEADER_INVALID: (String, String) = CONTENT_TYPE -> MimeTypes.BINARY
  val ACCEPT_HEADER: (String, String) = ACCEPT -> MimeTypes.XML
  val ACCEPT_HEADER_INVALID: (String, String) = ACCEPT -> MimeTypes.BINARY
  val BASIC_AUTH_HEADER: (String, String) = AUTHORIZATION -> validBasicAuthToken
  val BASIC_AUTH_HEADER_INVALID: (String, String) = AUTHORIZATION -> invalidBasicAuthToken
  val BASIC_AUTH_HEADER_OVERWRITTEN: (String, String) = AUTHORIZATION -> overwrittenBasicAuthToken
  val X_CLIENT_ID_HEADER: (String, String) = X_CLIENT_ID_HEADER_NAME -> clientIdString1
  val ValidHeaders: Map[String, String] = Map(
    X_CDS_CLIENT_ID_HEADER,
    X_CONVERSATION_ID_HEADER,
    CONTENT_TYPE_HEADER,
    ACCEPT_HEADER,
    BASIC_AUTH_HEADER,
    X_BADGE_ID_HEADER,
    X_SUBMITTER_ID_HEADER)
  val LoggingHeaders: Seq[(String, String)] = Seq(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER)
  val LoggingHeadersMixedCase: Seq[(String, String)] = Seq(X_CDS_CLIENT_ID_HEADER_MixedCase, X_CONVERSATION_ID_HEADER)
  val LoggingHeadersWithAuth: Seq[(String, String)] = Seq(X_CDS_CLIENT_ID_HEADER, X_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, BASIC_AUTH_HEADER)
  val LoggingHeadersWithAuthOverwritten: Seq[(String, String)] = Seq(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER, BASIC_AUTH_HEADER_OVERWRITTEN)
  val NoHeaders: Map[String, String] = Map[String, String]()
}
