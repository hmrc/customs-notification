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

package uk.gov.hmrc.customs.notification.connectors

import javax.inject.{Inject, Singleton}

import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.MimeTypes
import play.api.libs.json.Json
import play.mvc.Http.Status._
import uk.gov.hmrc.customs.api.common.config.ServiceConfigProvider
import uk.gov.hmrc.customs.notification.domain.{ApiSubscriptionFieldsResponse, DeclarantCallbackData}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.WSGetImpl
import uk.gov.hmrc.http._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class ApiSubscriptionFieldsConnector @Inject()(httpGet: WSGetImpl,
                                               logger: NotificationLogger,
                                               serviceConfigProvider: ServiceConfigProvider) {

  private val headers = Seq(
    (CONTENT_TYPE, MimeTypes.JSON),
    (ACCEPT, MimeTypes.JSON)
  )

  def getClientData(fieldsId: String)(implicit hc: HeaderCarrier): Future[Option[DeclarantCallbackData]] = {
    logger.debug("calling api-subscription-fields service")
    callApiSubscriptionFields(fieldsId)(hc = hc.copy(extraHeaders = headers)) map { response =>
      logger.debug(s"api-subscription-fields service response status=${response.status} response body=${response.body}")

      response.status match {
        case OK => parseResponseAsModel(response.body)
        case NOT_FOUND => None
        case status =>
          val msg = s"unexpected subscription information service response status=$status"
          logger.error(msg)
          throw new IllegalStateException(msg)
      }
    }
  }

  private def parseResponseAsModel(jsonResponse: String)(implicit hc: HeaderCarrier) = {
    val response = Some(Json.parse(jsonResponse).as[ApiSubscriptionFieldsResponse].fields)
    logger.debug(s"api-subscription-fields service parsed response=$response")
    response
  }

  private def callApiSubscriptionFields(fieldsId: String)(implicit hc: HeaderCarrier): Future[HttpResponse] = {

    val baseUrl = serviceConfigProvider.getConfig("api-subscription-fields").url
    val fullUrl = s"$baseUrl/$fieldsId"
    logger.debug("calling api-subscription-fields service", url = fullUrl)

    httpGet.GET[HttpResponse](fullUrl)
      .recoverWith {
        case _ : NotFoundException => Future.successful(HttpResponse(NOT_FOUND))
      }
      .recoverWith {
        case bre : BadRequestException => Future.failed(new IllegalStateException(bre.message))
      }
      .recoverWith {
        case httpError: HttpException => Future.failed(new RuntimeException(httpError))
      }
      .recoverWith {
        case e: Throwable =>
          logger.error(s"call to subscription information service failed. GET url=$fullUrl", e)
          Future.failed(e)
      }

  }

}
