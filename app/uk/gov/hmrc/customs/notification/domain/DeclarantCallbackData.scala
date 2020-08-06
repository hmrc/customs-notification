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

package uk.gov.hmrc.customs.notification.domain

import java.net.URL

import cats.syntax.OptionIdOps
import play.api.libs.json.{Format, JsError, JsNull, JsPath, JsResult, JsString, JsSuccess, JsValue, Json, JsonValidationError, OFormat, Writes}

import scala.util.Try
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._

case class DeclarantCallbackData(callbackUrl: Option[URL], securityToken: String)

object DeclarantCallbackData {

//  implicit def optionFormat[T: Format]: Format[Option[T]] = new Format[Option[T]]{
//    override def reads(json: JsValue): JsResult[Option[T]] = json.validateOpt[T]
//
//    override def writes(o: Option[T]): JsValue = o match {
//      case Some(t) ⇒ implicitly[Writes[T]].writes(t)
//      case None ⇒ JsNull
//    }
//  }

//  implicit object HttpOptionUrlFormat extends Format[Option[URL]] {
//
//    override def reads(json: JsValue): JsResult[Option[URL]] = json match {
//      case JsString(s) =>
//        if(s.isEmpty) {
//          JsSuccess(None)
//        } else {
//          parseUrl(s).map(url => JsSuccess(Some(url))).getOrElse(JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.url")))))
//        }
//      case _ => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.url"))))
//    }
//
//    private def parseUrl(s: String): Option[URL] = Try(new URL(s)).toOption
//
//    override def writes(o: Option[URL]): JsValue = JsString(o.getOrElse("").toString)
//  }

//  implicit object HttpOptionUrlFormat extends Format[URL] {
//
//    override def reads(json: JsValue): JsResult[URL] = json match {
//      case JsString(s) =>
//        if(s.isEmpty) {
//          JsSuccess(JsNull)
//        } else {
//          parseUrl(s).map(url => JsSuccess(url)).getOrElse(JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.url")))))
//        }
//      case _ => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.url"))))
//    }
//
//    private def parseUrl(s: String): Option[URL] = Try(new URL(s)).toOption
//
//    override def writes(o: URL): JsValue = JsString(o.toString)
//  }

  implicit object HttpUrl extends Format[URL] {

    override def reads(json: JsValue): JsResult[URL] = json match {
      case JsString(s) => parseUrl(s).map(url => JsSuccess(url)).getOrElse(JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.url")))))
      case _ => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.url"))))
    }

    private def parseUrl(s: String): Option[URL] = Try(new URL(s)).toOption

    override def writes(o: URL): JsValue = JsString(o.toString)
  }

  //implicit val jsonFormat1: OFormat[Option[URL]] = Json.format[Option[URL]]

  implicit def optionFormat[T: Format]: Format[Option[T]] = new Format[Option[T]]{
    override def reads(json: JsValue): JsResult[Option[T]] = json.validateOpt[T]

    override def writes(o: Option[T]): JsValue = o match {
      case Some(t) ⇒ implicitly[Writes[T]].writes(t)
      case None ⇒ JsNull
    }
  }

  implicit val jsonFormat: OFormat[DeclarantCallbackData] = Json.format[DeclarantCallbackData]

}


case class Person(
                   id: Int,
                   firstName: Option[String],
                   lastName: Option[String]
                 )

object Person {

  implicit def optionFormat[T: Format]: Format[Option[T]] = new Format[Option[T]]{
    override def reads(json: JsValue): JsResult[Option[T]] = json.validateOpt[T]

    override def writes(o: Option[T]): JsValue = o match {
      case Some(t) ⇒ implicitly[Writes[T]].writes(t)
      case None ⇒ JsNull
    }
  }

  implicit lazy val personFormat = (
    (__ \ "id").format[Int] and
      (__ \ "first_name").format[Option[String]] and
      (__ \ "last_name").format[Option[String]]
    )(Person.apply, unlift(Person.unapply))
}
