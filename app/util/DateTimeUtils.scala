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

package util

import java.time.ZonedDateTime

import org.joda.time.{DateTime, DateTimeZone}

object DateTimeUtils {

  def convertDateTimeToZonedDateTime(dateTime: DateTime): ZonedDateTime ={
    import java.time.{Instant, ZoneId, ZonedDateTime}
    val instant = Instant.ofEpochMilli(dateTime.getMillis)
    ZonedDateTime.ofInstant(instant, ZoneId.of("UTC"))
  }

 def convertZonedDateTimeToDateTime(zonedDateTime: ZonedDateTime) : DateTime = {
   val dateTimeZone = DateTimeZone.forID(zonedDateTime.getZone.getId)
   new DateTime(zonedDateTime.toInstant.toEpochMilli, dateTimeZone)
 }

}
