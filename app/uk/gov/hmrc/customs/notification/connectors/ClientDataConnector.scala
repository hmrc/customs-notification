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

import play.api.cache.{AsyncCacheApi, NamedCache}
import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.MimeTypes
import play.api.http.Status.NOT_FOUND
import uk.gov.hmrc.customs.notification.config.{ClientDataCacheConfig, ClientDataConfig}
import uk.gov.hmrc.customs.notification.connectors.ClientDataConnector.*
import uk.gov.hmrc.customs.notification.connectors.HttpConnector.*
import uk.gov.hmrc.customs.notification.models.*
import uk.gov.hmrc.customs.notification.util.*
import uk.gov.hmrc.http.HeaderCarrier

import java.net.URL
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ClientDataConnector @Inject()(httpConnector: HttpConnector,
                                    cacheConfig: ClientDataCacheConfig,
                                    @NamedCache("client-data") cache: AsyncCacheApi,
                                    config: ClientDataConfig)
                                   (implicit ec: ExecutionContext) extends Logger {
  def get(csid: ClientSubscriptionId)
         (implicit hc: HeaderCarrier,
          lc: LogContext): Future[Either[Error, ClientData]] = {
    val url = new URL(s"${config.url.toString}/$csid")
    val newHc = {
      HeaderCarrier(
        requestId = hc.requestId,
        extraHeaders = List(
          CONTENT_TYPE -> MimeTypes.JSON,
          ACCEPT -> MimeTypes.JSON)
      )
    }

    cache.get[ClientData](csid.toString)
      .flatMap {
        case Some(cachedClientData) =>
          Future.successful(Right(cachedClientData))
        case None =>
          httpConnector
            .get[ClientData](
              url = url,
              hc = newHc,
              requestDescriptor = "API subscription fields"
            )
            .flatMap {
              case Right(clientData) =>
                cache
                  .set(csid.toString, clientData, cacheConfig.ttl)
                  .map(_ => Right(clientData))
              case Left(ErrorResponse(_, response)) if response.status == NOT_FOUND =>
                logger.error("Declarant data not found for client subscription ID")
                Future.successful(Left(DeclarantNotFound))
              case Left(e) =>
                logger.error(e.message)
                Future.successful(Left(OtherError))
            }
      }
  }
}

object ClientDataConnector {

  sealed trait Error

  case object DeclarantNotFound extends Error

  case object OtherError extends Error
}
