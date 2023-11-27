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

package unit.services

import org.scalatest.AppendedClues._
import org.mockito.scalatest.MockitoSugar
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.{AnyWordSpec, AsyncWordSpec}
import play.api.libs.json.{Format, Json}

class TestSpec extends AnyWordSpec
  with MockitoSugar
  with Inside
  with Matchers {

  "Test" when {
    "x" should {
      "y" in {
        val y: Option[Int] = None
        y shouldBe defined withClue "i.e. that the request body was not valid JSON"
//        None shouldBe defined withClue "This wasn't defined?!?!?!"
      }
    }
  }
}



