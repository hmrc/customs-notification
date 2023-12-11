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

package uk.gov.hmrc.customs.notification.util

import play.api.Mode
import uk.gov.hmrc.customs.notification.models.LogContext

trait Logger {

  private val _logger: play.api.Logger = play.api.Logger(getClass)
  // So we can, for example, call 'logger.info()' instead of info because that's nicer
  val logger: Logger = this

  def debug(message: => String)(implicit lc: LogContext): Unit = {
    _logger.debug(format(message))
  }

  def info(message: => String)(implicit lc: LogContext): Unit = {
    _logger.info(format(message))
  }

  def warn(message: => String)(implicit lc: LogContext): Unit = {
    _logger.warn(format(message))
  }

  def warn(message: => String, t: => Throwable)(implicit lc: LogContext): Unit = {
    _logger.warn(format(message), t)
  }

  def error(message: => String)(implicit lc: LogContext): Unit = {
    _logger.error(format(message))
  }

  def error(msg: => String, t: => Throwable)(implicit lc: LogContext): Unit = {
    _logger.error(format(msg), t)
  }

  def format(msg: String)(implicit lc: LogContext): String = {
    val b = new StringBuilder()

    // Make logs easier to read in test mode
    def newLineIfTest(): Unit =
      if (play.api.Logger.applicationMode.contains(Mode.Test)) {
        b.append('\n')
      }

    lc.fieldsToLog.foreach { case (k, v) =>
      b.append('[')
      b.append(k)
      b.append('=')
      b.append(v)
      b.append(']')
    }
    b.append(' ')
    newLineIfTest()
    b.append(msg)
    newLineIfTest()
    b.result()
  }
}
