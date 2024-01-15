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

import org.bson.types.ObjectId
import org.mongodb.scala.model.Updates.set
import org.mongodb.scala.model.*
import org.mongodb.scala.result.{DeleteResult, UpdateResult}
import org.mongodb.scala.{ClientSession, ClientSessionOptions, MongoException, ReadConcern, ToSingleObservableVoid, TransactionOptions, WriteConcern}
import play.api.libs.json.*
import uk.gov.hmrc.customs.notification.config.{RepoConfig, RetryDelayConfig}
import uk.gov.hmrc.customs.notification.models.*
import uk.gov.hmrc.customs.notification.models.ProcessingStatus.*
import uk.gov.hmrc.customs.notification.repositories.BlockedCsidRepository.*
import uk.gov.hmrc.customs.notification.repositories.NotificationRepository as NotificationRepo
import uk.gov.hmrc.customs.notification.repositories.utils.Errors.{MongoDbError, catchException}
import uk.gov.hmrc.customs.notification.repositories.utils.MongoDbUtils
import uk.gov.hmrc.customs.notification.services.DateTimeService
import uk.gov.hmrc.customs.notification.util.FutureEither.Ops.*
import uk.gov.hmrc.customs.notification.util.Helpers.ignoreResult
import uk.gov.hmrc.customs.notification.util.{FutureEither, Logger}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.play.json.formats.{MongoFormats, MongoJavatimeFormats}

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class BlockedCsidRepository @Inject()(mongoComponent: MongoComponent,
                                      dateTimeService: DateTimeService,
                                      notificationRepo: NotificationRepository,
                                      config: RepoConfig,
                                      retryDelayConfig: RetryDelayConfig)
                                     (implicit ec: ExecutionContext)
  extends PlayMongoRepository[BlockedCsid](
    mongoComponent = mongoComponent,
    collectionName = "blocked-client-subscription-ids",
    domainFormat = BlockedCsid.format,
    indexes = indexes(config.notificationTtl)
  ) with Logger {

  private def nowInstant() = dateTimeService.now().toInstant

  def getFailedAndBlockedCount(clientId: ClientId)
                              (implicit lc: LogContext): Future[Either[MongoDbError, Long]] =
    catchException(s"getting count of FailedAndBlocked notifications for client ID [$clientId]") {
      val selector = Filters.and(
        Filters.eq(NotificationRepo.Fields.ClientId, clientId.id),
        Filters.eq(NotificationRepo.Fields.Status, FailedAndBlocked.toBson)
      )
      notificationRepo.underlying.collection.countDocuments(selector).toFuture()
    }

  def checkIfCsidIsBlocked(csid: ClientSubscriptionId)
                          (implicit lc: LogContext): Future[Either[MongoDbError, Boolean]] = {
    catchException(s"checking if csid [${csid.toString}] is blocked") {
      collection
        .find(csidIsBlockedFilter(csid))
        .first()
        .toFutureOption()
        .map {
          case Some(n) =>
            logger.info(s"csid [${csid.toString}] is blocked until [${n.availableAt}]")
            true
          case None =>
            logger.info(s"csid [${csid.toString}] is not blocked")
            false
        }
    }
  }

  private def csidIsBlockedFilter(csid: ClientSubscriptionId) = Filters.and(
    Filters.eq(Fields.ClientSubscriptionId, csid.toString),
    Filters.gt(Fields.AvailableAt, nowInstant())
  )

  def blockCsid(notification: Notification)
               (implicit lc: LogContext): Future[Either[MongoDbError, Unit]] = {
    val blockedCsidAndNoRetryAttemptedFilter = Filters.and(
      csidIsBlockedFilter(notification.csid),
      Filters.eq(Fields.retryAttempted, false)
    )

    def checkIfCsidIsBlockedOrRetried: Future[Boolean] = {
      collection
        .find(blockedCsidAndNoRetryAttemptedFilter)
        .first()
        .toFutureOption()
        .map(_.isDefined)
    }

    def updateRetryAttempted(): Future[Unit] = {
      collection
        .updateOne(
          filter = blockedCsidAndNoRetryAttemptedFilter,
          update = Updates.set(Fields.retryAttempted, true)
        )
        .toFuture()
        .map(ignoreResult)
    }

    def insertBlockedCsid(): Future[UpdateResult] = {
      val availableAt = dateTimeService.now().plusSeconds(retryDelayConfig.failedAndBlocked.toSeconds)
      val blockFilter = Filters.eq(Fields.ClientSubscriptionId, notification.csid.toString)
      val blockedNotification = BlockedCsid(
        csid = notification.csid,
        createdAt = dateTimeService.now().toInstant,
        availableAt = availableAt.toInstant,
        clientId = notification.clientId,
        notificationId = notification.id,
        retryAttempted = false
      )

      collection
        .replaceOne(
          filter = blockFilter,
          replacement = blockedNotification,
          options = ReplaceOptions().upsert(true)
        ).toFuture()
    }

    def blockAllNotificationsForCsid(): Future[Unit] = {
      val selector = Filters.and(
        Filters.eq(NotificationRepo.Fields.ClientSubscriptionId, notification.csid.toString),
        Filters.or(
          Filters.eq(NotificationRepo.Fields.Status, SavedToBeSent.toBson),
          Filters.eq(NotificationRepo.Fields.Status, FailedButNotBlocked.toBson)
        )
      )
      val update =
        Updates.combine(
          set(NotificationRepo.Fields.Status, FailedAndBlocked.toBson),
          set(NotificationRepo.Fields.UpdatedAt, nowInstant())
        )

      notificationRepo.underlying.collection
        .updateMany(selector, update)
        .toFuture()
        .map(ignoreResult)
    }

    def incNotificationFailureCount(): Future[Unit] = {
      notificationRepo.underlying.collection.updateOne(
        filter = Filters.eq(NotificationRepo.Fields.WorkItemId, notification.id),
        update = Updates.combine(
          Updates.inc(NotificationRepo.Fields.FailureCount, 1)
        )
      ).toFuture()
        .map(_.getModifiedCount match {
          case 1 =>
            Right(())
          case _ =>
            throw new IllegalStateException("Notification doesn't exist")
        })
    }

    transaction { (startTransaction, commitTransaction) =>
      startTransaction()
      (for {
        csidIsBlockedOrRetried <- FutureEither.liftF(checkIfCsidIsBlockedOrRetried)
        _ <- catchException("blocking csid and setting notifications to FailedAndBlocked") {
          if (csidIsBlockedOrRetried) {
            updateRetryAttempted()
          } else {
            for {
              _ <- incNotificationFailureCount()
              res <- insertBlockedCsid()
              _ <- if (res.getUpsertedId != null) blockAllNotificationsForCsid() else Future.unit
            } yield ()
          }
        }.toFutureEither
        _ <- FutureEither.liftF(commitTransaction())
      } yield ()).value
    }
  }

  def unblockCsid(csid: ClientSubscriptionId)
                 (implicit lc: LogContext): Future[Either[MongoDbError, Long]] =
    unblockCsid(
      doingWhatMsg = s"unblocking csid [$csid]",
      updateNotificationMatchField = NotificationRepository.Fields.ClientSubscriptionId,
      matchOnValue = csid.toString,
      deleteCsidMatchField = Fields.ClientSubscriptionId
    )

  def unblockCsid(clientId: ClientId)
                 (implicit lc: LogContext): Future[Either[MongoDbError, Long]] =
    unblockCsid(
      doingWhatMsg = s"deleting blocked csids for client ID [${clientId.id}]",
      updateNotificationMatchField = NotificationRepository.Fields.ClientId,
      matchOnValue = clientId.id,
      deleteCsidMatchField = Fields.ClientId,
    )

  private def unblockCsid(doingWhatMsg: String,
                          updateNotificationMatchField: String,
                          matchOnValue: String,
                          deleteCsidMatchField: String)
                         (implicit lc: LogContext): Future[Either[MongoDbError, Long]] = {
    def unblockFailedAndBlocked(): Future[UpdateResult] =
      notificationRepo.underlying.collection
        .updateMany(
          filter = Filters.and(
            Filters.eq(updateNotificationMatchField, matchOnValue),
            Filters.eq(NotificationRepo.Fields.Status, FailedAndBlocked.toBson)
          ),
          update = Updates.set(NotificationRepo.Fields.Status, FailedButNotBlocked.toBson)
        )
        .toFuture()

    def deleteBlockedCsid(): Future[DeleteResult] =
      collection
        .deleteMany(
          Filters.eq(deleteCsidMatchField, matchOnValue)
        )
        .toFuture()

    transaction { (startTransaction, commitTransaction) =>
      catchException(doingWhatMsg) {
        startTransaction()
        for {
          res <- unblockFailedAndBlocked()
          _ <- deleteBlockedCsid()
          _ <- commitTransaction()
        } yield res.getModifiedCount
      }
    }
  }

  def getSingleNotificationToUnblock()(implicit lc: LogContext): Future[Either[MongoDbError, Option[Notification]]] = {
    val maybeGetCsidToUnblock =
      catchException("getting eligible csid to unblock") {
        collection
          .findOneAndUpdate(
            filter = Filters.and(
              Filters.eq(Fields.retryAttempted, false),
              Filters.lte(Fields.AvailableAt, nowInstant())
            ),
            update = Updates.set(Fields.retryAttempted, true)
          )
          .toFutureOption()
          .map(_.map(_.notificationId))
      }

    val unblockNotifications = (id: ObjectId) =>
      catchException("unblocking FailedAndBlocked notifications for csid") {
        notificationRepo
          .underlying
          .findById(id)
          .map(_.map(NotificationRepo.Mapping.repoToDomain))
      }

    transaction { (startTransaction, commitTransaction) =>
      startTransaction()
      (for {
        maybeId <- maybeGetCsidToUnblock.toFutureEither
        maybeNotification <- maybeId match {
          case None =>
            FutureEither.pure(None)
          case Some(id) =>
            unblockNotifications(id).toFutureEither
        }
        _ <- FutureEither.liftF(commitTransaction())
      } yield maybeNotification)
        .value
    }
  }

  private val transactionOptions: TransactionOptions =
    TransactionOptions.builder()
      .readConcern(ReadConcern.SNAPSHOT)
      .writeConcern(WriteConcern.ACKNOWLEDGED)
      .build()
  private val sessionOptions: ClientSessionOptions =
    ClientSessionOptions.builder()
      .causallyConsistent(true)
      .build()

  private type StartTransaction = () => Unit
  private type CommitTransaction = () => Future[Unit]

  private def transaction[A](block: (StartTransaction, CommitTransaction) => Future[Either[MongoDbError, A]]): Future[Either[MongoDbError, A]] = {

    def commit(clientSession: ClientSession): Future[Unit] = {

      def loop(clientSession: ClientSession, retryAttempt: Int): Future[Unit] =
        clientSession
          .commitTransaction()
          .map(ignoreResult)
          .toFuture()
          .map(ignoreResult)
          .recoverWith(recovery(clientSession, retryAttempt))

      def recovery(clientSession: ClientSession,
                   retryAttempt: Int
                  ): PartialFunction[Throwable, Future[Unit]] = {
        case e: MongoException if e.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL) =>
          if (retryAttempt < config.maxTransactionRetries) {
            loop(clientSession, retryAttempt + 1)
          } else {
            throw e
          }
        case e: MongoException =>
          throw e
      }

      loop(clientSession, retryAttempt = 0)
    }

    FutureEither.liftF(mongoComponent.client.startSession(sessionOptions).toFuture())
      .flatMap { clientSession =>
        val startTransaction = () => clientSession.startTransaction(transactionOptions)
        val commitTransaction = () => commit(clientSession)
        block(startTransaction, commitTransaction)
          .toFutureEither
      }
      .value
  }
}

