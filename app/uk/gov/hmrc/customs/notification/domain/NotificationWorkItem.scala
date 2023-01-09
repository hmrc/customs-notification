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

package uk.gov.hmrc.customs.notification.domain

import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.mongo.play.json.formats.{MongoFormats, MongoJodaFormats}

case class NotificationWorkItem(_id: ClientSubscriptionId,
                                clientId: ClientId,
                                metricsStartDateTime: Option[DateTime] = None,
                                notification: Notification
) extends HasId with HasClientSubscriptionId {
  override def idName: String = "conversationId"
  override def idValue: String = notification.conversationId.toString
  override def clientSubscriptionId: ClientSubscriptionId = _id

}
object NotificationWorkItem {
  implicit val dateFormats = MongoJodaFormats.dateTimeFormat
  implicit val objectIdFormats: Format[ObjectId] = MongoFormats.objectIdFormat
  implicit val format = Json.format[NotificationWorkItem]

}
