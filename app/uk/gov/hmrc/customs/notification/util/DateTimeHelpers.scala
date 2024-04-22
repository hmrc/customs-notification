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

package uk.gov.hmrc.customs.notification.util

import java.time.{LocalDateTime, ZonedDateTime}

object DateTimeHelpers {

  implicit class DateTimeToZonedDateTimeOps(val localDateTime: LocalDateTime) extends AnyVal {
      def toZonedDateTime: ZonedDateTime = {
        import java.time.ZoneId
//        val instant = Instant.ofEpochMilli(dateTime.getMillis)
//        ZonedDateTime.ofInstant(dateTime, ZoneId.of("UTC"))
        localDateTime.atZone(ZoneId.of("UTC"))
      }
  }

  implicit class ZonedDateTimeToDateTimeOps(val zonedDateTime: ZonedDateTime) extends AnyVal {
    def toDateTime: LocalDateTime = {
//      val dateTimeZone = DateTimeZone.forID(zonedDateTime.getZone.getId)
//      new DateTime(zonedDateTime.toInstant.toEpochMilli, dateTimeZone)
      zonedDateTime.toLocalDateTime
    }
  }

}
