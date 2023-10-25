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

package unit.connectors

import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.error.{HttpResultError, MapResultError, NonHttpError}
import uk.gov.hmrc.http.BadRequestException
import util.UnitSpec

class MapResultErrorSpec extends UnitSpec with MapResultError {

  "mapResultError" should {
    "map members of HttpException to HttpResultError" in {
      val exception = new BadRequestException("BOOM")

      mapResultError(exception) shouldBe HttpResultError(BAD_REQUEST, exception)
    }
    "map exceptions that are not HttpResultError, Upstream4xxResponse, Upstream5xxResponse to NonHttpError" in {
      val exception = new Exception("BOOM")

      mapResultError(exception) shouldBe NonHttpError(exception)
    }
  }

}
