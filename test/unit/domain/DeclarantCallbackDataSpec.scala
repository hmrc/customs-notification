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

package unit.domain

import java.net.URL

import play.api.libs.json.{JsError, JsResult, JsSuccess, JsValue, Json}
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationsMetricsRequest, DeclarantCallbackData}
import util.UnitSpec
import util.CustomsNotificationMetricsTestData.{EventEnd, EventStart}
import util.TestData.conversationId

class DeclarantCallbackDataSpec extends UnitSpec {


  private val expectedJson: JsValue = Json.parse("""
                                                   |{
                                                   |  "callbackUrl" : "https://NOTIFICATION",
                                                   |  "securityToken" : "abc"
                                                   |}
                                      """.stripMargin)

  "DeclarantCallbackData model" should {
    "serialise to Json" in {
      val request = DeclarantCallbackData(Some(new URL("https://NOTIFICATION")), "abc")
      val actualJson: JsValue = Json.toJson(request)

      actualJson shouldBe expectedJson
    }

    "deserialise from Json" in {

      val value1: JsValue = Json.parse(
        """
          |{
          |  "callbackUrl" : "http://AAAAAA",
          |  "securityToken" : "abc"
          |}
                                      """.stripMargin)

      val unit = value1.validate[DeclarantCallbackData] match {
        case JsSuccess(place, _) => {
          println("XXXXXXXXXXXXXXXXXXXXXXXXX")
          println(place)
        }
        case e: JsError => {
          println("YYYYYYYYYYYYYYYYYYYYYYYYYYY")
          println(e)
        }
      }
      unit

      //value1 shouldBe expectedJson
    }

    "deserialise from Json when callbackUrl field is not present" in {

      val jsValue: JsValue = Json.parse(
        """
          |{
          |  "securityToken" : "abc"
          |}
          """.stripMargin)

      val jsResult: JsResult[DeclarantCallbackData] = jsValue.validate[DeclarantCallbackData]
      jsResult shouldBe JsSuccess(DeclarantCallbackData(None,"abc"))

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
      jsResult shouldBe JsSuccess(DeclarantCallbackData(None,"abc"))

    }

  }
}
