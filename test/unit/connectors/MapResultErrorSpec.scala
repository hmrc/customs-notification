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

package unit.connectors

import uk.gov.hmrc.customs.notification.connectors.MapResultError
import uk.gov.hmrc.http.{BadRequestException, Upstream4xxResponse, Upstream5xxResponse}
import util.UnitSpec
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.domain.{HttpResultError, NonHttpError}

class MapResultErrorSpec extends UnitSpec with MapResultError {

  "mapResultError" should {
    "map members of HttpException to HttpResultError" in {
      val exception = new BadRequestException("BOOM")

      mapResultError(exception) shouldBe HttpResultError(BAD_REQUEST, exception)
    }
    "map Upstream4xxResponse to HttpResultError" in {
      val exception = Upstream4xxResponse(message ="BOOM", upstreamResponseCode = BAD_REQUEST, reportAs = BAD_REQUEST)

      mapResultError(exception) shouldBe HttpResultError(BAD_REQUEST, exception)
    }
    "map Upstream5xxResponse to HttpResultError" in {
      val exception = Upstream5xxResponse(message ="BOOM", upstreamResponseCode = INTERNAL_SERVER_ERROR, reportAs = INTERNAL_SERVER_ERROR)

      mapResultError(exception) shouldBe HttpResultError(INTERNAL_SERVER_ERROR, exception)
    }
    "map exceptions that are not HttpResultError, Upstream4xxResponse, Upstream5xxResponse to NonHttpError" in {
      val exception = new Exception("BOOM")

      mapResultError(exception) shouldBe NonHttpError(exception)
    }
  }

}
