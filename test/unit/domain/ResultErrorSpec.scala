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

package unit.domain

import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.domain.{HttpResultError, NonHttpError}
import util.UnitSpec

class ResultErrorSpec extends UnitSpec {
  val exception = new Exception("BOOM")
  "NonHttpError" should {
    "return false for all HTTP status query methods" in {
      val actual = NonHttpError(exception)

      actual.is3xx shouldBe false
      actual.is4xx shouldBe false
      actual.not3xxOr4xx shouldBe false
    }
    "return cause" in {
      val actual = NonHttpError(exception)

      actual.cause shouldBe exception
    }
  }
  "HttpResultError" should {
    "return false for is3xx when 299 HTTP status" in {
      val actual = HttpResultError(299, exception)

      actual.is3xx shouldBe false
    }
    "return true for is3xx when 300 HTTP status" in {
      val actual = HttpResultError(300, exception)

      actual.is3xx shouldBe true
    }
    "return true for is3xx when 399 HTTP status" in {
      val actual = HttpResultError(399, exception)

      actual.is3xx shouldBe true
    }
    "return false for is3xx when 400 HTTP status" in {
      val actual = HttpResultError(400, exception)

      actual.is3xx shouldBe false
    }

    "return false for is4xx when 399 HTTP status" in {
      val actual = HttpResultError(399, exception)

      actual.is4xx shouldBe false
    }
    "return true for is3xx when 400 HTTP status" in {
      val actual = HttpResultError(400, exception)

      actual.is4xx shouldBe true
    }
    "return true for is4xx when 499 HTTP status" in {
      val actual = HttpResultError(499, exception)

      actual.is4xx shouldBe true
    }
    "return false for is4xx when 500 HTTP status" in {
      val actual = HttpResultError(500, exception)

      actual.is4xx shouldBe false
    }

    "return false for not3xxOr4xx when 300 HTTP status" in {
      val actual = HttpResultError(300, exception)

      actual.not3xxOr4xx shouldBe false
    }
    "return false for not3xxOr4xx when 400 HTTP status" in {
      val actual = HttpResultError(400, exception)

      actual.not3xxOr4xx shouldBe false
    }
    "return true for not3xxOr4xx when 500 HTTP status" in {
      val actual = HttpResultError(500, exception)

      actual.not3xxOr4xx shouldBe true
    }

    "return cause" in {
      val actual = HttpResultError(BAD_REQUEST, exception)

      actual.cause shouldBe exception
    }
  }
}
