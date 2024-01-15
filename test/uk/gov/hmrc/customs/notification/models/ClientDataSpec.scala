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

package uk.gov.hmrc.customs.notification.models

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json
import uk.gov.hmrc.http.Authorization

import java.net.URL

class ClientDataSpec extends AnyWordSpec
  with Matchers {
  "ClientData json" when {
    "has callback URL and security token" should {
      "parse as PushCallbackData" in {
        val json = Json.parse(
          s"""
             |{
             |  "clientId" : "abc123",
             |  "apiContext" : "customs/declarations",
             |  "apiVersion" : "1.0",
             |  "fieldsId" : "00000000-8888-4444-2222-111111111111",
             |  "fields": {
             |    "callbackUrl": "https://www.example.com",
             |    "securityToken": "some-security-token"
             |  }
             |}
             |""".stripMargin
        )
        val expected = ClientData(
          clientId = ClientId("abc123"),
          sendData = PushCallbackData(
            callbackUrl = new URL("https://www.example.com"),
            securityToken = Authorization("some-security-token"))
        )

        val actual = ClientData.reads.reads(json).get

        actual shouldBe expected
      }
    }

    "has no callback URL" should {
      "parse as SendToPullQueue" in {

        val json = Json.parse(
          s"""
             |{
             |  "clientId" : "abc123",
             |  "apiContext" : "customs/declarations",
             |  "apiVersion" : "1.0",
             |  "fieldsId" : "00000000-8888-4444-2222-111111111111",
             |  "fields": {
             |    "securityToken": "some-security-token"
             |  }
             |}
             |""".stripMargin
        )

        val expected = ClientData(
          clientId = ClientId("abc123"),
          sendData = SendToPullQueue
        )

        val actual = ClientData.reads.reads(json).get

        actual shouldBe expected
      }
    }

    "has an invalid callback URL" should {
      "return an error" in {
        val json = Json.parse(
          s"""
             |{
             |  "clientId" : "abc123",
             |  "apiContext" : "customs/declarations",
             |  "apiVersion" : "1.0",
             |  "fieldsId" : "00000000-8888-4444-2222-111111111111",
             |  "fields": {
             |    "callbackUrl": "not-a-valid-url",
             |    "securityToken": "some-security-token"
             |  }
             |}
             |""".stripMargin
        )

        val actual = ClientData.reads.reads(json)

        actual.isError shouldBe true
      }
    }
  }
}
