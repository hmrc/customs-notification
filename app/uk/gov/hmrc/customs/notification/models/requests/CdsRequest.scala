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

///*
// * Copyright 2023 HM Revenue & Customs
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
//package uk.gov.hmrc.customs.notification.models.requests
//
//import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
//import play.api.http.MimeTypes
//import play.api.libs.json.Writes.StringWrites
//import play.api.libs.json._
//import uk.gov.hmrc.customs.notification.models._
//import uk.gov.hmrc.customs.notification.util.HeaderNames._
//import uk.gov.hmrc.http.{Authorization, HeaderCarrier}
//
//import java.net.URL
//import java.time.ZonedDateTime
//
//// TODO: Writes tests
//sealed trait CdsRequest {
//  type POST_BODY
//  type RESPONSE
//
//  def url: URL
//
//  def transformHeaderCarrier(hc: HeaderCarrier): HeaderCarrier
//
//  def descriptor: String
//
//  def shouldSendHttpRequestToAuditing: Boolean
//}
//
//trait PostRequest extends CdsRequest {
//  final type RESPONSE = Unit
//
//  def body: POST_BODY
//
//  def writes: Writes[POST_BODY]
//  def descriptor: String
//}
//
//trait GetRequest extends CdsRequest {
//  final  type POST_BODY = Nothing
//}
//
//case class ApiSubscriptionFieldsRequest(clientSubscriptionId: ClientSubscriptionId,
//                                        baseUrl: URL) extends GetRequest {
//  val shouldSendHttpRequestToAuditing = true
//  type RESPONSE = ApiSubscriptionFields
//  val url: URL = new URL(s"${baseUrl.toString}/$clientSubscriptionId")
//  val descriptor: String = "API subscription fields"
//  def transformHeaderCarrier(hc: HeaderCarrier): HeaderCarrier =
//    hc.withExtraHeaders(
//      CONTENT_TYPE -> MimeTypes.JSON,
//      ACCEPT -> MimeTypes.JSON
//    )
//}
//
//case class PullNotificationRequest(clientSubscriptionId: ClientSubscriptionId,
//                                   notification: Notification,
//                                   url: URL) extends PostRequest {
//  type POST_BODY = String
//  val body: String = notification.payload
//  val writes: Writes[String] = StringWrites
//  val descriptor: String = "pull enqueue"
//  val shouldSendHttpRequestToAuditing = true
//
//  def transformHeaderCarrier(hc: HeaderCarrier): HeaderCarrier =
//    HeaderCarrier(
//      requestId = hc.requestId,
//      extraHeaders = List(
//        CONTENT_TYPE -> MimeTypes.XML,
//        X_CONVERSATION_ID_HEADER_NAME -> notification.conversationId.toString,
//        SUBSCRIPTION_FIELDS_ID_HEADER_NAME -> clientSubscriptionId.toString,
//        NOTIFICATION_ID_HEADER_NAME -> notification.notificationId.toString) ++
//        notification.headers.collectFirst { case h@Header(X_BADGE_ID_HEADER_NAME, _) => h.toTuple } ++
//        notification.headers.collectFirst { case h@Header(X_CORRELATION_ID_HEADER_NAME, _) => h.toTuple }
//    )
//
//}
//
//case class InternalPushNotificationRequest(conversationId: ConversationId,
//                                           authToken: Authorization,
//                                           xmlPayload: String,
//                                           outboundCallHeaders: Seq[Header],
//                                           url: URL) extends PostRequest {
//  type POST_BODY = String
//  val body: String = xmlPayload
//  val writes: Writes[String] = StringWrites
//  val descriptor: String = "internal push"
//  val shouldSendHttpRequestToAuditing = true
//
//  def transformHeaderCarrier(hc: HeaderCarrier): HeaderCarrier =
//    hc
//      .copy(authorization = Some(authToken))
//      .withExtraHeaders(
//        List(
//          CONTENT_TYPE -> MimeTypes.XML,
//          ACCEPT -> MimeTypes.XML,
//          X_CONVERSATION_ID_HEADER_NAME -> conversationId.toString
//        ) ++ outboundCallHeaders.map(_.toTuple): _*
//      )
//
//}
//
//case class ExternalPushNotificationRequest(notificationId: NotificationId,
//                                           conversationId: ConversationId,
//                                           url: URL,
//                                           outboundCallHeaders: Seq[Header],
//                                           authHeaderToken: Authorization,
//                                           xmlPayload: String) extends PostRequest {
//  type POST_BODY = this.type
//  val body: this.type = this
//  val writes: Writes[this.type] = Json.writes[this.type]
//  val descriptor: String = "external push"
//  val shouldSendHttpRequestToAuditing = true
//
//   def transformHeaderCarrier(hc: HeaderCarrier): HeaderCarrier =
//    hc.withExtraHeaders(
//      ACCEPT -> MimeTypes.JSON,
//      CONTENT_TYPE -> MimeTypes.JSON,
//      NOTIFICATION_ID_HEADER_NAME -> notificationId.toString
//    )
//}
//
//case class MetricsRequest(conversationId: ConversationId,
//                          eventStart: ZonedDateTime,
//                          eventEnd: ZonedDateTime,
//                          url: URL) extends PostRequest {
//
//  val shouldSendHttpRequestToAuditing = false
//  type POST_BODY = this.type
//  val body: this.type = this
//  val descriptor: String = "metrics"
//
//   val writes: Writes[this.type] = {
//    val additionalEventType = "eventType" -> JsString("NOTIFICATION")
//    Json.writes[this.type].transform((o: JsObject) => o + additionalEventType - "url")
//  }
//
//   def transformHeaderCarrier(hc: HeaderCarrier): HeaderCarrier =
//    HeaderCarrier(
//      requestId = hc.requestId,
//      extraHeaders = List(
//        CONTENT_TYPE -> MimeTypes.JSON,
//        ACCEPT -> MimeTypes.JSON
//      )
//    )
//}
