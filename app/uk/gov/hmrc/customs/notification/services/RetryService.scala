/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.customs.notification.services

import akka.actor.{ActorSystem, Scheduler}
import akka.pattern.after
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.{ConversationId, ResultError}
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.http.HttpResponse

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

// idea for retry taken from from https://gist.github.com/viktorklang/9414163
@Singleton
class RetryService @Inject()(configService: ConfigService, logger: CdsLogger, actorSystem: ActorSystem) {

  def retry(f: => Future[Either[ResultError, HttpResponse]])
           (implicit conversationId: ConversationId, ec: ExecutionContext): Future[Either[ResultError, HttpResponse]] = {
    retry(
      f,
      configService.pushNotificationConfig.retryDelay,
      delayFactor = 1,
      configService.pushNotificationConfig.retryMaxAttempts -1,
      actorSystem.scheduler
    )
  }

  private def retry(
    f: => Future[Either[ResultError, HttpResponse]],
    delay: FiniteDuration, delayFactor: Int, retries: Int, s: Scheduler)
    (implicit conversationId: ConversationId, ec: ExecutionContext): Future[Either[ResultError, HttpResponse]] = {

    if (retries == configService.pushNotificationConfig.retryMaxAttempts -1) {
      infoLog(s"First call delay=0, delayFactor=0, remaining retries=$retries")
    } else {
      infoLog(s"Retrying call delay=$delay milliseconds, delayFactor=$delayFactor, remaining retries=$retries")
    }

    f.flatMap{
      case r@Right(_) =>
        Future.successful(r)
      case Left(resultError) if retries > 0 && resultError.not3xxOr4xx =>
        val increasedDelay = delay * delayFactor
        val x: Future[Either[ResultError, HttpResponse]] = after(increasedDelay, s)(retry(f, increasedDelay, configService.pushNotificationConfig.retryDelayFactor, retries - 1, s))
        x
      case l@Left(resultError) => // retries exhausted ie <= 0 or 3XX or 4XX error encountered
        errorLog(s"Aborted retries. is 3XX or 4XX=${!resultError.not3xxOr4xx}, delay=$delay milliseconds, delayFactor=$delayFactor, remaining retries=$retries", resultError.cause)
        Future.successful(l)
    }
  }

  private def infoLog(msg: String)(implicit conversationId: ConversationId): Unit = {
    logger.info(formatLogMsg(msg, conversationId))
  }

  private def errorLog(msg: String, e: Throwable)(implicit conversationId: ConversationId): Unit = {
    logger.error(formatLogMsg(msg, conversationId), e)
  }

  private def formatLogMsg(msg: String, conversationId: ConversationId) = {
    s"[conversationId=$conversationId] $msg"
  }
}
