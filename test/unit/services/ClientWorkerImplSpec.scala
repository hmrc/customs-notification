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

package unit.services

import java.util.UUID

import akka.actor.{ActorSystem, Cancellable, Scheduler}
import org.mockito.ArgumentMatchers.{any, eq => ameq}
import org.mockito.Mockito
import org.mockito.Mockito.{when, _}
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.domain.{ClientSubscriptionId, CustomsNotificationConfig, PullExcludeConfig}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationRepo, LockOwnerId, LockRepo}
import uk.gov.hmrc.customs.notification.services.{ClientWorkerImpl, PullClientNotificationService, PushClientNotificationService}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.test.UnitSpec
import unit.services.ClientWorkerTestData._
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class ClientWorkerImplSpec extends UnitSpec with MockitoSugar with Eventually {

  private val oneThousand = 1000
  private val lockDuration = org.joda.time.Duration.millis(oneThousand)

  private trait SetUp {
    private[ClientWorkerImplSpec] val mockActorSystem = mock[ActorSystem]
    private[ClientWorkerImplSpec] val mockScheduler = mock[Scheduler]
    private[ClientWorkerImplSpec] val mockCancellable = mock[Cancellable]

    private[ClientWorkerImplSpec] val mockRepo = mock[ClientNotificationRepo]
    private[ClientWorkerImplSpec] val mockDeclarantDetails = mock[ApiSubscriptionFieldsConnector]
    private[ClientWorkerImplSpec] val mockPull = mock[PullClientNotificationService]
    private[ClientWorkerImplSpec] val mockPush = mock[PushClientNotificationService]
    private[ClientWorkerImplSpec] val mockLockRepo = mock[LockRepo]
    private[ClientWorkerImplSpec] val mockLogger = mock[NotificationLogger]
    private[ClientWorkerImplSpec] val mockHttpResponse = mock[HttpResponse]
    private[ClientWorkerImplSpec] val mockPullExcludeConfig = mock[PullExcludeConfig]
    private[ClientWorkerImplSpec] val mockConfig = mock[CustomsNotificationConfig]

    private[ClientWorkerImplSpec] lazy val clientWorker = new ClientWorkerImpl(
      mockActorSystem,
      mockRepo,
      mockDeclarantDetails,
      mockPush,
      mockPull,
      mockLockRepo,
      mockLogger,
      mockConfig
    )

    def schedulerExpectations(): Unit = {
      when(mockActorSystem.scheduler).thenReturn(mockScheduler)
      when(mockScheduler.schedule(any[FiniteDuration], any[FiniteDuration], any[Runnable])(any[ExecutionContext])).thenReturn(mockCancellable)
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
        when(mockRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne, ClientNotificationTwo)), Future.successful(Nil))
        when(mockDeclarantDetails.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier])).thenReturn(Future.successful(Some(DeclarantCallbackDataOne)), Future.successful(Some(DeclarantCallbackDataTwo)))
        when(mockPush.send(DeclarantCallbackDataOne, ClientNotificationOne)).thenReturn(true)
        when(mockPush.send(DeclarantCallbackDataTwo, ClientNotificationTwo)).thenReturn(true)
        when(mockRepo.delete(ameq(ClientNotificationOne))).thenReturn(Future.successful(()))
        when(mockRepo.delete(ameq(ClientNotificationTwo))).thenReturn(Future.successful(()))
        when(mockConfig.pullExcludeConfig).thenReturn(mockPullExcludeConfig)
        when(mockPullExcludeConfig.csIdsToExclude).thenReturn(Seq(CsidThree.toString()))
        val ordered = Mockito.inOrder(mockPush, mockRepo, mockPush, mockRepo, mockLockRepo, mockCancellable)
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))

        private val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration) )

        actual shouldBe (())
        eventually {
          ordered.verify(mockPush).send(ameq(DeclarantCallbackDataOne), ameq(ClientNotificationOne))
          ordered.verify(mockRepo).delete(ClientNotificationOne)
          ordered.verify(mockPush).send(ameq(DeclarantCallbackDataTwo), ameq(ClientNotificationTwo))
          ordered.verify(mockRepo).delete(ClientNotificationTwo)
          ordered.verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          ordered.verify(mockCancellable).cancel()
          verifyLogInfo("[clientSubscriptionId=eaca01f9-ec3b-4ede-b263-61b626dde231] Push successful")
          verifyLogInfo("[clientSubscriptionId=eaca01f9-ec3b-4ede-b263-61b626dde231] processing notification record number 1, logging every 1 records")
          verifyZeroInteractions(mockPull)
        }
      }

      "push notifications even when there are errors deleting the notification after a successful push" in new SetUp {
        schedulerExpectations()
        when(mockRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne)), Future.successful(Nil))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))
        when(mockDeclarantDetails.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier])).thenReturn(Future.successful(Some(DeclarantCallbackDataOne)))
        when(mockPush.send(DeclarantCallbackDataOne, ClientNotificationOne)).thenReturn(true)
        when(mockRepo.delete(ameq(ClientNotificationOne))).thenReturn(Future.failed((emulatedServiceFailure)))
        when(mockConfig.pullExcludeConfig).thenReturn(mockPullExcludeConfig)
        when(mockPullExcludeConfig.csIdsToExclude).thenReturn(Seq(CsidThree.toString()))

        private val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration) )

        actual shouldBe (())
        eventually {
          verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          verifyLogInfo("[clientSubscriptionId=eaca01f9-ec3b-4ede-b263-61b626dde231] Push successful")
          verify(mockCancellable).cancel()
          verifyZeroInteractions(mockPull)
        }
      }

      "exit push processing when fetch returns empty list" in new SetUp {
        schedulerExpectations()
        when(mockRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List()))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.failed(emulatedServiceFailure))
        when(mockPush.send(DeclarantCallbackDataOne, ClientNotificationOne)).thenReturn(true)
        when(mockConfig.pullExcludeConfig).thenReturn(mockPullExcludeConfig)
        when(mockPullExcludeConfig.csIdsToExclude).thenReturn(Seq(CsidThree.toString()))

        private val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration) )

        actual shouldBe (())
        eventually {
          verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          verify(mockCancellable).cancel()
          verifyZeroInteractions(mockPull)
        }
      }

      "exit push processing when fetch returns same size of list twice in a row, when declarant details not found and pull queue fails" in new SetUp {
        schedulerExpectations()
        when(mockRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne)))
        when(mockDeclarantDetails.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier])).thenReturn(Future.successful(Some(DeclarantCallbackDataOne)), Future.successful(None))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))
        when(mockPush.send(DeclarantCallbackDataOne, ClientNotificationOne)).thenReturn(false)
        when(mockPull.send(ameq(ClientNotificationOne))).thenReturn(false)
        when(mockConfig.pullExcludeConfig).thenReturn(mockPullExcludeConfig)
        when(mockPullExcludeConfig.csIdsToExclude).thenReturn(Seq(CsidTwo.toString()))

        private val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration) )

        actual shouldBe (())
        eventually {
          verify(mockPull).send(ameq(ClientNotificationOne))
          verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          verify(mockCancellable).cancel()
        }
      }

      "enqueue all two notification to pull queue when declarant details are not found for first" in new SetUp {
        schedulerExpectations()
        when(mockRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne, ClientNotificationTwo)), Future.successful(List(ClientNotificationOne, ClientNotificationTwo)), Future.successful(Nil))
        when(mockDeclarantDetails.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier])).thenReturn(Future.successful(None))
        when(mockPull.send(ameq(ClientNotificationOne))).thenReturn(true)
        when(mockPull.send(ameq(ClientNotificationTwo))).thenReturn(true)
        when(mockRepo.delete(ameq(ClientNotificationOne))).thenReturn(Future.successful(()))
        when(mockRepo.delete(ameq(ClientNotificationTwo))).thenReturn(Future.successful(()))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))
        when(mockConfig.pullExcludeConfig).thenReturn(mockPullExcludeConfig)
        when(mockPullExcludeConfig.csIdsToExclude).thenReturn(Seq(CsidThree.toString()))
        val ordered = Mockito.inOrder(mockRepo, mockPull, mockRepo, mockPull, mockRepo, mockLockRepo, mockCancellable)

        private val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration) )

        actual shouldBe (())
        eventually {
          verifyZeroInteractions(mockPush)
          ordered.verify(mockPull).send(ameq(ClientNotificationOne))
          ordered.verify(mockRepo).delete(ClientNotificationOne)
          ordered.verify(mockPull).send(ameq(ClientNotificationTwo))
          ordered.verify(mockRepo).delete(ClientNotificationTwo)
          ordered.verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          ordered.verify(mockCancellable).cancel()
        }
      }

      "enqueue second of two notification to pull queue when declarant details are not found for second" in new SetUp {
        schedulerExpectations()
        when(mockRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne, ClientNotificationTwo)), Future.successful(List(ClientNotificationTwo)), Future.successful(Nil))
        when(mockDeclarantDetails.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier])).thenReturn(Future.successful(Some(DeclarantCallbackDataOne)), Future.successful(None))
        when(mockPush.send(DeclarantCallbackDataOne, ClientNotificationOne)).thenReturn(true)
        when(mockPull.send(ameq(ClientNotificationTwo))).thenReturn(true)
        when(mockRepo.delete(ameq(ClientNotificationOne))).thenReturn(Future.successful(()))
        when(mockRepo.delete(ameq(ClientNotificationTwo))).thenReturn(Future.successful(()))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))
        when(mockConfig.pullExcludeConfig).thenReturn(mockPullExcludeConfig)
        when(mockPullExcludeConfig.csIdsToExclude).thenReturn(Seq(CsidThree.toString()))
        val ordered = Mockito.inOrder(mockPush, mockRepo, mockPull, mockRepo, mockLockRepo, mockCancellable)

        private val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration) )

        actual shouldBe (())
        eventually {
          ordered.verify(mockPush).send(ameq(DeclarantCallbackDataOne), ameq(ClientNotificationOne))
          ordered.verify(mockRepo).delete(ClientNotificationOne)
          ordered.verify(mockPull).send(ameq(ClientNotificationTwo))
          ordered.verify(mockRepo).delete(ClientNotificationTwo)
          ordered.verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          ordered.verify(mockCancellable).cancel()
        }
      }

      "enqueue notification to pull queue when declarant callback URL is empty" in new SetUp {
        schedulerExpectations()
        when(mockRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne)), Future.successful(List(ClientNotificationOne)), Future.successful(Nil))
        when(mockDeclarantDetails.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier])).thenReturn(Future.successful(Some(DeclarantCallbackDataOneWithEmptyUrl)))
        when(mockPull.send(ameq(ClientNotificationOne))).thenReturn(true)
        when(mockRepo.delete(ameq(ClientNotificationOne))).thenReturn(Future.successful(()))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))
        when(mockConfig.pullExcludeConfig).thenReturn(mockPullExcludeConfig)
        when(mockPullExcludeConfig.csIdsToExclude).thenReturn(Seq(CsidThree.toString()))

        val ordered = Mockito.inOrder(mockRepo, mockPull, mockLockRepo, mockCancellable)

        private val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration) )

        actual shouldBe (())
        eventually {
          verifyZeroInteractions(mockPush)
          ordered.verify(mockPull).send(ameq(ClientNotificationOne))
          ordered.verify(mockRepo).delete(ClientNotificationOne)
          ordered.verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          ordered.verify(mockCancellable).cancel()
        }
      }
    }

    "for unhappy path" should {
      "exit out processing loop when there is a fatal exception, then release lock and cancel timer" in new SetUp {
        schedulerExpectations()
        when(mockRepo.fetch(CsidOne))
          .thenReturn(Future.failed(new VirtualMachineError{}))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))
        when(mockConfig.pullExcludeConfig).thenReturn(mockPullExcludeConfig)
        when(mockPullExcludeConfig.csIdsToExclude).thenReturn(Seq(CsidThree.toString()))

        private val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration) )

        actual shouldBe (())
        eventually {
          verify(mockRepo).fetch(CsidOne)
          verifyZeroInteractions(mockDeclarantDetails)
          verifyZeroInteractions(mockPush)
          verifyZeroInteractions(mockPull)
          verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          verify(mockCancellable).cancel()
        }
      }

      "exit push processing and release lock when fetch of notifications throws an exception" in new SetUp {
        schedulerExpectations()
        when(mockRepo.fetch(CsidOne))
          .thenReturn(Future.failed(emulatedServiceFailure))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))
        when(mockConfig.pullExcludeConfig).thenReturn(mockPullExcludeConfig)
        when(mockPullExcludeConfig.csIdsToExclude).thenReturn(Seq(CsidThree.toString()))

        private val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration) )

        actual shouldBe (())
        eventually {
          verifyLogError("[clientSubscriptionId=eaca01f9-ec3b-4ede-b263-61b626dde231] error fetching notifications: Emulated service failure.")
          verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          verifyZeroInteractions(mockPush)
          verify(mockCancellable).cancel()
        }
      }

      "exit push processing and release lock when fetch of declarant details throws an exception" in new SetUp {
        schedulerExpectations()
        when(mockRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne)))
        when(mockDeclarantDetails.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier]))
          .thenReturn(Future.failed(emulatedServiceFailure))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))
        when(mockConfig.pullExcludeConfig).thenReturn(mockPullExcludeConfig)
        when(mockPullExcludeConfig.csIdsToExclude).thenReturn(Seq(CsidThree.toString()))

        private val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration) )

        actual shouldBe (())
        eventually {
          verifyLogError("Fatal error - exiting processing: Error getting declarant details: Emulated service failure.")
          verifyZeroInteractions(mockPush)
          verifyZeroInteractions(mockPull)
          verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          verify(mockCancellable).cancel()
        }
      }

      "log release lock error when release lock error fails on exit of push processing when fetch throws a fatal exception" in new SetUp {
        schedulerExpectations()
        when(mockRepo.fetch(CsidOne))
          .thenReturn(Future.failed(new VirtualMachineError{}))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.failed(emulatedServiceFailure))
        when(mockConfig.pullExcludeConfig).thenReturn(mockPullExcludeConfig)
        when(mockPullExcludeConfig.csIdsToExclude).thenReturn(Seq(CsidThree.toString()))

        private val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration) )

        actual shouldBe (())
        eventually {
          verify(mockRepo).fetch(CsidOne)
          verifyZeroInteractions(mockDeclarantDetails)
          verifyZeroInteractions(mockPush)
          verifyZeroInteractions(mockPull)
          verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          verify(mockCancellable).cancel()
          verifyLogError("[clientSubscriptionId=eaca01f9-ec3b-4ede-b263-61b626dde231][lockOwnerId=eaca01f9-ec3b-4ede-b263-61b626dde231] error releasing lock")
        }
      }

      "exit push processing loop early if push returns false" in new SetUp {
        schedulerExpectations()
        when(mockRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne)), Future.successful(Nil))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))
        when(mockDeclarantDetails.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier])).thenReturn(Future.successful(Some(DeclarantCallbackDataOne)))
        when(mockPush.send(DeclarantCallbackDataOne, ClientNotificationOne)).thenReturn(false)
        when(mockConfig.pullExcludeConfig).thenReturn(mockPullExcludeConfig)
        when(mockPullExcludeConfig.csIdsToExclude).thenReturn(Seq(CsidThree.toString()))

        private val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration) )

        actual shouldBe (())
        eventually {
          verifyLogInfo("[clientSubscriptionId=eaca01f9-ec3b-4ede-b263-61b626dde231] About to enqueue notifications to pull queue")
          verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          verify(mockCancellable).cancel()
        }
      }

      "exit pull processing loop early if pull returns false" in new SetUp {
        schedulerExpectations()
        when(mockRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne)), Future.successful(List(ClientNotificationOne)), Future.successful(Nil))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))
        when(mockDeclarantDetails.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier])).thenReturn(Future.successful(Some(DeclarantCallbackDataOne)))
        when(mockPush.send(DeclarantCallbackDataOne, ClientNotificationOne)).thenReturn(false)
        when(mockPull.send(ameq(ClientNotificationOne))).thenReturn(false)
        when(mockConfig.pullExcludeConfig).thenReturn(mockPullExcludeConfig)
        when(mockPullExcludeConfig.csIdsToExclude).thenReturn(Seq(CsidThree.toString()))

        private val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration) )

        actual shouldBe (())
        eventually {
          verifyLogError("[clientSubscriptionId=eaca01f9-ec3b-4ede-b263-61b626dde231] error enqueuing notifications to pull queue: pull queue unavailable")
          verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          verify(mockCancellable).cancel()
        }
      }

      "do not send to pull queue when push fails for excluded csids" in new SetUp {
        schedulerExpectations()
        when(mockRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne)), Future.successful(Nil))
        when(mockLockRepo.release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))).thenReturn(Future.successful(()))
        when(mockDeclarantDetails.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier])).thenReturn(Future.successful(Some(DeclarantCallbackDataOne)))
        when(mockPush.send(DeclarantCallbackDataOne, ClientNotificationOne)).thenReturn(false)
        when(mockConfig.pullExcludeConfig).thenReturn(mockPullExcludeConfig)
        when(mockPullExcludeConfig.csIdsToExclude).thenReturn(Seq(CsidOne.toString()))
        when(mockPullExcludeConfig.pullExcludeEnabled).thenReturn(true)

        private val actual = await( clientWorker.processNotificationsFor(CsidOne, CsidOneLockOwnerId, lockDuration) )

        actual shouldBe (())
        eventually {
          verifyLogInfo("[clientSubscriptionId=eaca01f9-ec3b-4ede-b263-61b626dde231] failed push and was not sent to pull queue")
          verify(mockLockRepo).release(eqClientSubscriptionId(CsidOne), eqLockOwnerId(CsidOneLockOwnerId))
          verify(mockCancellable).cancel()
          verify(mockPull, never()).send(ClientNotificationOne)
        }
      }

    }
  }

}
