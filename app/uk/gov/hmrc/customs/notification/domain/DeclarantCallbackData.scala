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

package uk.gov.hmrc.customs.notification.domain

import play.api.libs.json._

import java.net.URL
import scala.util.Try

case class CallbackUrl(url: Option[URL]) extends AnyVal {
  override def toString: String = url.fold("")(_.toString)

  def isPush: Boolean = url.isDefined
  def isPull: Boolean = url.isEmpty
}

case class DeclarantCallbackData(callbackUrl: CallbackUrl, securityToken: String)

object DeclarantCallbackData {

  implicit object CallbackUrlFormat extends Format[CallbackUrl] {

    override def reads(json: JsValue): JsResult[CallbackUrl] = json match {
      case JsString(s) =>
        if(s.isEmpty) {
          JsSuccess(CallbackUrl(None))
        } else {
          parseUrl(s).map(url => JsSuccess(CallbackUrl(Some(url)))).getOrElse(JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.url")))))
        }
      case _ => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.url"))))
    }

    private def parseUrl(s: String): Option[URL] = Try(new URL(s)).toOption

    override def writes(o: CallbackUrl): JsValue = JsString(o.toString)
  }

  implicit val jsonFormat: OFormat[DeclarantCallbackData] = Json.format[DeclarantCallbackData]

}

