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

package uk.gov.hmrc.customs.notification.controllers

import play.api.http.ContentTypes

import javax.inject.{Inject, Singleton}
import play.api.mvc._
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse._
import uk.gov.hmrc.customs.notification.config.CustomsNotificationConfig
import uk.gov.hmrc.customs.notification.models.requests.MetaDataRequest
import uk.gov.hmrc.customs.notification.models.{ClientId, HasId}
import uk.gov.hmrc.customs.notification.services.{CustomsNotificationService, HeadersActionFilter}
import uk.gov.hmrc.customs.notification.util.HeaderNames.{NOTIFICATION_ID_HEADER_NAME, X_CLIENT_ID_HEADER_NAME}
import uk.gov.hmrc.customs.notification.util.{DateTimeHelper, NotificationLogger, NotificationWorkItemRepo, Util}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse

import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

@Singleton
class CustomsNotificationController @Inject()(cc: ControllerComponents,
                                              logger: NotificationLogger,
                                              headersActionFilter: HeadersActionFilter,
                                              customsNotificationService: CustomsNotificationService,
                                              repo: NotificationWorkItemRepo)(implicit ec: ExecutionContext) extends BackendController(cc) {
  override val controllerComponents: ControllerComponents = cc
  //TODO move this during process of cleaning up headerservice as only used here
  private val xmlValidationErrorMessage = "Request body does not contain well-formed XML."

  def submit(): Action[AnyContent] = {
    (Action andThen headersActionFilter).async {
      implicit request: Request[AnyContent] =>
        val startTime = DateTimeHelper.zonedDateTimeUtc
        val maybeXml: Option[NodeSeq] = request.body.asXml
        val metaDataRequest: MetaDataRequest = MetaDataRequest.buildMetaDataRequest(maybeXml, request.headers, startTime)
        implicit val headerCarrier: HeaderCarrier = hc(request).copy()
          .withExtraHeaders((NOTIFICATION_ID_HEADER_NAME, metaDataRequest.notificationId.toString))
        maybeXml match {
          case Some(xml) =>
            customsNotificationService.handleNotification(xml, metaDataRequest)(headerCarrier)
            Future.successful(Results.Accepted)
          case None =>
            logger.error(xmlValidationErrorMessage)(metaDataRequest)
            Future.successful(errorBadRequest(xmlValidationErrorMessage).XmlResult)
        }
    }
  }

  def blockedCount(): Action[AnyContent] = Action.async {
    implicit request: Request[AnyContent] =>
      validateHeader(request.headers, "blocked-count") match {
        case Left(errorResponse) => Future.successful(errorResponse.XmlResult)
        case Right(clientId) =>
          implicit val loggingContext: HasId = Util.createLoggingContext(clientId.toString)
          repo.blockedCount(ClientId(clientId.toString)).map { count =>
            logger.info(s"blocked count of $count returned")
            val countXml = <pushNotificationBlockedCount>
              {count}
            </pushNotificationBlockedCount>
            Ok(s"<?xml version='1.0' encoding='UTF-8'?>\n$countXml").as(ContentTypes.XML)
          }.recover {
            case t: Throwable =>
              logger.error(s"unable to get blocked count due to $t")
              ErrorInternalServerError.XmlResult
          }
      }
  }

  def deleteBlocked(): Action[AnyContent] = Action.async {
    implicit request =>
      validateHeader(request.headers, "delete blocked-flag") match {
        case Left(errorResponse) => Future.successful(errorResponse.XmlResult)
        case Right(clientId) =>
          implicit val loggingContext: HasId = Util.createLoggingContext(clientId.toString)
          repo.unblockFailedAndBlockedByClientId(ClientId(clientId.toString)).map { updateCount =>
            if (updateCount > 0) {
              logger.info(s"blocked flags deleted for clientId $clientId")
              NoContent
            } else {
              logger.info(s"no blocked flags deleted for clientId $clientId")
              ErrorNotFound.XmlResult
            }
          }.recover {
            case t: Throwable =>
              logger.error(s"unable to delete blocked flags due to $t")
              ErrorInternalServerError.XmlResult
          }
      }
  }

  //TODO move this
  private def validateHeader(headers: Headers, endpointName: String): Either[ErrorResponse, ClientId] = {
    headers.get(X_CLIENT_ID_HEADER_NAME).fold[Either[ErrorResponse, ClientId]] {
      logger.errorWithHeaders(s"missing $X_CLIENT_ID_HEADER_NAME header when calling $endpointName endpoint", headers.headers)
      Left(errorBadRequest(s"$X_CLIENT_ID_HEADER_NAME required"))
    } { clientId =>
      logger.debugWithHeaders(s"called $endpointName", headers.headers)
      Right(ClientId(clientId))
    }
  }
}
