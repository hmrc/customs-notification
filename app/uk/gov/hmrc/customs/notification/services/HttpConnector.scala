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

package uk.gov.hmrc.customs.notification.services

import play.api.http.Status
import play.api.libs.json.{Json, Reads}
import uk.gov.hmrc.customs.notification.models.errors.CdsError
import uk.gov.hmrc.customs.notification.models.requests.{CdsRequest, GetRequest, PostRequest}
import uk.gov.hmrc.customs.notification.services.HttpConnector._
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.control.NonFatal

@Singleton
class HttpConnector @Inject()(http: HttpClient) {

  def post[R <: PostRequest](request: R)(implicit hc: HeaderCarrier,
                                         ec: ExecutionContext): Future[Either[PostHttpConnectorError[R], Unit]] = {
    http.POST[request.POST_BODY, HttpResponse](
      request.url,
      request.body)(
      request.writes,
      readRaw,
      request.transformHeaderCarrier(hc),
      ec)
      .map { response =>
        response.status match {
          case status if Status.isSuccessful(status) => Right(())
          case _ => Left(ErrorResponse(request, response))
        }
      }.recover {
      case NonFatal(t) => Left(HttpClientError(request, t))
    }
  }

  def get[R <: GetRequest, A](request: R)(implicit hc: HeaderCarrier,
                                          ec: ExecutionContext,
                                          responseReads: Reads[A]): Future[Either[HttpConnectorError[R], A]] = {
    http.GET[HttpResponse](
      request.url)(
      readRaw,
      request.transformHeaderCarrier(hc), ec)
      .map { response =>
        response.status match {
          case status if Status.isSuccessful(status) =>
            Json.parse(response.body).asOpt[A] match {
              case Some(value) => Right(value)
              case None => Left(ParseError[R](request, response))
            }
          case _ => Left(ErrorResponse(request, response))
        }
      }.recover {
      case NonFatal(t) => Left(HttpClientError(request, t))
    }
  }
}

object HttpConnector {
  sealed trait HttpConnectorError[+R <: CdsRequest] extends CdsError {
    val request: R
    val message: String
  }

  sealed trait PostHttpConnectorError[+R <: CdsRequest] extends HttpConnectorError[R]

  case class HttpClientError[R <: CdsRequest](request: R,
                                              exception: Throwable) extends PostHttpConnectorError[R] {
    val message: String = s"HTTP client error while making ${request.descriptor} request with exception: ${exception.getMessage}"
  }

  case class ErrorResponse[R <: CdsRequest](request: R,
                                            response: HttpResponse) extends PostHttpConnectorError[R] {
    val message: String = s"Error response with status code ${response.status} when making ${request.descriptor} request"
  }

  case class ParseError[R <: CdsRequest](request: R,
                                                        response: HttpResponse) extends HttpConnectorError[R] {
    val message: String = {
      s"${request.descriptor} request succeeded but could not parse response body.\nBody: ${response.body}"
    }
  }
}