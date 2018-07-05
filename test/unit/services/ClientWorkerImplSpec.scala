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

import akka.actor.{ActorSystem, Cancellable, Scheduler}
import org.mockito.ArgumentMatchers.{any, eq => ameq}
import org.mockito.Mockito.{when, _}
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationRepo, LockOwnerId, LockRepo}
import uk.gov.hmrc.customs.notification.services.{ClientWorkerImpl, PullClientNotificationService, PushClientNotificationService}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.test.UnitSpec
import unit.services.ClientWorkerTestData.{ClientNotificationOne, ClientNotificationTwo, CsidOne, CsidOneLockOwnerId}
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

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

    def schedulerExpectations(): Unit = {
      when(mockActorSystem.scheduler).thenReturn(mockScheduler)
      when(mockScheduler.schedule(any[FiniteDuration], any[FiniteDuration], any[Runnable])(any[ExecutionContext])).thenReturn(mockCancelable)
    }

    def verifyLogError(msg: String): Unit = {
      PassByNameVerifier(mockLogger, "error")
        .withByNameParam(msg)
        .withParamMatcher(any[HeaderCarrier])
        .verify()
    }

    def verifyLogInfo(msg: String): Unit = {
      PassByNameVerifier(mockLogger, "info")
        .withByNameParam(msg)
        .withParamMatcher(any[HeaderCarrier])
        .verify()
    }

    def eqLockOwnerId(id: LockOwnerId): LockOwnerId = ameq[String](id.id).asInstanceOf[LockOwnerId]
    def eqClientSubscriptionId(id: ClientSubscriptionId): ClientSubscriptionId = ameq[UUID](id.id).asInstanceOf[ClientSubscriptionId]
  }

  "ClientWorkerImpl" can {
    "for happy path" should {
      "push notifications when there are no errors" in new SetUp {
        schedulerExpectations()
        when(mockClientNotificationRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne, ClientNotificationTwo)), Future.successful(List()))

        val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId) )

        actual shouldBe (())
        eventually {
          verify(mockLockRepo, never()).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          verify(mockCancelable).cancel()
        }
      }
      "exit push processing when fetch returns empty list" in new SetUp {
        schedulerExpectations()
        when(mockClientNotificationRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List()), Future.successful(List()))

        val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId) )

        actual shouldBe (())
        eventually {
          verify(mockLockRepo, never()).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          verify(mockCancelable).cancel()
        }
      }
    }
    "for unhappy path" should {
      "exit push processing and release lock when fetch throws an exception" in new SetUp {
        schedulerExpectations()
        when(mockClientNotificationRepo.fetch(CsidOne))
          .thenReturn(Future.failed(emulatedServiceFailure))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))

        val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId) )

        actual shouldBe (())
        eventually {
          verifyLogError("error pushing notifications")
          verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          verify(mockCancelable).cancel()
        }
      }

      "log release lock error when release lock error fails on exit of push processing when fetch throws an exception" in new SetUp {
        schedulerExpectations()
        when(mockClientNotificationRepo.fetch(CsidOne))
          .thenReturn(Future.failed(emulatedServiceFailure))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.failed(emulatedServiceFailure))

        val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId) )

        actual shouldBe (())
        eventually {
          verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          verifyLogError("error pushing notifications")
          verifyLogError("error releasing lock")
          verify(mockCancelable).cancel()
        }
      }
    }
  }

}
