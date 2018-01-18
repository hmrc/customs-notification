/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.customs.notification.logging

import javax.inject.Singleton

import com.google.inject.Inject
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.logging.LoggingHelper.{formatDebug, formatError, formatInfo, formatWarn}
import uk.gov.hmrc.customs.notification.model.SeqOfHeader
import uk.gov.hmrc.http.HeaderCarrier

@Singleton
class NotificationLogger @Inject()(logger: CdsLogger) {

  def debug(msg: => String)(implicit hc: HeaderCarrier): Unit = logger.debug(formatDebug(msg, None, None))
  def debug(msg: => String, url: => String)(implicit hc: HeaderCarrier): Unit = logger.debug(formatDebug(msg, Some(url), None))
  def debug(msg: => String, url: => String, payload: => String)(implicit hc: HeaderCarrier): Unit = logger.debug(formatDebug(msg, Some(url), Some(payload)))
  def debug(msg: => String, headers: => SeqOfHeader): Unit = logger.debug(formatDebug(msg, headers))
  def info(msg: => String)(implicit hc: HeaderCarrier): Unit = logger.info(formatInfo(msg))
  def info(msg: => String, headers: => SeqOfHeader): Unit = logger.info(formatInfo(msg, headers))
  def warn(msg: => String)(implicit hc: HeaderCarrier): Unit = logger.warn(formatWarn(msg))
  def error(msg: => String, e: => Throwable)(implicit hc: HeaderCarrier): Unit = logger.error(formatError(msg), e)
  def error(msg: => String)(implicit hc: HeaderCarrier): Unit = logger.error(formatError(msg))

}

