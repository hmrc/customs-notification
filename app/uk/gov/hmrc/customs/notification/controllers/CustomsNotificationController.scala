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
import uk.gov.hmrc.customs.notification.services.{CustomsNotificationService, DateTimeService}
import uk.gov.hmrc.play.bootstrap.controller.BaseController

import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

case class RequestMetaData(clientSubscriptionId: ClientSubscriptionId,
                           conversationId: ConversationId,
                           mayBeBadgeId: Option[BadgeId],
                           mayBeSubmitterNumber: Option[Submitter],
                           maybeCorrelationId: Option[CorrelationId],
                           startTime: ZonedDateTime)
  extends HasId
  with HasClientSubscriptionId
  with HasMaybeBadgeId
  with HasMaybeCorrelationId
  with HasMaybeSubmitter
{
  def mayBeBadgeIdHeader: Option[Header] = asHeader(CustomHeaderNames.X_BADGE_ID_HEADER_NAME, mayBeBadgeId)

  def mayBeSubmitterHeader: Option[Header] = asHeader(CustomHeaderNames.X_SUBMITTER_ID_HEADER_NAME, mayBeSubmitterNumber)

  def mayBeCorrelationIdHeader: Option[Header] = asHeader(CustomHeaderNames.X_CORRELATION_ID_HEADER_NAME, maybeCorrelationId)

  private def asHeader[T](name: String, maybeHeaderValue: Option[T]) =
    maybeHeaderValue.map(v => Header(name = name, value = v.toString))

  override def idName: String = "conversationId"

  override def idValue: String = conversationId.toString
}

@Singleton
class CustomsNotificationController @Inject()(val logger: NotificationLogger,
                                              val customsNotificationService: CustomsNotificationService,
                                              val callbackDetailsConnector: ApiSubscriptionFieldsConnector,
                                              val configService: CustomsNotificationConfig,
                                              val dateTimeService: DateTimeService)
                                                      (implicit ec: ExecutionContext)
               extends BaseController with HeaderValidator {

  override val notificationLogger: NotificationLogger = logger
  private lazy val maybeBasicAuthToken: Option[String] = configService.maybeBasicAuthToken
  private lazy val xmlValidationErrorMessage = "Request body does not contain well-formed XML."

  def submit(): Action[AnyContent] = {
    val startTime = dateTimeService.zonedDateTimeUtc
    validateHeaders(maybeBasicAuthToken) async {
      implicit request =>
        implicit val rd = requestMetaData(request.headers, startTime)
        request.body.asXml match {
          case Some(xml) =>
            process(xml)
          case None =>
            notificationLogger.error(xmlValidationErrorMessage)
            Future.successful(errorBadRequest(xmlValidationErrorMessage).XmlResult)
        }
    }
  }

  private def requestMetaData(headers: Headers, startTime: ZonedDateTime): RequestMetaData = {
    // headers have been validated so safe to do a naked get except badgeId, submitter and correlation id which are optional
    RequestMetaData(ClientSubscriptionId(UUID.fromString(headers.get(X_CDS_CLIENT_ID_HEADER_NAME).get)),
      ConversationId(UUID.fromString(headers.get(X_CONVERSATION_ID_HEADER_NAME).get)),
      headers.get(X_BADGE_ID_HEADER_NAME).map(BadgeId), headers.get(X_SUBMITTER_ID_HEADER_NAME).map(Submitter),
      headers.get(X_CORRELATION_ID_HEADER_NAME).map(CorrelationId),
      startTime)
  }

  private def process(xml: NodeSeq)(implicit md: RequestMetaData): Future[Result] = {
    logger.debug(s"Received notification with payload: $xml, metaData: $md")
    callbackDetailsConnector.getClientData(md.clientSubscriptionId.toString()).flatMap {

      case Some(apiSubscriptionFields) =>
        handleNotification(xml, md, apiSubscriptionFields).recover{
          case t: Throwable =>
            logger.error(s"Notification processing failed due to: ${t.getMessage}")
            ErrorInternalServerError.XmlResult
        }.map {
          case true =>
            logger.info("Notification processed successfully")
            Results.Accepted
          case false =>
            logger.error("Notification processing failed")
            ErrorInternalServerError.XmlResult
        }

      case None =>
        logger.error("Declarant data not found")
        Future.successful(ErrorCdsClientIdNotFound.XmlResult)

    }.recover {
      case ex: Throwable =>
        notificationLogger.error("Failed to fetch declarant data " + ex.getMessage)
        errorInternalServerError("Internal Server Error").XmlResult
    }
  }

  def handleNotification(xml: NodeSeq, md: RequestMetaData, apiSubscriptionFields: ApiSubscriptionFields): Future[Boolean] = {
    customsNotificationService.handleNotification(xml, md, apiSubscriptionFields)
  }
}
