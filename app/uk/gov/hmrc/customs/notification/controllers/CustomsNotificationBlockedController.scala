/*
 * Copyright 2020 HM Revenue & Customs
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

import javax.inject.{Inject, Singleton}
import play.api.http.ContentTypes
import play.api.mvc._
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse.{ErrorInternalServerError, ErrorNotFound}
import uk.gov.hmrc.customs.notification.controllers.CustomErrorResponses.ErrorClientIdMissing
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames.X_CLIENT_ID_HEADER_NAME
import uk.gov.hmrc.customs.notification.domain.{ClientId, HasId}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.CustomsNotificationBlockedService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CustomsNotificationBlockedController @Inject()(val customsNotificationBlockedService: CustomsNotificationBlockedService,
                                                     val cc: ControllerComponents,
                                                     val logger: NotificationLogger)
                                                    (implicit ec: ExecutionContext)
  extends BackendController(cc) {

  def blockedCount(): Action[AnyContent] = Action.async {
    implicit request: Request[AnyContent] =>
      validateHeader(request.headers, "blocked-count") match {
        case Left(errorResponse) => Future.successful(errorResponse.XmlResult)
        case Right(clientId) =>
          implicit val loggingContext: HasId = createLoggingContext(clientId.toString)
          customsNotificationBlockedService.blockedCount(ClientId(clientId.toString)).map { count =>
            logger.info(s"blocked count of $count returned")
            blockedCountResponse(count)
          }.recover {
            case t: Throwable =>
              logger.error(s"unable to get blocked count due to $t")
              ErrorInternalServerError.XmlResult
          }
      }
  }

  def deleteBlocked():Action[AnyContent] = Action.async {
    implicit request =>
      validateHeader(request.headers, "delete blocked-flag") match {
        case Left(errorResponse) => Future.successful(errorResponse.XmlResult)
        case Right(clientId) =>
          implicit val loggingContext: HasId = createLoggingContext(clientId.toString)
          customsNotificationBlockedService.deleteBlocked(ClientId(clientId.toString)).map { deleted =>
            if (deleted) {
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

  private def validateHeader(headers: Headers, endpointName: String): Either[ErrorResponse, ClientId] = {
    headers.get(X_CLIENT_ID_HEADER_NAME).fold[Either[ErrorResponse, ClientId]]{
      logger.errorWithHeaders(s"missing $X_CLIENT_ID_HEADER_NAME header when calling $endpointName endpoint", headers.headers)
      Left(ErrorClientIdMissing)
    } { clientId =>
      logger.debugWithHeaders(s"called $endpointName", headers.headers)
      Right(ClientId(clientId))
    }
  }

  private def createLoggingContext(clientId: String): HasId = {
    new HasId {
      override def idName: String = "clientId"
      override def idValue: String = clientId
    }
  }

  private def blockedCountResponse(count: Int): Result = {
    val countXml = <pushNotificationBlockedCount>{count}</pushNotificationBlockedCount>
    Ok(s"<?xml version='1.0' encoding='UTF-8'?>\n$countXml").as(ContentTypes.XML)
  }
}
