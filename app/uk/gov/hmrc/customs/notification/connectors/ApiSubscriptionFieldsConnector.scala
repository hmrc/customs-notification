/*
 * Copyright 2024 HM Revenue & Customs
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

import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.MimeTypes
import play.api.libs.json.Json
import play.mvc.Http.Status._
import uk.gov.hmrc.customs.notification.config.ServiceConfigProvider
import uk.gov.hmrc.customs.notification.controllers.FieldsIdMapperHotFix
import uk.gov.hmrc.customs.notification.domain.{ApiSubscriptionFields, CustomsNotificationConfig}
import uk.gov.hmrc.customs.notification.http.Non2xxResponseException
import uk.gov.hmrc.customs.notification.logging.CdsLogger
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HttpClient, _}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApiSubscriptionFieldsConnector @Inject()(http: HttpClient,
                                               logger: CdsLogger,
                                               serviceConfigProvider: ServiceConfigProvider,
                                               configService: CustomsNotificationConfig)
                                              (implicit ec: ExecutionContext) {

  private val headers = Seq(
    (CONTENT_TYPE, MimeTypes.JSON),
    (ACCEPT, MimeTypes.JSON)
  )

  def getClientData(fieldsId: String)(implicit hc: HeaderCarrier): Future[Option[ApiSubscriptionFields]] = {
    val fieldsIdMapperHotFix = new FieldsIdMapperHotFix(logger, configService.notificationConfig)
    val safeFieldsId = fieldsIdMapperHotFix.translate(fieldsId)

    callApiSubscriptionFields(safeFieldsId, hc) map { response =>
      logger.debug(s"api-subscription-fields service response status=${response.status} response body=${response.body}")

      response.status match {
        case OK => parseResponseAsModel(response.body)
        case NOT_FOUND => None
        case status =>
          logger.error(s"unexpected subscription information service response status=$status")
          throw new Non2xxResponseException(status)
      }
    }
  }

  private def parseResponseAsModel(jsonResponse: String): Option[ApiSubscriptionFields] = {
    val response = Some(Json.parse(jsonResponse).as[ApiSubscriptionFields])
    logger.debug(s"api-subscription-fields service parsed response=$response")
    response
  }

  private def callApiSubscriptionFields(fieldsId: String, hc: HeaderCarrier): Future[HttpResponse] = {
    implicit val headerCarrier: HeaderCarrier = HeaderCarrier(requestId = hc.requestId, extraHeaders = headers)

    val baseUrl = serviceConfigProvider.getConfig("api-subscription-fields").url
    val fullUrl = s"$baseUrl/$fieldsId"
    val headerNames: Seq[String] = HeaderNames.explicitlyIncludedHeaders
    val headersToLog = hc.headers(headerNames) ++ hc.extraHeaders

    logger.debug(s"calling api-subscription-fields service with fieldsId=$fieldsId url=$fullUrl headers=${headersToLog}")

    http.GET[HttpResponse](fullUrl)
      .recoverWith {
        case httpError: HttpException =>
          Future.failed(new RuntimeException(httpError)) //reserved for problems in making the request

        case e: Throwable =>
          logger.error(s"call to subscription information service failed. GET url=$fullUrl")
          Future.failed(e)
      }
  }
}
