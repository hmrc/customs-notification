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

package unit.logging

import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.model.SeqOfHeader
import uk.gov.hmrc.http.HeaderCarrier

class StubNotificationLogger(logger: CdsLogger) extends NotificationLogger(logger) {

  override def debug(msg: => String)(implicit hc: HeaderCarrier): Unit =
    println(msg)
  override def debug(msg: => String, url: => String)(implicit hc: HeaderCarrier): Unit =
    println(msg)
  override def debug(msg: => String, url: => String, payload: => String)(implicit hc: HeaderCarrier): Unit =
    println(msg)
  override def debug(msg: => String, headers: => SeqOfHeader): Unit =
    println(msg)
  override def info(msg: => String)(implicit hc: HeaderCarrier): Unit =
    println(msg)
  override def error(msg: => String)(implicit hc: HeaderCarrier): Unit =
    println(msg)
  override def error(msg: => String, headers: => SeqOfHeader): Unit =
    println(msg)
  override def debugWithoutRequestContext(s: => String): Unit =
    println(s)

}
