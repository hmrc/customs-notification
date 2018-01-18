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

package uk.gov.hmrc.customs.notification.controllers

import javax.inject.{Inject, Singleton}

import play.api.Configuration
import play.api.mvc._
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse
import uk.gov.hmrc.customs.notification.controllers.CustomErrorResponses.ErrorCdsClientIdNotFound
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.{CustomsNotificationService, DeclarantCallbackDataNotFound, NotificationSent}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.microservice.controller.BaseController

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.NodeSeq

@Singleton
class CustomsNotificationController @Inject()(logger: NotificationLogger,
                                              customsNotificationService: CustomsNotificationService,
                                              configuration: Configuration)
  extends BaseController with HeaderValidator {

  override val notificationLogger: NotificationLogger = logger
  private lazy val xmlValidationErrorMessage = "Request body does not contain well-formed XML."

  def submit(): Action[AnyContent] = validateHeaders(loadBasicToken) async {
    implicit request =>
      request.body.asXml match {
        case Some(xml) => onXmlPayload(xml, request.headers)
        case None =>
          notificationLogger.error(xmlValidationErrorMessage)
          Future.successful(ErrorResponse.errorBadRequest(xmlValidationErrorMessage).XmlResult)
      }
  }

  private def onXmlPayload(xml: NodeSeq, headers: Headers)(implicit hc: HeaderCarrier): Future[Result] = {
    logger.debug("Received with payload", url = "", payload = xml.toString)

    customsNotificationService.sendNotification(xml, headers) map { sendNotificationResult => {
      sendNotificationResult match {
          case NotificationSent => Results.NoContent
          case DeclarantCallbackDataNotFound => ErrorCdsClientIdNotFound.XmlResult
        }
      }
    }
  }

  private lazy val loadBasicToken: Option[String] = {
    configuration.getString("auth.token.internal")
  }
}
