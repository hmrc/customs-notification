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

package util

import com.github.tomakehurst.wiremock.client.WireMock._
import play.api.http.Status

trait AuditService extends WireMockRunner {

  private val AuditWriteUrl: String = "/write/audit"

  def stubAuditService(): Unit = stubFor(post(urlEqualTo(AuditWriteUrl))
    .willReturn(
      aResponse()
        .withStatus(Status.OK)))

  def verifyAuditWrite(): Unit = verify(postRequestedFor(urlEqualTo(AuditWriteUrl)))

  def verifyNoAuditWrite(): Unit = verify(0, postRequestedFor(urlEqualTo(AuditWriteUrl)))
}
