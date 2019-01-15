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

package uk.gov.hmrc.customs.notification.services

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain.{DeclarantCallbackData, Header, PushNotificationRequest, PushNotificationRequestBody}

import scala.xml.NodeSeq

@Singleton
class PushNotificationRequestService @Inject()(apiSubscriptionFieldsConnector: ApiSubscriptionFieldsConnector) {

  def createRequest(notificationXML: NodeSeq, clientData: DeclarantCallbackData, metaData: RequestMetaData): PushNotificationRequest = {

    val outboundCallHeaders: Seq[Header] = (metaData.mayBeBadgeId ++ metaData.mayBeEoriNumber).toSeq

    PushNotificationRequest(metaData.clientId.toString(),
      PushNotificationRequestBody(
        clientData.callbackUrl,
        clientData.securityToken,
        metaData.conversationId.toString(),
        outboundCallHeaders,
        notificationXML.toString()))
  }
}
