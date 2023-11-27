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

package util

object IntegrationTest {
  val TestHost = "localhost"
  val TestPort = 9666
  val TestOrigin = s"http://$TestHost:$TestPort"

  val ApiSubsFieldsUrlContext = "/field"
  val InternalPushUrlContext = "/some-custom-internal-push-url"
  val ExternalPushUrlContext = "/notify-customs-declarant"
  val MetricsUrlContext = "/log-times"
  val PullQueueContext = "/queue"

  object Responses {
    val apiSubscriptionFieldsOk: String =
      s"""
         |{
         |  "clientId" : "${TestData.ClientId.id}",
         |  "apiContext" : "customs/declarations",
         |  "apiVersion" : "1.0",
         |  "fieldsId" : "${TestData.NewClientSubscriptionId.toString}",
         |  "fields": {
         |    "callbackUrl": "${TestData.ClientPushUrl.toString}",
         |    "securityToken": "${TestData.PushSecurityToken.value}"
         |  }
         |}
         |""".stripMargin
  }
}
