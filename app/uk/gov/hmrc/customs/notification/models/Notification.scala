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

package uk.gov.hmrc.customs.notification.models

import play.api.libs.json.{Format, Json}
import play.api.mvc.Headers

case class Notification(notificationId: Option[NotificationId],
                        conversationId: ConversationId,
                        headers: Seq[Header],
                        payload: String,
                        contentType: String,
                        mostRecentPushPullHttpStatus: Option[Int] = None){
  private val caseInsensitiveHeaders = Headers(headers.map { h => h.name -> h.value }: _*)
  override def toString: String = s"notificationId: ${notificationId.toString}, conversationId: ${conversationId.toString}, headers: ${headers.toString()}, contentType: $contentType"
  def getHeader(name: String): Option[Header] = caseInsensitiveHeaders.get(name).map(Header(name, _))
  def getHeaderAsTuple(headerName: String): Option[(String, String)] = getHeader(headerName).map { h => h.name -> h.value }
}

object Notification {
  implicit val notificationJF: Format[Notification] = Json.format[Notification]
}
