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

import akka.actor.ActorSystem
import com.typesafe.config.Config
import play.api.Configuration
import play.api.http.Status
import play.api.libs.json.{JsObject, Reads}
import play.api.libs.json.{Json => PlayJson}
import play.api.libs.ws.WSClient
import uk.gov.hmrc.customs.notification.connectors.HttpConnector._
import uk.gov.hmrc.customs.notification.models.Payload
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.hooks.HttpHook
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse}
import uk.gov.hmrc.play.http.ws._

import java.net.URL
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.xml.NodeSeq

@Singleton
private class NoAuditHttpClient @Inject()(override val actorSystem: ActorSystem,
                                          config: Configuration,
                                          override val wsClient: WSClient) extends HttpClient with WSHttp {

  override protected def configuration: Config = config.underlying

  override val hooks: Seq[HttpHook] = Nil
}

@Singleton
class HttpConnector @Inject()(http: HttpClient,
                              noAuditHttp: NoAuditHttpClient)(implicit ec: ExecutionContext) {

  /**
   * Send a POST request via HTTP
   *
   * @param request A request instance that subtypes [[uk.gov.hmrc.customs.notification.models.requests.PostRequest]]
   * @param hc      A HeaderCarrier for the client request to customs-notification
   * @param ec      The ExecutionContext to execute the HTTP request on
   * @return Eventually [[scala.Unit]] if response status is 2xx, or a [[connectors.HttpConnector.PostHttpConnectorError]] otherwise
   */
  def post(url: URL,
           body: RequestBody,
           hc: HeaderCarrier,
           requestDescriptor: String,
           shouldSendRequestToAuditing: Boolean): Future[Either[PostHttpConnectorError, Unit]] = {
    val httpClientToUse: HttpClient = chooseHttpClient(shouldSendRequestToAuditing)

    val bodyAsString: String = body match {
      case RequestBody.Xml(underlying) => underlying.toString
      case RequestBody.Json(underlying) => PlayJson.stringify(underlying)
    }

    httpClientToUse
      .POSTString[HttpResponse](url, bodyAsString)(readRaw, hc, ec)
      .map { response =>
        response.status match {
          case status if Status.isSuccessful(status) => Right(())
          case _ => Left(ErrorResponse(requestDescriptor, response))
        }
      }.recover {
      case NonFatal(t) => Left(HttpClientError(requestDescriptor, t))
    }
  }

  /**
   * Send a GET request via HTTP
   *
   * @param request A request instance that subtypes [[uk.gov.hmrc.customs.notification.models.requests.GetRequest]]
   * @param hc      A HeaderCarrier for the client request to customs-notification
   * @param ec      The ExecutionContext to execute the HTTP request on
   * @tparam A The type to unmarshall the potentially successful [[uk.gov.hmrc.http.HttpResponse]] into
   * @return Eventually the response parsed as A if response status is 2xx, or a [[connectors.HttpConnector.PostHttpConnectorError]] otherwise
   */
  def get[A](url: URL,
             hc: HeaderCarrier,
             requestDescriptor: String,
             shouldSendRequestToAuditing: Boolean)(implicit responseReads: Reads[A]): Future[Either[GetHttpConnectorError, A]] = {
    val httpClientToUse = chooseHttpClient(shouldSendRequestToAuditing)

    httpClientToUse
      .GET[HttpResponse](url)(readRaw, hc, ec)
      .map { response =>
        response.status match {
          case status if Status.isSuccessful(status) =>
            PlayJson.parse(response.body).asOpt[A] match {
              case Some(value) => Right(value)
              case None => Left(ParseError(requestDescriptor, response))
            }
          case _ => Left(ErrorResponse(requestDescriptor, response))
        }
      }.recover {
      case NonFatal(t) => Left(HttpClientError(requestDescriptor, t))
    }
  }

  private def chooseHttpClient(shouldAudit: Boolean): HttpClient = if (shouldAudit) http else noAuditHttp
}

object HttpConnector {
  sealed trait RequestBody {
    type A

    def underlying: A
  }

  object RequestBody{
    case class Xml(underlying: Payload) extends RequestBody {
      type A = Payload
    }

    case class Json(underlying: JsObject) extends RequestBody {
      type A = JsObject
    }
  }

  sealed trait HttpConnectorError {
    def message: String
  }

  sealed trait PostHttpConnectorError extends HttpConnectorError

  sealed trait GetHttpConnectorError extends HttpConnectorError

  case class HttpClientError(requestDescriptor: String, exception: Throwable)
    extends PostHttpConnectorError with GetHttpConnectorError {
    val message: String = s"HTTP client error while making $requestDescriptor request with exception: ${exception.getMessage}"
  }

  case class ErrorResponse(requestDescriptor: String, response: HttpResponse)
    extends PostHttpConnectorError with GetHttpConnectorError {
    val message: String = s"Error response with status code ${response.status} when making $requestDescriptor request"
  }

  case class ParseError(requestDescriptor: String, response: HttpResponse)
    extends GetHttpConnectorError {
    val message: String = {
      s"$requestDescriptor request succeeded but could not parse response body.\nBody: ${response.body}"
    }
  }
}
