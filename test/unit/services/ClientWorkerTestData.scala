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

import java.util.UUID

import org.joda.time.DateTime
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.repo.LockOwnerId

object ClientWorkerTestData {
  val CsidOne = ClientSubscriptionId(UUID.fromString("eaca01f9-ec3b-4ede-b263-61b626dde231"))
  val CsidTwo = ClientSubscriptionId(UUID.fromString("eaca01f9-ec3b-4ede-b263-61b626dde232"))
  val ConversationIdOne = ConversationId(UUID.fromString("caca01f9-ec3b-4ede-b263-61b626dde231"))
  val ConversationIdTwo = ConversationId(UUID.fromString("caca01f9-ec3b-4ede-b263-61b626dde232"))
  val CsidOneLockOwnerId = LockOwnerId(CsidOne.id.toString)
  val Headers = Seq(Header("h1", "v1"))
  val PayloadOne = "PAYLOAD_ONE"
  val PayloadTwo = "PAYLOAD_TWO"
  val ContentType = "CONTENT_TYPE"
  val NotificationOne = Notification(ConversationIdOne, Headers, PayloadOne, ContentType)
  val NotificationTwo = Notification(ConversationIdTwo, Headers, PayloadTwo, ContentType)
  val TimeStampOne = DateTime.now
  private val oneThousand = 1000
  val TimeStampTwo = TimeStampOne.plus(oneThousand)
  val ClientNotificationOne = ClientNotification(CsidOne, NotificationOne, Some(TimeStampOne))
  val ClientNotificationTwo = ClientNotification(CsidOne, NotificationTwo, Some(TimeStampTwo))
  val DeclarantCallbackDataOne = DeclarantCallbackData("URL", "SECURITY_TOKEN")
  val pnrOne = PublicNotificationRequest(CsidOne.id.toString, PublicNotificationRequestBody("URL", "SECURITY_TOKEN", ConversationIdOne.id.toString, Headers, PayloadOne))
  val pnrTwo = PublicNotificationRequest(CsidOne.id.toString, PublicNotificationRequestBody("URL", "SECURITY_TOKEN", ConversationIdTwo.id.toString, Headers, PayloadTwo))
}



