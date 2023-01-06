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

import java.time.{Clock, ZonedDateTime}
import java.util.TimeZone

import org.joda.time.{DateTime, DateTimeZone, Duration}

import scala.concurrent.duration.FiniteDuration

object DateTimeHelpers {

  implicit class DateTimeToZonedDateTimeOps(val dateTime: DateTime) extends AnyVal {
      def toZonedDateTime: ZonedDateTime = {
        import java.time.{Instant, ZoneId, ZonedDateTime}
        val instant = Instant.ofEpochMilli(dateTime.getMillis)
        ZonedDateTime.ofInstant(instant, ZoneId.of("UTC"))
      }
  }

  implicit class ZonedDateTimeToDateTimeOps(val zonedDateTime: ZonedDateTime) extends AnyVal {
    def toDateTime: DateTime = {
      val dateTimeZone = DateTimeZone.forID(zonedDateTime.getZone.getId)
      new DateTime(zonedDateTime.toInstant.toEpochMilli, dateTimeZone)
    }
  }

  implicit class ClockJodaExtensions(clock: Clock) {
    def nowAsJoda: DateTime = {
      new DateTime(
        clock.instant().toEpochMilli,
        DateTimeZone.forTimeZone(TimeZone.getTimeZone(clock.getZone)))
    }
  }

  implicit class FiniteDurationOps(val finiteDuration: FiniteDuration) extends AnyVal {
    def toJodaDuration: Duration = {
      Duration.millis(finiteDuration.toMillis)
    }
  }

}
