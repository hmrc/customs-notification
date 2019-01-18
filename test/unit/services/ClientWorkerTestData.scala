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

package unit.services

import java.util.UUID

import org.joda.time.DateTime
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.repo.LockOwnerId

object ClientWorkerTestData {
  val ClientIdStringOne = "ClientIdOne"
  val ClientIdStringTwo = "ClientIdTwo"
  val ClientIdOne = ClientId(ClientIdStringOne)
  val ClientIdTwo = ClientId(ClientIdStringTwo)
  val CsidOne = ClientSubscriptionId(UUID.fromString("eaca01f9-ec3b-4ede-b263-61b626dde231"))
  val CsidTwo = ClientSubscriptionId(UUID.fromString("eaca01f9-ec3b-4ede-b263-61b626dde232"))
  val CsidThree = ClientSubscriptionId(UUID.fromString("eaca01f9-ec3b-4ede-b263-61b626dde233"))
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
  val ClientNotificationOne = ClientNotification(CsidOne, NotificationOne, Some(TimeStampOne), None)
  val ClientNotificationOneWithMetricsTime = ClientNotification(CsidOne, NotificationOne, Some(TimeStampOne), Some(TimeStampOne.minusSeconds(2)))
  val ClientNotificationTwo = ClientNotification(CsidOne, NotificationTwo, Some(TimeStampTwo), None)
  val DeclarantCallbackDataOne = DeclarantCallbackData("URL", "SECURITY_TOKEN")
  val DeclarantCallbackDataOneWithEmptyUrl = DeclarantCallbackDataOne.copy(callbackUrl = "")
  val DeclarantCallbackDataTwo = DeclarantCallbackData("URL2", "SECURITY_TOKEN2")
  val ApiSubscriptionFieldsResponseOne = ApiSubscriptionFieldsResponse(ClientIdStringOne, DeclarantCallbackDataOne)
  val ApiSubscriptionFieldsResponseOneWithEmptyUrl = ApiSubscriptionFieldsResponse(ClientIdStringOne, DeclarantCallbackDataOneWithEmptyUrl)
  val ApiSubscriptionFieldsResponseTwo = ApiSubscriptionFieldsResponse(ClientIdStringTwo, DeclarantCallbackDataTwo)
  val pnrOne = PushNotificationRequest(CsidOne.id.toString, PushNotificationRequestBody("URL", "SECURITY_TOKEN", ConversationIdOne.id.toString, Headers, PayloadOne))
  val pnrTwo = PushNotificationRequest(CsidOne.id.toString, PushNotificationRequestBody("URL2", "SECURITY_TOKEN2", ConversationIdTwo.id.toString, Headers, PayloadTwo))
}



