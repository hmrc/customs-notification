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

import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.customs.notification.domain.CustomsNotificationsMetricsRequest
import util.UnitSpec
import util.CustomsNotificationMetricsTestData.{EventEnd, EventStart}
import util.TestData.conversationId

class CustomsNotificationsMetricsRequestSpec extends UnitSpec {


  private val expectedJson: JsValue = Json.parse("""
                                        |{
                                        |  "eventType" : "NOTIFICATION",
                                        |  "conversationId" : "eaca01f9-ec3b-4ede-b263-61b626dde231",
                                        |  "eventStart" : "2016-01-30T23:46:59Z[UTC]",
                                        |  "eventEnd" : "2016-01-30T23:47:01Z[UTC]"
                                        |}
                                      """.stripMargin)

  "CustomsDeclarationsMetricsRequest model" should {
    "serialise to Json" in {
      val request = CustomsNotificationsMetricsRequest("NOTIFICATION", conversationId, EventStart, EventEnd)
      val actualJson: JsValue = Json.toJson(request)

      actualJson shouldBe expectedJson
    }

  }
}
