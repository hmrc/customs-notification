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

package unit.services

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.ActorSystem
import org.mockito.ArgumentMatchers.{any, eq => ameq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.domain.{ClientSubscriptionId, CustomsNotificationConfig}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationRepo, LockOwnerId, LockRepo}
import uk.gov.hmrc.customs.notification.services.{ClientWorkerImpl, PullClientNotificationService, PushClientNotificationService}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import unit.services.ClientWorkerTestData._
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._

import scala.concurrent.Future

class ClientWorkerImplTimerSpec extends UnitSpec with MockitoSugar with Eventually with BeforeAndAfterAll {

  private val actorSystem = ActorSystem("TestActorSystem")
  private val oneThousand = 1000
  private val lockDuration = org.joda.time.Duration.millis(oneThousand)
  private val ninetyPercentOfLockDuration = 900
  private val oneAndAHalfSecondsProcessingDelay = 1500
  private val fiveSecondsProcessingDelay = 5000

  private trait SetUp {

    private[ClientWorkerImplTimerSpec] val mockClientNotificationRepo = mock[ClientNotificationRepo]
    private[ClientWorkerImplTimerSpec] val mockApiSubscriptionFieldsConnector = mock[ApiSubscriptionFieldsConnector]
    private[ClientWorkerImplTimerSpec] val mockPullClientNotificationService = mock[PullClientNotificationService]
    private[ClientWorkerImplTimerSpec] val mockPushClientNotificationService = mock[PushClientNotificationService]
    private[ClientWorkerImplTimerSpec] val mockLockRepo = mock[LockRepo]
    private[ClientWorkerImplTimerSpec] val mockLogger = mock[NotificationLogger]
    private[ClientWorkerImplTimerSpec] val mockCustomsNotificationConfig = mock[CustomsNotificationConfig]


    def clientWorkerWithProcessingDelay(delayMilliseconds: Int): ClientWorkerImpl = {
      lazy val clientWorker = new ClientWorkerImpl(
        actorSystem,
        mockClientNotificationRepo,
        mockApiSubscriptionFieldsConnector,
        mockPushClientNotificationService,
        mockPullClientNotificationService,
        mockLockRepo,
        mockLogger
      )
      {
        override protected def process(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId)(implicit hc: HeaderCarrier, refreshLockFailed: AtomicBoolean): Future[Unit] = {
          scala.concurrent.blocking {
            Thread.sleep(delayMilliseconds)
          }
          super.process(csid, lockOwnerId)(hc, refreshLockFailed)
        }
      }
      clientWorker
    }

    private[ClientWorkerImplTimerSpec] implicit val implicitHc = HeaderCarrier()

    def eqLockOwnerId(id: LockOwnerId): LockOwnerId = ameq[String](id.id).asInstanceOf[LockOwnerId]
    def eqClientSubscriptionId(id: ClientSubscriptionId): ClientSubscriptionId = ameq[UUID](id.id).asInstanceOf[ClientSubscriptionId]

    def verifyLogError(msg: String): Unit = {
      PassByNameVerifier(mockLogger, "error")
        .withByNameParam(msg)
        .withParamMatcher(any[HeaderCarrier])
        .verify()
    }

  }

  override protected def afterAll(): Unit = {
    actorSystem.terminate()
    super.afterAll()
  }

  "ClientWorker" can {
    "In happy path" should {
      "refresh timer when elapsed time > time delay duration" in new SetUp {

        when(mockClientNotificationRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne)), Future.successful(Nil))
        when(mockLockRepo.tryToAcquireOrRenewLock(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId) , any[org.joda.time.Duration])).thenReturn(Future.successful(true))
        when(mockApiSubscriptionFieldsConnector.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier])).thenReturn(Future.successful(Some(DeclarantCallbackDataOne)))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))
        when(mockPushClientNotificationService.send(DeclarantCallbackDataOne, ClientNotificationOne)).thenReturn(true)
        when(mockClientNotificationRepo.delete(ameq(ClientNotificationOne)))
          .thenReturn(Future.successful(()))

        private val actual = await(clientWorkerWithProcessingDelay(fiveSecondsProcessingDelay).processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration))

        actual shouldBe (())
        eventually{
          verify(mockPushClientNotificationService).send(ameq(DeclarantCallbackDataOne), ameq(ClientNotificationOne))
          verify(mockClientNotificationRepo).delete(ameq(ClientNotificationOne))
          val expectedLockRefreshCount: Int = fiveSecondsProcessingDelay / ninetyPercentOfLockDuration
          verify(mockLockRepo, times(expectedLockRefreshCount)).tryToAcquireOrRenewLock(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId), any[org.joda.time.Duration])
        }
      }

    }

    "In unhappy path" should {
      "exit push inner loop processing and release lock when lock refresh returns false" in new SetUp {
        when(mockClientNotificationRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne)), Future.successful(Nil))
        when(mockLockRepo.tryToAcquireOrRenewLock(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId), any[org.joda.time.Duration])).thenReturn(Future.successful(false))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))

        private val actual = await(clientWorkerWithProcessingDelay(oneAndAHalfSecondsProcessingDelay).processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration))

        actual shouldBe (())
        eventually {
          verifyLogError("[clientSubscriptionId=eaca01f9-ec3b-4ede-b263-61b626dde231] error pushing notifications: quitting pull processing - error refreshing lock")
          verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
        }
      }

      "log an error when refresh lock throws an exception" in new SetUp {
        when(mockClientNotificationRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne)), Future.successful(Nil))
        when(mockLockRepo.tryToAcquireOrRenewLock(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId), any[org.joda.time.Duration])).thenReturn(Future.failed(emulatedServiceFailure))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))

        private val actual = await(clientWorkerWithProcessingDelay(oneAndAHalfSecondsProcessingDelay).processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration))

        actual shouldBe (())
        eventually {
          verifyLogError("[clientSubscriptionId=eaca01f9-ec3b-4ede-b263-61b626dde231] error pushing notifications: quitting pull processing - error refreshing lock")
          verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
        }
      }
    }

  }

}
