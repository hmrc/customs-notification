package uk.gov.hmrc.lock

import java.util.UUID

import play.api.libs.json.Json
import reactivemongo.api.DB
import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId
import uk.gov.hmrc.lock.LockFormats.expiryTime
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future



class NotificationLockRepository(implicit mongo: () => DB) extends LockRepository {
  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats

  def findAllNonExpiredLocks(): Future[List[ClientSubscriptionId]] = withCurrentTime { now =>
      super.find(expiryTime -> Json.obj("$gte" -> now.toDateTime())).map({
        listOfLocks => listOfLocks.map(lock=> ClientSubscriptionId(UUID.fromString(lock.id)))
      })
  }
}
