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

package uk.gov.hmrc.customs.notification.controllers

import java.time.ZonedDateTime
import java.util.UUID

import javax.inject.{Inject, Singleton}
import play.api.mvc._
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse._
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.controllers.CustomErrorResponses.ErrorCdsClientIdNotFound
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.{CustomsNotificationClientWorkerService, CustomsNotificationService, CustomsNotificationWorkItemService, DateTimeService}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.controller.BaseController

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.NodeSeq

case class RequestMetaData(clientSubscriptionId: ClientSubscriptionId,
                           conversationId: ConversationId,
                           mayBeBadgeId: Option[Header],
                           mayBeEoriNumber: Option[Header],
                           maybeCorrelationId: Option[Header],
                           startTime: ZonedDateTime)

abstract class CustomsNotificationController @Inject()(val logger: NotificationLogger,
                                                       val customsNotificationService: CustomsNotificationService,
                                                       val callbackDetailsConnector: ApiSubscriptionFieldsConnector,
                                                       val configService: CustomsNotificationConfig,
                                                       val dateTimeService: DateTimeService)
               extends BaseController with HeaderValidator {

  override val notificationLogger: NotificationLogger = logger
  private lazy val maybeBasicAuthToken: Option[String] = configService.maybeBasicAuthToken
  private lazy val xmlValidationErrorMessage = "Request body does not contain well-formed XML."

  def submit(): Action[AnyContent] = {
    val startTime = dateTimeService.zonedDateTimeUtc
    validateHeaders(maybeBasicAuthToken) async {
      implicit request =>
        request.body.asXml match {
          case Some(xml) =>
            process(xml, requestMetaData(request.headers, startTime))
          case None =>
            notificationLogger.error(xmlValidationErrorMessage)
            Future.successful(errorBadRequest(xmlValidationErrorMessage).XmlResult)
        }
    }
  }

  private def requestMetaData(headers: Headers, startTime: ZonedDateTime): RequestMetaData = {
    // headers have been validated so safe to do a naked get except badgeId, eori and correlation id which are optional
    RequestMetaData(ClientSubscriptionId(UUID.fromString(headers.get(X_CDS_CLIENT_ID_HEADER_NAME).get)),
      ConversationId(UUID.fromString(headers.get(X_CONVERSATION_ID_HEADER_NAME).get)),
      findHeaderValue(X_BADGE_ID_HEADER_NAME, headers), findHeaderValue(X_EORI_ID_HEADER_NAME, headers),
      findHeaderValue(X_CORRELATION_ID_HEADER_NAME, headers),
      startTime)
  }

  private def process(xml: NodeSeq, md: RequestMetaData)(implicit hc: HeaderCarrier): Future[Result] = {
    logger.debug(s"Received notification with payload: $xml, metaData: $md")

    callbackDetailsConnector.getClientData(md.clientSubscriptionId.toString()).flatMap {

      case Some(apiSubscriptionFieldsResponse) =>
        handleNotification(xml, md, apiSubscriptionFieldsResponse).recover{
          case _: Throwable => ErrorInternalServerError.XmlResult
        }.map {
          case true =>
            logger.info("Notification processed successfully")
            Results.Accepted
          case false => ErrorInternalServerError.XmlResult
        }

      case None =>
        logger.error("Declarant data not found")
        Future.successful(ErrorCdsClientIdNotFound.XmlResult)

    }.recover {
      case ex: Throwable =>
        notificationLogger.error("Failed to fetch Declarant data " + ex.getMessage)
        errorInternalServerError("Internal Server Error").XmlResult
    }
  }

  private def findHeaderValue(headerName: String, headers: Headers): Option[Header] = {
    headers.get(headerName).map(Header(headerName, _))
  }

  def handleNotification(xml: NodeSeq, md: RequestMetaData, apiSubscriptionFieldsResponse: ApiSubscriptionFieldsResponse)(implicit hc: HeaderCarrier): Future[Boolean]
}

@Singleton
class CustomsNotificationClientWorkerController @Inject()(logger: NotificationLogger,
                                                          customsNotificationService: CustomsNotificationClientWorkerService,
                                                          callbackDetailsConnector: ApiSubscriptionFieldsConnector,
                                                          configService: CustomsNotificationConfig,
                                                          dateTimeService: DateTimeService)
  extends CustomsNotificationController(logger, customsNotificationService, callbackDetailsConnector, configService, dateTimeService) {

  def handleNotification(xml: NodeSeq, md: RequestMetaData, apiSubscriptionFieldsResponse: ApiSubscriptionFieldsResponse)(implicit hc: HeaderCarrier): Future[Boolean] = {
    customsNotificationService.handleNotification(xml, md)
  }

}

//TODO rename as CustomsNotificationRetryController
@Singleton
class CustomsNotificationWorkItemController @Inject()(logger: NotificationLogger,
                                              customsNotificationService: CustomsNotificationWorkItemService,
                                              callbackDetailsConnector: ApiSubscriptionFieldsConnector,
                                              configService: CustomsNotificationConfig,
                                              dateTimeService: DateTimeService)
  extends CustomsNotificationController(logger, customsNotificationService, callbackDetailsConnector, configService, dateTimeService) {

  def handleNotification(xml: NodeSeq, md: RequestMetaData, apiSubscriptionFieldsResponse: ApiSubscriptionFieldsResponse)(implicit hc: HeaderCarrier): Future[Boolean] = {
    customsNotificationService.handleNotification(xml, md, apiSubscriptionFieldsResponse)
  }
}