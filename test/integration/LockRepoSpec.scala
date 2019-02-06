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

package integration

import java.util.UUID

import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import reactivemongo.api.DB
import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId
import uk.gov.hmrc.customs.notification.repo.{LockOwnerId, LockRepo, MongoDbProvider}
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class LockRepoSpec extends UnitSpec
  with MockitoSugar
  with MongoSpecSupport
  with BeforeAndAfterAll
  with BeforeAndAfterEach { self =>
  val lockRepository = new LockRepository

  private val mongoDbProvider: MongoDbProvider = new MongoDbProvider {
    override val mongo: () => DB = self.mongo
  }

  val lockRepo: LockRepo = new LockRepo(mongoDbProvider)

  override def beforeEach() {
    await(lockRepository.drop)
  }

  override def afterAll() {
    await(lockRepository.drop)
  }
  val ONE = 1
  private val five = 5
  private val twentyFive = 25
  private val fiveThousand = 5000
  private val fiveSecondsDuration = org.joda.time.Duration.standardSeconds(five)
  private val twentyFiveSecondsDuration = org.joda.time.Duration.standardSeconds(twentyFive)
  private val fiveThousandSecondsDuration = org.joda.time.Duration.standardSeconds(fiveThousand)
  private val fiveThousandSecondsinThePastDuration = org.joda.time.Duration.standardSeconds(ONE).minus(fiveThousand)

  "LockRepo" should {

    "when requesting a lock for a client subscription Id should return true if lock does not already exist" in {
      val csId = UUID.randomUUID()
      val ownerId = LockOwnerId("caller1")

      await(lockRepo.tryToAcquireOrRenewLock(ClientSubscriptionId(csId), ownerId, fiveSecondsDuration)) shouldBe true
    }

    "when requesting a lock should return false if we do not own the lock" in {
      val csId = UUID.randomUUID()
      val ownerId1 = LockOwnerId("caller1")
      val ownerId2 = LockOwnerId("caller2")

      await(lockRepo.tryToAcquireOrRenewLock(ClientSubscriptionId(csId), ownerId1, twentyFiveSecondsDuration)) shouldBe true
      await(lockRepo.tryToAcquireOrRenewLock(ClientSubscriptionId(csId), ownerId1, twentyFiveSecondsDuration)) shouldBe true
      await(lockRepo.tryToAcquireOrRenewLock(ClientSubscriptionId(csId), ownerId2, twentyFiveSecondsDuration)) shouldBe false
    }

    "when requesting to release a lock that exists and is owned by caller, should release successfully" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val ownerId1 = LockOwnerId("worker1")
      val ownerId2 = LockOwnerId("worker2")

      await(lockRepo.tryToAcquireOrRenewLock(csId, ownerId1, twentyFiveSecondsDuration)) shouldBe true
      await(lockRepo.release(csId, ownerId1)) should be ((): Unit)
      await(lockRepo.tryToAcquireOrRenewLock(csId, ownerId2, twentyFiveSecondsDuration)) shouldBe true
    }

    "when requesting to release a lock that exists and is NOT owned by caller, should fail to release lock" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val ownerId1 = LockOwnerId("worker1")
      val ownerId2 = LockOwnerId("worker2")

      await(lockRepo.tryToAcquireOrRenewLock(csId, ownerId1, twentyFiveSecondsDuration)) shouldBe true
      await(lockRepo.release(csId, ownerId2)) should be ((): Unit)
      await(lockRepo.tryToAcquireOrRenewLock(csId, ownerId2, twentyFiveSecondsDuration)) shouldBe false
    }

    "when requesting to refresh a lock that exists and is owned by caller, should refresh successfully" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val ownerId1 = LockOwnerId("worker1")

      await(lockRepo.tryToAcquireOrRenewLock(csId, ownerId1, twentyFiveSecondsDuration)) shouldBe true
      await(lockRepo.tryToAcquireOrRenewLock(csId, ownerId1, twentyFiveSecondsDuration)) shouldBe true

    }

    "when requesting to refresh a lock that exists and is NOT owned by caller, should fail to refresh lock" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val ownerId1 = LockOwnerId("worker1")
      val ownerId2 = LockOwnerId("worker2")

      await(lockRepo.tryToAcquireOrRenewLock(csId, ownerId1, twentyFiveSecondsDuration)) shouldBe true
      await(lockRepo.tryToAcquireOrRenewLock(csId, ownerId2, twentyFiveSecondsDuration)) shouldBe false
    }

    "when requesting if a lock exists should return true if lock exists" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val ownerId1 = LockOwnerId("worker1")

      await(lockRepo.tryToAcquireOrRenewLock(csId, ownerId1, twentyFiveSecondsDuration)) shouldBe true
      await(lockRepo.isLocked(csId)) shouldBe true
      await(lockRepo.release(csId, ownerId1)) shouldBe ((): Unit)
      await(lockRepo.isLocked(csId)) shouldBe false

    }

    "when requesting if a lock exists should return false if lock does not exist" in {
      await(lockRepo.isLocked(ClientSubscriptionId(UUID.randomUUID()))) shouldBe false
    }

    "when requesting to get all lock should return lock that are not expired" in {

      val csId = UUID.randomUUID()
      val csId2 = UUID.randomUUID()
      val csId3 = UUID.randomUUID()
      val csId4 = UUID.randomUUID()
      val csId5 = UUID.randomUUID()
      val csId6 = UUID.randomUUID()
      val ownerId = LockOwnerId("caller1")
      val ownerId2 = LockOwnerId("caller2")

      await(lockRepo.tryToAcquireOrRenewLock(ClientSubscriptionId(csId), ownerId, fiveThousandSecondsDuration)) shouldBe true
      await(lockRepo.tryToAcquireOrRenewLock(ClientSubscriptionId(csId2), ownerId, fiveThousandSecondsDuration)) shouldBe true
      await(lockRepo.tryToAcquireOrRenewLock(ClientSubscriptionId(csId3), ownerId, fiveThousandSecondsDuration)) shouldBe true
      await(lockRepo.tryToAcquireOrRenewLock(ClientSubscriptionId(csId4), ownerId2, fiveThousandSecondsinThePastDuration)) shouldBe true
      await(lockRepo.tryToAcquireOrRenewLock(ClientSubscriptionId(csId5), ownerId2, fiveThousandSecondsDuration)) shouldBe true
      await(lockRepo.tryToAcquireOrRenewLock(ClientSubscriptionId(csId6), ownerId2, fiveThousandSecondsDuration)) shouldBe true

      val locks = await(lockRepo.lockedCSIds())
      locks.size shouldBe 5

    }


    "when requesting to get all lock should return nothing if all locks are expired" in {

      val csId = UUID.randomUUID()
      val csId2 = UUID.randomUUID()
      val csId3 = UUID.randomUUID()
      val ownerId = LockOwnerId("caller1")

      await(lockRepo.tryToAcquireOrRenewLock(ClientSubscriptionId(csId), ownerId, fiveThousandSecondsinThePastDuration)) shouldBe true
      await(lockRepo.tryToAcquireOrRenewLock(ClientSubscriptionId(csId2), ownerId, fiveThousandSecondsinThePastDuration)) shouldBe true
      await(lockRepo.tryToAcquireOrRenewLock(ClientSubscriptionId(csId3), ownerId, fiveThousandSecondsinThePastDuration)) shouldBe true

      await(lockRepo.lockedCSIds()).size shouldBe 0
    }

  }

}
