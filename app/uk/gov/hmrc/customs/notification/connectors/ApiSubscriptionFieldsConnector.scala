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

package uk.gov.hmrc.customs.notification.connectors

import javax.inject.{Inject, Singleton}
import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.MimeTypes
import uk.gov.hmrc.customs.api.common.config.ServiceConfigProvider
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.config.NotificationConfig
import uk.gov.hmrc.customs.notification.models.ClientSubscriptionId
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.HttpClient
import uk.gov.hmrc.http.HttpReads.Implicits._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApiSubscriptionFieldsConnector @Inject()(http: HttpClient,
                                               logger: CdsLogger,
                                               serviceConfigProvider: ServiceConfigProvider,
                                               notificationConfig: NotificationConfig)(implicit ec: ExecutionContext) {

  private val headers = Seq((CONTENT_TYPE, MimeTypes.JSON), (ACCEPT, MimeTypes.JSON))
  //Below val & def are: 'Hot fix for
  //https://jira.tools.tax.service.gov.uk/browse/DCWL-851
  //https://jira.tools.tax.service.gov.uk/browse/DCWL-859'
  private val csIdMap = notificationConfig.hotFixTranslates.map { pair =>
    val mapping = pair.split(":").toList
    mapping(0) -> mapping(1)
  }.toMap

  private def translate(csId: String): String = {
    val safeCsId: String = csIdMap.getOrElse(csId, csId)
    if (!safeCsId.matches(csId)) {
      logger.warn(s"FieldsIdMapperHotFix: translating fieldsId(csId) [$csId] to [$safeCsId].")
    }
    safeCsId
  }

  def getApiSubscriptionFields(clientSubscriptionId: ClientSubscriptionId, hc: HeaderCarrier): Future[HttpResponse] = {
    //TODO check headers have been changed correctly here
    implicit val updatedHeaderCarrier: HeaderCarrier = HeaderCarrier(requestId = hc.requestId, extraHeaders = headers)
    val safeCsId: String = translate(clientSubscriptionId.toString)
    val baseUrl = serviceConfigProvider.getConfig("api-subscription-fields").url
    val fullUrl = s"$baseUrl/$safeCsId"
    val headerNames: Seq[String] = HeaderNames.explicitlyIncludedHeaders
    val headersToLog: Seq[(String, String)] = hc.headers(headerNames) ++ hc.extraHeaders

    logger.debug(s"calling api-subscription-fields service with fieldsId=$safeCsId url=$fullUrl \nheaders=$headersToLog")
    http.GET[HttpResponse](fullUrl)
      .recoverWith { error => throw new RuntimeException(s"Non-Http Error(callApiSubscriptionFields) when attempting to retrieve ApiSubscriptionFields with CsId:$safeCsId, Url:$fullUrl, headers:$headersToLog, error:$error") }
  }

  //TODO maybe delete
//  def getApiSubscriptionFields(clientSubscriptionId: ClientSubscriptionId)(implicit hc: HeaderCarrier): Future[Option[ApiSubscriptionFields]] = {
//    val safeCsId: String = translate(clientSubscriptionId.toString)
//    logger.debug("calling api-subscription-fields service")
//    val eventualResponse: Future[HttpResponse] = callApiSubscriptionFields(safeCsId, hc)
//
//    eventualResponse map { response =>
//      logger.debug(s"api-subscription-fields service response status=${response.status} response body=${response.body}")
//      response.status match {
//        case OK =>
//          val parsedResponse: Option[ApiSubscriptionFields] = Some(Json.parse(response.body).as[ApiSubscriptionFields])
//          logger.debug(s"api-subscription-fields service parsed response=$parsedResponse")
//          parsedResponse
//        case status =>
//          logger.error(s"Error(getApiSubscriptionFields) non-200 response when attempting to retrieve ApiSubscriptionFields with CsId:$clientSubscriptionId, Url:$fullUrl, headers:$headersToLog, status=$status")
//          throw new Non2xxResponseException(status)
//      }
//    }
//  }
//
//  def getApiSubscriptionFields2(clientSubscriptionId: ClientSubscriptionId)(implicit hc: HeaderCarrier): Future[HttpResponse] = {
//    val safeCsId: String = translate(clientSubscriptionId.toString)
//    logger.debug("calling api-subscription-fields service")
//    val eventualResponse: Future[HttpResponse] = callApiSubscriptionFields(safeCsId, hc)
//
//    eventualResponse map { response =>
//      logger.debug(s"api-subscription-fields service response status=${response.status} response body=${response.body}")
//      response.status match {
//        case OK =>
//          val parsedResponse: Option[ApiSubscriptionFields] = Some(Json.parse(response.body).as[ApiSubscriptionFields])
//          logger.debug(s"api-subscription-fields service parsed response=$parsedResponse")
//          parsedResponse
//        case non200Status =>
//          logger.error(s"Error(getApiSubscriptionFields) non-200 response when attempting to retrieve ApiSubscriptionFields with CsId:$clientSubscriptionId, Url:$fullUrl, headers:$headersToLog, status=$non200Status")
//          throw new Non2xxResponseException(non200Status)
//      }
//    }
//  }



  //TODO delete
//  private def callApiSubscriptionFieldsOLD(clientSubscriptionId: String, hc: HeaderCarrier): Future[HttpResponse] = {
//    implicit val updatedHeaderCarrier: HeaderCarrier = HeaderCarrier(requestId = hc.requestId, extraHeaders = headers)
//    val baseUrl = serviceConfigProvider.getConfig("api-subscription-fields").url
//    val fullUrl = s"$baseUrl/$clientSubscriptionId"
//    val headerNames: Seq[String] = HeaderNames.explicitlyIncludedHeaders
//    val headersToLog: Seq[(String, String)] = hc.headers(headerNames) ++ hc.extraHeaders
//
//    logger.debug(s"calling api-subscription-fields service with fieldsId=$clientSubscriptionId url=$fullUrl \nheaders=$headersToLog")
//    http.GET[HttpResponse](fullUrl)
//      .recoverWith {
//        case httpError: HttpException =>
//          throw new RuntimeException(s"Http Error(callApiSubscriptionFields) when attempting to retrieve ApiSubscriptionFields with CsId:$clientSubscriptionId, Url:$fullUrl, headers:$headersToLog, error:$httpError")
//        case e: Throwable =>
//          throw new RuntimeException(s"Non-Http Error(callApiSubscriptionFields) when attempting to retrieve ApiSubscriptionFields with CsId:$clientSubscriptionId, Url:$fullUrl, headers:$headersToLog, error:$e")
//      }
//  }
}
