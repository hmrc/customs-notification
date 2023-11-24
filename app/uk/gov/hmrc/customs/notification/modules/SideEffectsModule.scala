package uk.gov.hmrc.customs.notification.modules

import com.google.inject.{AbstractModule, Provides}
import org.bson.types.ObjectId
import uk.gov.hmrc.customs.notification.models.NotificationId

import java.time.{ZoneId, ZonedDateTime}
import java.util.UUID

class SideEffectsModule extends AbstractModule {
  @Provides def getCurrentZonedDateTime: () => ZonedDateTime = () => ZonedDateTime.now(ZoneId.of("UTC"))
  @Provides def createRandomNotificationId: () => NotificationId = () => NotificationId(UUID.randomUUID())
  @Provides def createRandomObjectId: () => ObjectId = () => new ObjectId()
}
