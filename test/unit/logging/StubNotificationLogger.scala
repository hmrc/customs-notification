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

package unit.logging

import org.slf4j.LoggerFactory
import uk.gov.hmrc.customs.notification.domain.HasId
import uk.gov.hmrc.customs.notification.logging.{CdsLogger, NotificationLogger}
import uk.gov.hmrc.customs.notification.model.SeqOfHeader

class StubNotificationLogger extends NotificationLogger(MockitoSugarHelper.mock[CdsLogger]) {
  val logger = LoggerFactory.getLogger("StubNotificationLogger")

  override def debug(msg: => String)(implicit rm: HasId): Unit = logger.debug(msg)

  override def debug(msg: =>String, url: =>String)(implicit rm: HasId): Unit = logger.debug(msg + url)

  override def debug(msg: =>String, url: =>String, payload: =>String)(implicit rm: HasId): Unit = logger.debug(msg + url + payload)

  override def debugWithHeaders(msg: =>String, headers: =>SeqOfHeader): Unit = logger.debug(msg + headers)

  override def info(msg: =>String)(implicit rm: HasId): Unit = logger.info(msg)

  override def errorWithHeaders(msg: =>String, headers: =>SeqOfHeader): Unit = logger.error(msg + headers)

  override def error(msg: =>String)(implicit rm: HasId): Unit = logger.error(msg)

  override def error(msg: =>String, t: =>Throwable)(implicit rm: HasId): Unit = logger.error(msg + t.toString, t)

}
