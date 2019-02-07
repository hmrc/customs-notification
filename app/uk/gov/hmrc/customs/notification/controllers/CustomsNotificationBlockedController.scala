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

import javax.inject.{Inject, Singleton}
import play.api.http.ContentTypes
import play.api.mvc._
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse.{ErrorInternalServerError, ErrorNotFound}
import uk.gov.hmrc.customs.notification.controllers.CustomErrorResponses.ErrorClientIdMissing
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames.X_CLIENT_ID_HEADER_NAME
import uk.gov.hmrc.customs.notification.domain.{ClientId, HasId}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.CustomsNotificationBlockedService
import uk.gov.hmrc.play.bootstrap.controller.BaseController

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class CustomsNotificationBlockedController @Inject()(val logger: NotificationLogger,
                                                     val customsNotificationBlockedService: CustomsNotificationBlockedService)
  extends BaseController {

  def blockedCount(): Action[AnyContent] = Action.async {
    implicit request: Request[AnyContent] =>
      validateHeader(request.headers, "blocked-count").flatMap(_.fold(Future.successful(ErrorClientIdMissing.XmlResult)) {
        clientId =>
          implicit val loggingContext: HasId = createLoggingContext(clientId)
          logger.debug(s"getting blocked count")
          customsNotificationBlockedService.blockedCount(ClientId(clientId)).map { count =>
            logger.info(s"blocked count of $count returned")
            blockedCountResponse(count)
          }.recover {
            case t: Throwable =>
              logger.error(s"unable to get blocked count due to ${t.getMessage}")
              ErrorInternalServerError.XmlResult
          }
      })
  }

  def deleteBlocked():Action[AnyContent] = Action.async {
    implicit request =>
      validateHeader(request.headers, "blocked-flag").flatMap(_.fold(Future.successful(ErrorClientIdMissing.XmlResult)) {
        clientId =>
          implicit val loggingContext = createLoggingContext(clientId)
          logger.debug(s"deleting blocked flags for clientId $clientId")
          customsNotificationBlockedService.deleteBlocked(ClientId(clientId)).map { deleted =>
            if (deleted) {
              logger.info(s"blocked flags deleted for clientId $clientId")
              NoContent
            } else {
              logger.info(s"no blocked flags deleted for clientId $clientId")
              ErrorNotFound.XmlResult
            }
          }.recover {
            case t: Throwable =>
              logger.error(s"unable to delete blocked flags due to ${t.getMessage}")
              ErrorInternalServerError.XmlResult
          }
      })
  }

  private def validateHeader(headers: Headers, endpointName: String): Future[Option[String]] = {
    headers.get(X_CLIENT_ID_HEADER_NAME).fold {
      logger.errorWithHeaders(s"missing $X_CLIENT_ID_HEADER_NAME header when calling $endpointName endpoint", headers.headers)
      val tmp : Option[String] = None
      Future.successful(tmp)
    } { clientId =>
      Future.successful(Some(clientId))
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
