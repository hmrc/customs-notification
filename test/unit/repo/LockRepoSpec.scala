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

package unit.repo

import java.util.UUID

import org.mockito.ArgumentMatchers.{eq => meq, _}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import reactivemongo.api.DB
import reactivemongo.core.errors.GenericDriverException
import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId
import uk.gov.hmrc.customs.notification.repo.{LockRepo, LockOwnerId}
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.Future

class LockRepoSpec extends UnitSpec with MockitoSugar {

  val lockRepository: LockRepository = mock[LockRepository]

  val lockRepo: LockRepo = new LockRepo() {
    val db: () => DB = () => mock[DB]
    override val repo: LockRepository = lockRepository
  }

  private val timeoutInSeconds = 5
  private val duration = org.joda.time.Duration.standardSeconds(timeoutInSeconds)

  "LockRepo" should {

    "when requesting a lock for a client subscription Id should return true if lock does not already exist" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val lockOwnerId = new LockOwnerId("worker1")

      when(lockRepository.renew(meq(csId.id.toString), meq(lockOwnerId.id), meq(duration))).thenReturn(Future.successful(true))
      when(lockRepository.lock(meq(csId.id.toString), meq(lockOwnerId.id), meq(duration))).thenReturn(Future.successful(false))
      
      await(lockRepo.lock(csId,lockOwnerId, duration)) shouldBe true
    }

    "when requesting a lock for a client subscription Id should return false if lock already exists" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val lockOwnerId = new LockOwnerId("worker1")

      when(lockRepository.renew(meq(csId.id.toString), meq(lockOwnerId.id), meq(duration))).thenReturn(Future.successful(false))
      when(lockRepository.lock(meq(csId.id.toString), meq(lockOwnerId.id), meq(duration))).thenReturn(Future.successful(false))
      
      await(lockRepo.lock(csId,lockOwnerId, duration)) shouldBe false
    }

    "when requesting a lock and repository returns failed future propagate the failed future" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val lockOwnerId = new LockOwnerId("worker1")

      when(lockRepository.renew(meq(csId.id.toString), meq(lockOwnerId.id), meq(duration))).thenReturn(Future.failed(GenericDriverException(s"fails to remove:lock")))
      when(lockRepository.lock(meq(csId.id.toString), meq(lockOwnerId.id), meq(duration))).thenReturn(Future.failed(GenericDriverException(s"fails to remove:lock")))
      when(lockRepository.releaseLock(meq(csId.id.toString), meq(lockOwnerId.id))).thenReturn(Future.failed(GenericDriverException(s"fails to remove:lock")))
      
      assertThrows[GenericDriverException] {
        await(lockRepo.lock(csId,lockOwnerId, duration))
      }
    }

    "when requesting to release a lock that exists and is owned by caller, should release successfully" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val lockOwnerId = new LockOwnerId("worker1")

      when(lockRepository.releaseLock(meq(csId.id.toString), meq(lockOwnerId.id))).thenReturn(Future.successful(()))
      
      await(lockRepo.release(csId,lockOwnerId)) shouldBe (():Unit)
    }

    "when requesting to release a lock that doesn't exists , should release successfully" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val lockOwnerId = new LockOwnerId("worker1")

      when(lockRepository.releaseLock(meq(csId.id.toString), meq(lockOwnerId.id))).thenReturn(Future.successful(()))
      
      await(lockRepo.release(csId,lockOwnerId)) shouldBe (():Unit)
    }

    "when requesting to release a lock and repository returns failed future propagate the failed future" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val lockOwnerId = new LockOwnerId("worker1")
      when(lockRepository.releaseLock(meq(csId.id.toString), meq(lockOwnerId.id))).thenReturn(Future.failed(GenericDriverException(s"fails to remove:lock")))
      
      assertThrows[GenericDriverException] {
        await(lockRepo.release(csId,lockOwnerId)) shouldBe (():Unit)
      }
    }

    "when requesting to refresh a lock for a client subscription Id should return true if lock does not already exist" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val lockOwnerId = new LockOwnerId("worker1")
      when(lockRepository.renew(meq(csId.id.toString), meq(lockOwnerId.id), meq(duration))).thenReturn(Future.successful(true))
      when(lockRepository.lock(meq(csId.id.toString), meq(lockOwnerId.id), meq(duration))).thenReturn(Future.successful(false))
      
      await(lockRepo.refreshLock(csId,lockOwnerId, duration)) shouldBe true
    }

    "when asking if a lock exists for a client subscription Id should return true if lock does exists" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
     val lockOwnerId = new LockOwnerId("worker1")
      when(lockRepository.isLocked(meq(csId.id.toString), meq(lockOwnerId.id))).thenReturn(Future.successful(true))
      await(lockRepo.isLocked(csId,lockOwnerId)) shouldBe true
     }

    "when asking if a lock exists for a client subscription Id should return false if lock does not exists" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val lockOwnerId = new LockOwnerId("worker1")
      when(lockRepository.isLocked(meq(csId.id.toString), meq(lockOwnerId.id))).thenReturn(Future.successful(false))
      
      await(lockRepo.isLocked(csId,lockOwnerId)) shouldBe false
    }

  }

}
