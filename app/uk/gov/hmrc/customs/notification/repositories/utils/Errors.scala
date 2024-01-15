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

package uk.gov.hmrc.customs.notification.repositories.utils

import uk.gov.hmrc.customs.notification.models.LogContext
import uk.gov.hmrc.customs.notification.util.Logger

import scala.concurrent.{ExecutionContext, Future}

object Errors {

  case class MongoDbError(doingWhat: String, cause: Throwable) {
    val message: String = s"MongoDb error while $doingWhat. Exception: ${cause.getMessage}"
  }

  def catchException[A](doingWhat: String)(block: => Future[A])
                       (implicit ec: ExecutionContext,
                        logger: Logger,
                        lc: LogContext): Future[Either[MongoDbError, A]] = {
    block.map { result =>
//      logger.debug(s"${doingWhat.capitalize}. Result: $result")
      Right(result)
    }.recover {
      case t: Throwable =>
        val e = MongoDbError(doingWhat, t)
        logger.error(e.message)
        Left(e)
    }
  }
}
