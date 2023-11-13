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

  def post(request: PostRequest)(implicit
                                 hc: HeaderCarrier,
                                 ec: ExecutionContext): Future[Either[HttpConnectorError, Unit]] = {
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

  def get[A: ClassTag](request: GetRequest)(implicit
                                            hc: HeaderCarrier,
                                            ec: ExecutionContext,
                                            responseParser: Reads[A]): Future[Either[HttpConnectorError, A]] = {
    http.GET[HttpResponse](
      request.url)(
      readRaw,
      request.transformHeaderCarrier(hc), ec)
      .map { response =>
        response.status match {
          case status if Status.isSuccessful(status) =>
            Json.parse(response.body).asOpt[A] match {
              case Some(value) => Right(value)
              case None =>
                val responseDtoName = implicitly[ClassTag[A]].runtimeClass.getSimpleName
                Left(ParseError(request, s"Could not parse response body to $responseDtoName.\nBody: ${response.body}"))
            }
          case _ => Left(ErrorResponse(request, response))
        }
      }.recover {
      case NonFatal(t) => Left(HttpClientError(request, t))
    }
  }
}

object HttpConnector {
  sealed trait HttpConnectorError extends CdsError {
    val request: CdsRequest
  }

  case class HttpClientError(request: CdsRequest, exception: Throwable) extends HttpConnectorError

  case class ErrorResponse(request: CdsRequest, response: HttpResponse) extends HttpConnectorError

  case class ParseError(request: CdsRequest, message: String) extends HttpConnectorError
}