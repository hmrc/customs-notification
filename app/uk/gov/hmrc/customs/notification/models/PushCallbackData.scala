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

package uk.gov.hmrc.customs.notification.models

import play.api.libs.json._
import uk.gov.hmrc.http.Authorization

import java.net.URL
import scala.util.{Failure, Success, Try}


case class PushCallbackData(callbackUrl: Option[URL],
                            securityToken: Authorization)


object PushCallbackData {
  implicit val authReads: Reads[Authorization] = Json.valueReads[Authorization]
  implicit val urlReads: Reads[URL] = {
    case JsString(urlStr) => Try(new URL(urlStr)) match {
      case Success(url) => JsSuccess(url)
      case Failure(_) => JsError("error.malformed.url")
    }
    case _ => JsError("error.expected.url")
  }
  implicit val reads: Reads[PushCallbackData] = Json.reads[PushCallbackData]

}

