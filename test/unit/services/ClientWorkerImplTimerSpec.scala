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
  private val oneAndAHalfSecondsProcessingDelay = 1500
  private val fiveSecondsProcessingDelay = 5000
  private val lockRefreshDurationInMilliseconds = 800

  trait SetUp {

    val mockClientNotificationRepo = mock[ClientNotificationRepo]
    val mockApiSubscriptionFieldsConnector = mock[ApiSubscriptionFieldsConnector]
    val mockPullClientNotificationService = mock[PullClientNotificationService]
    val mockPushClientNotificationService = mock[PushClientNotificationService]
    val mockLockRepo = mock[LockRepo]
    val mockLogger = mock[NotificationLogger]
    val mockCustomsNotificationConfig = mock[CustomsNotificationConfig]


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

    implicit val implicitHc = HeaderCarrier()

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

        when(mockLockRepo.tryToAcquireOrRenewLock(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId) , any[org.joda.time.Duration])).thenReturn(Future.successful(true))
        when(mockClientNotificationRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne)))
        when(mockApiSubscriptionFieldsConnector.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier])).thenReturn(Future.successful(Some(DeclarantCallbackDataOne)))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))
        when(mockPushClientNotificationService.send(DeclarantCallbackDataOne, ClientNotificationOne)).thenReturn(true)
        when(mockClientNotificationRepo.delete(ameq(ClientNotificationOne)))
          .thenReturn(Future.successful(()))

        val actual = await(clientWorkerWithProcessingDelay(fiveSecondsProcessingDelay).processNotificationsFor(CsidOne, CsidOneLockOwnerId))

        actual shouldBe (())
        eventually{
          verify(mockPushClientNotificationService).send(ameq(DeclarantCallbackDataOne), ameq(ClientNotificationOne))
          verify(mockClientNotificationRepo).delete(ameq(ClientNotificationOne))
          val expectedLockRefreshCount = fiveSecondsProcessingDelay / lockRefreshDurationInMilliseconds
          verify(mockLockRepo, times(expectedLockRefreshCount)).tryToAcquireOrRenewLock(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId), any[org.joda.time.Duration])
        }
      }

    }

    "In unhappy path" should {
      "exit push inner loop processing and release lock when lock refresh failure detected" in new SetUp {

        when(mockClientNotificationRepo.fetch(CsidOne))
          .thenReturn(Future.failed(emulatedServiceFailure))
        when(mockLockRepo.tryToAcquireOrRenewLock(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId), any[org.joda.time.Duration])).thenReturn(Future.successful(false))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))

        val actual = await(clientWorkerWithProcessingDelay(oneAndAHalfSecondsProcessingDelay).processNotificationsFor(CsidOne, CsidOneLockOwnerId))

        actual shouldBe (())
        eventually {
          verifyLogError("error pushing notifications")
          verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
        }
      }
    }

  }

}
