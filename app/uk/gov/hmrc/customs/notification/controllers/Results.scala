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

package uk.gov.hmrc.customs.notification.controllers

import play.api.http.ContentTypes
import play.api.mvc.Result
import play.api.mvc.Results.{Ok, Status}
import play.mvc.Http.Status.*

private object Results {
  def blockedCountOkResponseFrom(count: Int): Result = {
    val countXml =
      <pushNotificationBlockedCount>
        {count}
      </pushNotificationBlockedCount>
    Ok(scala.xml.Utility.trim(countXml)).as(ContentTypes.XML)
  }

  class Response(val status: Int,
                 val cdsCode: String) extends play.api.mvc.Results.Status(status) {

    def apply(message: String): Result = {
      val xml =
        <errorResponse>
          <code>
            {cdsCode}
          </code>
          <message>
            {message}
          </message>
        </errorResponse>

      val body = scala.xml.Utility.trim(xml).toString

      Status(status)(body).as(ContentTypes.XML)
    }
  }

  object BadRequest extends Response(BAD_REQUEST, "BAD_REQUEST") {
    def apply(): Result = super.apply("Bad request")
  }

  object NotFound extends Response(NOT_FOUND, "NOT_FOUND") {
    def apply(): Result = super.apply("Resource was not found.")
  }

  object Unauthorised extends Response(UNAUTHORIZED, "UNAUTHORIZED")

  object NotAcceptable extends Response(NOT_ACCEPTABLE, "ACCEPT_HEADER_INVALID")

  object UnsupportedMediaType extends Response(UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE")

  object InternalServerError extends Response(INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR") {
    def apply(): Result = super.apply("Internal server error.")
  }
}
