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

package uk.gov.hmrc.customs.notification.services

import java.time.LocalDateTime
import scala.util.Try

trait Debug {
  def colourln(colour: String, message: String): Unit = {
    println(colour + Console.BLACK + LocalDateTime.now() + " " + message + Console.RESET)
  }


  def extractFunctionCode(payload: String): String = Try {
    val functionCodeIndex = payload.indexOf("p:FunctionCode")
    payload.subSequence(functionCodeIndex, functionCodeIndex + 20).toString
  }.getOrElse(s"FailedToGetFunctionCode from [$payload]")
}

object Debug extends Debug
