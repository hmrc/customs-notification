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

package unit.logging

import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

case class StubCdsLogger() extends CdsLogger(MockitoSugarHelper.mock[ServicesConfig]){

  override def debug(msg: =>String): Unit = println(msg)

  override def debug(msg: =>String, e: =>Throwable): Unit = println(msg + e.toString)

  override def info(msg: =>String): Unit = println(msg)

  override def info(msg: =>String, e: =>Throwable): Unit = println(msg + e.toString)

  override def warn(msg: =>String): Unit = println(msg)

  override def warn(msg: =>String, e: =>Throwable): Unit = println(msg+e.toString)

  override def error(msg: =>String): Unit = println(msg)

  override def error(msg: =>String, e: =>Throwable): Unit = println(msg+e.toString)
}
