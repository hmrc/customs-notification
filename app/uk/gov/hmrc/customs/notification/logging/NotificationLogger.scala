/*
 * Copyright 2022 HM Revenue & Customs
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

import com.google.inject.Inject
import javax.inject.Singleton
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.HasId
import uk.gov.hmrc.customs.notification.logging.LoggingHelper.{format, formatDebug, formatWithHeaders, formatWithoutHeaders}
import uk.gov.hmrc.customs.notification.model.SeqOfHeader

@Singleton
class NotificationLogger @Inject()(logger: CdsLogger) {

  def debug(msg: => String)(implicit rm: HasId): Unit = {
    logger.debug(format(msg, rm))
  }

  def debug(msg: => String, url: => String)(implicit rm: HasId): Unit = {
    logger.debug(formatDebug(msg, Some(url)))
  }

  def debug(msg: => String, url: => String, payload: => String)(implicit rm: HasId): Unit = {
    logger.debug(formatDebug(msg, Some(url), Some(payload)))
  }

  def debugWithHeaders(msg: => String, headers: => SeqOfHeader): Unit = logger.debug(formatWithHeaders(msg, headers))

  def debugWithPrefixedHeaders(msg: => String, headers: => SeqOfHeader): Unit = logger.debug(formatWithoutHeaders(msg, headers))

  def info(msg: => String)(implicit rm: HasId): Unit = {
    logger.info(format(msg, rm))
  }

  def warn(msg: => String)(implicit rm: HasId): Unit = {
    logger.warn(format(msg, rm))
  }

  def errorWithHeaders(msg: => String, headers: => SeqOfHeader): Unit = logger.error(formatWithHeaders(msg, headers))

  def error(msg: => String)(implicit rm: HasId): Unit = {
    logger.error(format(msg, rm))
  }

  def error(msg: => String, t: => Throwable)(implicit rm: HasId): Unit = {
    logger.error(format(msg, rm), t)
  }

}
