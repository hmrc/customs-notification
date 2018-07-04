package unit.services

import akka.actor.{ActorSystem, Cancellable}
import com.google.common.util.concurrent.AbstractScheduledService.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationRepo, LockRepo}
import uk.gov.hmrc.customs.notification.services.{ClientWorkerImpl, PullClientNotificationService, PushClientNotificationService}
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.play.test.UnitSpec
import unit.services.ClientWorkerTestData.CsidOne

class ClientWorkerImplSpec extends UnitSpec with MockitoSugar with Eventually {

  trait SetUp {
    val mockActorSystem = mock[ActorSystem]
    val mockScheduler = mock[Scheduler]
    val mockCancelable = mock[Cancellable]

    val mockClientNotificationRepo = mock[ClientNotificationRepo]
    val mockPullClientNotificationService = mock[PullClientNotificationService]
    val mockPushClientNotificationService = mock[PushClientNotificationService]
    val mockLockRepo = mock[LockRepo]
    val mockLogger = mock[NotificationLogger]
    val mockHttpResponse = mock[HttpResponse]

    lazy val clientWorker = new ClientWorkerImpl(
      mockActorSystem,
      mockClientNotificationRepo,
      mockPullClientNotificationService,
      mockPushClientNotificationService,
      mockLockRepo,
      mockLogger
    )

  }

  "ClientWorkerImpl" can {
    "for happy path" should {
      "push notifications when there are no errors" in new SetUp {

        val actual = await( clientWorker.processNotificationsFor(CsidOne) )

        actual shouldBe (())
      }
    }
  }

}
