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

import java.net.URL

import play.api.libs.json._
import uk.gov.hmrc.customs.notification.domain.{CallbackUrl, DeclarantCallbackData}
import _root_.util.UnitSpec

class DeclarantCallbackDataSpec extends UnitSpec {


  private val expectedJson: JsValue = Json.parse("""
                                                   |{
                                                   |  "callbackUrl" : "https://NOTIFICATION",
                                                   |  "securityToken" : "abc"
                                                   |}
                                      """.stripMargin)

  "DeclarantCallbackData model" should {
    "serialise to Json" in {
      val request = DeclarantCallbackData(CallbackUrl(Some(new URL("https://NOTIFICATION"))), "abc")
      val actualJson: JsValue = Json.toJson(request)

      actualJson shouldBe expectedJson
    }

    "deserialise from Json" in {

      val json: JsValue = Json.parse(
        """
          |{
          |  "callbackUrl" : "http://abc",
          |  "securityToken" : "abc"
          |}
          """.stripMargin)

      json.validate[DeclarantCallbackData] shouldBe JsSuccess(DeclarantCallbackData(CallbackUrl(Some(new URL("http://abc"))),"abc"))

    }

    "throw an error when deserialising from Json when callbackUrl field is not present" in {

      val jsValue: JsValue = Json.parse(
        """
          |{
          |  "securityToken" : "abc"
          |}
          """.stripMargin)

      val jsResult: JsResult[DeclarantCallbackData] = jsValue.validate[DeclarantCallbackData]
      jsResult shouldBe
        new JsError(List((new JsPath(List(KeyPathNode("callbackUrl"))), List(JsonValidationError(List("error.path.missing"))))))

    }

    "deserialise from Json when callbackUrl field is empty" in {

      val jsValue: JsValue = Json.parse(
        """
          |{
          |  "callbackUrl" : "",
          |  "securityToken" : "abc"
          |}
          """.stripMargin)

      val jsResult: JsResult[DeclarantCallbackData] = jsValue.validate[DeclarantCallbackData]
      jsResult shouldBe JsSuccess(DeclarantCallbackData(CallbackUrl(None),"abc"))

    }

  }
}
