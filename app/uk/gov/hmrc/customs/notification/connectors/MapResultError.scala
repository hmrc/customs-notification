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

package uk.gov.hmrc.customs.notification.connectors

import uk.gov.hmrc.customs.notification.domain.{HttpResultError, NonHttpError, ResultError}
import uk.gov.hmrc.http.{HttpException, Upstream4xxResponse, Upstream5xxResponse}

trait MapResultError {

  // maps non uniform uk.gov.hmrc.http Exception hierarchy to a uniform ResultError hierarchy
  def mapResultError(e: Throwable): ResultError = e match {
    case e: HttpException =>
      HttpResultError(e.responseCode, e)
    case e: Upstream4xxResponse =>
      HttpResultError(e.upstreamResponseCode, e)
    case e: Upstream5xxResponse =>
      HttpResultError(e.upstreamResponseCode, e)
    case e: Throwable =>
      NonHttpError(e)
  }

}
