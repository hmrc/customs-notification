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

package uk.gov.hmrc.customs.notification.services

import javax.inject.{Inject, Singleton}

import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames.X_BADGE_ID_HEADER_NAME
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain.{DeclarantCallbackData, Header, PublicNotificationRequest, PublicNotificationRequestBody}

import scala.xml.NodeSeq

@Singleton
class PublicNotificationRequestService @Inject()(apiSubscriptionFieldsConnector: ApiSubscriptionFieldsConnector) {

  def createRequest(notificationXML: NodeSeq, clientData: DeclarantCallbackData, metaData: RequestMetaData): PublicNotificationRequest = {

    val outboundCallHeaders: Seq[Header] = metaData.mayBeBadgeId match {
      case x if x == None || x.get.isEmpty => Seq()
      case Some(badgeId) => Seq(Header(X_BADGE_ID_HEADER_NAME, badgeId))
    }

    PublicNotificationRequest(metaData.clientId,
      PublicNotificationRequestBody(
        clientData.callbackUrl,
        clientData.securityToken,
        metaData.conversationId.toString,
        outboundCallHeaders,
        notificationXML.toString()))
  }
}
