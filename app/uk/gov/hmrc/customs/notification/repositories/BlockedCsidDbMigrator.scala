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

package uk.gov.hmrc.customs.notification.repositories

import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.*
import org.reactivestreams.{Subscriber, Subscription}
import uk.gov.hmrc.customs.notification.config.BlockedCsidDbMigrationConfig
import uk.gov.hmrc.customs.notification.models.Loggable.Implicits.loggableNotification
import uk.gov.hmrc.customs.notification.models.ProcessingStatus.FailedAndBlocked
import uk.gov.hmrc.customs.notification.models.{LogContext, Notification}
import uk.gov.hmrc.customs.notification.repositories.NotificationRepository.*
import uk.gov.hmrc.customs.notification.util.FutureEither.Ops.FutureEitherOps
import uk.gov.hmrc.customs.notification.util.{FutureEither, Logger}

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future, Promise}

@Singleton
class BlockedCsidDbMigrator @Inject()(notificationRepo: NotificationRepository,
                                      blockedCsidRepo: BlockedCsidRepository,
                                      config: BlockedCsidDbMigrationConfig
                                      )(implicit ec: ExecutionContext) extends Logger {
  if (config.enabled) {
    migrate()
  }

  def migrate(): Future[Int] = {
    val latestAvailableAt = "latestAvailableAt"
    val docs1 = "docs1"
    val origAvailableAt = s"$docs1.${NotificationRepository.Fields.AvailableAt}"
    val docs2 = "docs2"
    val docs3 = "docs3"
    val pipeline = List(
      Aggregates.`match`(
        Filters.eq("status", FailedAndBlocked.toBson)
      ),
      Aggregates.group(
        id = s"$$${Fields.ClientSubscriptionId}",
        fieldAccumulators =
          Accumulators.max(latestAvailableAt, s"$$${NotificationRepository.Fields.AvailableAt}"),
        Accumulators.push(docs1, "$$ROOT")
      ),
      Aggregates.project(Document(
        docs2 -> Document(
          "$filter" -> Document(
            "input" -> s"$$$docs1",
            "as" -> s"$docs1",
            "cond" -> Document(
              "$eq" -> List(s"$$$$$origAvailableAt", s"$$$latestAvailableAt")
            )
          )
        ),
        "_id" -> 0
      )),
      Aggregates.project(Document(
        docs3 -> Document(
          "$first" -> s"$$$docs2"
        )
      )),
      Aggregates.replaceWith(s"$$$docs3")
    )

    val promise: Promise[Int] = Promise()

    implicit val lc: LogContext = LogContext.empty
    logger.info("Starting blocked csid migration")
    notificationRepo.underlying.collection
      .aggregate(pipeline)
      .map(NotificationRepository.Mapping.repoToDomain)
      .subscribe(new NotificationObserver(promise))

    promise.future
  }

  private class NotificationObserver(promise: Promise[Int])
                            (implicit lc: LogContext) extends Subscriber[Notification] {
    @volatile
    private var subscription: Option[Subscription] = None

    private val ignored: AtomicInteger = new AtomicInteger()
    private val failed: AtomicInteger = new AtomicInteger()
    private val completed: AtomicInteger = new AtomicInteger()

    override def onSubscribe(s: Subscription): Unit = {
      subscription = Some(s)
      s.request(1)
    }

    override def onNext(notification: Notification): Unit = {
      for {
        isAlreadyMigrated <- blockedCsidRepo.checkIfCsidIsBlocked(notification.csid)
          .toFutureEither
          .ignoreError
        _ <- if (isAlreadyMigrated) {
          ignored.incrementAndGet()
          FutureEither.unit
        } else {
          FutureEither.liftF(
            blockedCsidRepo.blockCsid(notification)(LogContext(notification))
              .map {
                case Left(_) =>
                  logger.error(s"Couldn't migrate csid [${notification.csid}]")
                  failed.incrementAndGet()
                case Right(_) =>
                  logger.info(s"Migrated blocked csid [${notification.csid}]")
                  completed.incrementAndGet()
              }
          ).ignoreError
        }
      } yield {
        logger.info("Migrated one thing")
        subscription.foreach(_.request(1))
      }
    }

    override def onError(t: Throwable): Unit = {
      val updatedFailed = failed.get() + 1
      logger.error(s"There was an error migrating blocked csids (${completed.get()} completed, ${ignored.get()} ignored, $updatedFailed failed): ${t.getMessage}")
      promise.success(completed.get()).future
    }

    override def onComplete(): Unit = {
      logger.info(s"Successfully migrated csids (${completed.get()} completed, ${ignored.get()} ignored, ${failed.get()} failed)")
      promise.success(completed.get()).future
    }
  }
}
