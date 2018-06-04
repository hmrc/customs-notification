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

import play.api.mvc.Headers
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames.{X_BADGE_ID_HEADER_NAME, X_CDS_CLIENT_ID_HEADER_NAME, X_CONVERSATION_ID_HEADER_NAME}
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain.{DeclarantCallbackData, Header, PublicNotificationRequest, PublicNotificationRequestBody}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.NodeSeq

@Singleton
class PublicNotificationRequestService @Inject()(apiSubscriptionFieldsConnector: ApiSubscriptionFieldsConnector) {

  def createRequest(xml: NodeSeq, clientData: DeclarantCallbackData, metaData: RequestMetaData): PublicNotificationRequest = {
    ???
  }

  def createRequest(xml: NodeSeq, headers: Headers)(implicit hc: HeaderCarrier): Future[Option[PublicNotificationRequest]] = {
    for {
      (clientId, conversationId, mayBeBadgeId) <- getHeaders(headers)
      maybeClientData <- getClientData(clientId)
      maybePublicNotificationRequest <- maybePublicNotificationRequest(clientId, xml, conversationId, mayBeBadgeId, maybeClientData)
    } yield {
      maybePublicNotificationRequest
    }
  }

  private def getHeaders(headers: Headers): Future[(String, String, Option[String])] = {
    // headers have been validated so safe to do a naked get except badgeId which is optional
    Future.successful((headers.get(X_CDS_CLIENT_ID_HEADER_NAME).get,
      headers.get(X_CONVERSATION_ID_HEADER_NAME).get,
      headers.get(X_BADGE_ID_HEADER_NAME).flatMap(x => if (x.isEmpty) None else Some(x)))
    )
  }

  private def getClientData(clientId: String)(implicit hc: HeaderCarrier) = {
    apiSubscriptionFieldsConnector.getClientData(clientId)
  }

  // xml.toString() may be processor intensive for large payloads so we do not block
  private def maybePublicNotificationRequest(clientId: String,
                                             xml: NodeSeq,
                                             conversationId: String,
                                             mayBeBadgeId: Option[String],
                                             maybeClientData: Option[DeclarantCallbackData]): Future[Option[PublicNotificationRequest]] = {
    Future {
      maybeClientData.fold[Option[PublicNotificationRequest]](None) { clientData =>
        val outboundCallHeaders: Seq[Header] = mayBeBadgeId.fold(Seq[Header]())(x => Seq(Header(X_BADGE_ID_HEADER_NAME, x)))
        val request = PublicNotificationRequest(clientId, PublicNotificationRequestBody(clientData.callbackUrl, clientData.securityToken, conversationId, outboundCallHeaders, xml.toString()))
        Some(request)
      }
    }
  }

}