object BlockedCsidRepository {
  case class BlockedCsid(csid: ClientSubscriptionId,
                         createdAt: Instant,
                         availableAt: Instant,
                         clientId: ClientId,
                         notificationId: ObjectId,
                         retryAttempted: Boolean)

  object BlockedCsid {
    implicit val objectIdFormats: Format[ObjectId] = MongoFormats.objectIdFormat
    implicit val instantFormat: Format[Instant] = MongoJavatimeFormats.instantFormat
    implicit val format: Format[BlockedCsid] = Json.format[BlockedCsid]
  }

  object Fields {
    val ClientSubscriptionId = "csid"
    val CreatedAt = "createdAt"
    val AvailableAt = "availableAt"
    val ClientId = "clientId"
    val NotificationId = "notificationId"
    val retryAttempted = "retryAttempted"
  }

  private def indexes(ttl: FiniteDuration): List[IndexModel] = List(
//    MongoDbUtils.ttlIndexFor(Fields.CreatedAt, ttl),

    /** [[uk.gov.hmrc.customs.notification.repositories.BlockedCsidRepository.checkIfCsidIsBlocked]] */
    IndexModel(
      keys = Indexes.descending(
        Fields.ClientSubscriptionId,
        Fields.AvailableAt
      )
    ),

    /** [[uk.gov.hmrc.customs.notification.repositories.BlockedCsidRepository.blockCsid]] */
    /** [[uk.gov.hmrc.customs.notification.repositories.BlockedCsidRepository.unblockCsid(ClientSubscriptionId)]] */
    IndexModel(
      keys = Indexes.descending(
        Fields.ClientSubscriptionId
      ),
      indexOptions = IndexOptions()
        .unique(true)
    ),

    /** [[uk.gov.hmrc.customs.notification.repositories.BlockedCsidRepository.unblockCsid(ClientId)]] */
    IndexModel(
      keys = Indexes.descending(
        Fields.ClientId
      )
    ),

    /** [[uk.gov.hmrc.customs.notification.repositories.BlockedCsidRepository.getSingleNotificationToUnblock]] */
    IndexModel(
      keys = Indexes.descending(
        Fields.retryAttempted,
        Fields.AvailableAt
      ),
      indexOptions = IndexOptions()
        .partialFilterExpression(
          Filters.eq(Fields.retryAttempted, false)
        )
    ),
  )
}
