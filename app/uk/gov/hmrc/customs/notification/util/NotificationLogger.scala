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

import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.models._

import javax.inject.{Inject, Singleton}

// TODO: Write tests
@Singleton
class NotificationLogger @Inject()(logger: CdsLogger) {
  def debug(msg: => String): Unit = {
    logger.debug(msg)
  }

  def debug[A: Loggable](msg: => String, toLog: A): Unit = {
    logger.debug(format(msg, toLog))
  }

  def info[A: Loggable](msg: => String, toLog: A): Unit = {
    logger.info(format(msg, toLog))
  }

  def warn[A: Loggable](msg: => String, toLog: A): Unit = {
    logger.warn(format(msg, toLog))
  }

  def warn[A: Loggable](msg: => String, t: => Throwable, toLog: A): Unit = {
    logger.warn(format(msg, toLog), t)
  }

  def error(msg: => String): Unit = {
    logger.error(msg)
  }

  def error[A: Loggable](msg: => String, toLog: A): Unit = {
    logger.error(format(msg, toLog))
  }

  def error[A: Loggable](msg: => String, t: => Throwable, toLog: A): Unit = {
    logger.error(format(msg, toLog), t)
  }

  def format[A](msg: String, toLog: A)(implicit ev: Loggable[A]): String = {
    val prefix = {
      ev.fieldsToLog(toLog)
        .collect { case (k, Some(v)) => s"[$k=$v]" }
        .mkString
    }
    s"$prefix $msg"
  }
}
