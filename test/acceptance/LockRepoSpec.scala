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

package acceptance

import java.util.UUID

import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import reactivemongo.api.DB
import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId
import uk.gov.hmrc.customs.notification.repo.{LockRepo, LockOwnerId}
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class LockRepoSpec extends UnitSpec with MockitoSugar with MongoSpecSupport with BeforeAndAfterAll {

  val lockRepository = new LockRepository

  val lockRepo: LockRepo = new LockRepo() {
    val db: () => DB = () => mock[DB]
    override val repo: LockRepository = lockRepository
  }

  override def beforeAll(): Unit = {
    lockRepository.drop
  }

  override def afterAll(): Unit = {
    lockRepository.drop
  }

  private val five = 5
  private val twentyFive = 25
  private val fiveSecondsDuration = org.joda.time.Duration.standardSeconds(five)
  private val twentyFiveSecondsDuration = org.joda.time.Duration.standardSeconds(twentyFive)

  "LockRepo" should {

    "when requesting a lock for a client subscription Id should return true if lock does not already exist" in {
      val csId = UUID.randomUUID()
      val ownerId = new LockOwnerId("caller1")

      await(lockRepo.lock(ClientSubscriptionId(csId), ownerId, fiveSecondsDuration)) shouldBe true
    }

    "when requesting a lock for a client subscription Id should return true even if lock already exists" in {
      val csId = UUID.randomUUID()
      val ownerId = new LockOwnerId("caller1")

      await(lockRepo.lock(ClientSubscriptionId(csId), ownerId, twentyFiveSecondsDuration)) shouldBe true
      await(lockRepo.lock(ClientSubscriptionId(csId), ownerId, twentyFiveSecondsDuration)) shouldBe true
    }

    "when requesting a lock should return false if we do not own the lock" in {
      val csId = UUID.randomUUID()
      val ownerId1 = new LockOwnerId("caller1")
      val ownerId2 = new LockOwnerId("caller2")

      await(lockRepo.lock(ClientSubscriptionId(csId), ownerId1, twentyFiveSecondsDuration)) shouldBe true
      await(lockRepo.lock(ClientSubscriptionId(csId), ownerId1, twentyFiveSecondsDuration)) shouldBe true
      await(lockRepo.lock(ClientSubscriptionId(csId), ownerId2, twentyFiveSecondsDuration)) shouldBe false
    }

    "when requesting to release a lock that exists and is owned by caller, should release successfully" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val ownerId1 = new LockOwnerId("worker1")
      val ownerId2 = new LockOwnerId("worker2")

      await(lockRepo.lock(csId, ownerId1, twentyFiveSecondsDuration)) shouldBe true
      await(lockRepo.release(csId, ownerId1)) should be ((): Unit)
      await(lockRepo.lock(csId, ownerId2, twentyFiveSecondsDuration)) shouldBe true
    }

    "when requesting to release a lock that exists and is NOT owned by caller, should fail to release lock" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val ownerId1 = new LockOwnerId("worker1")
      val ownerId2 = new LockOwnerId("worker2")

      await(lockRepo.lock(csId, ownerId1, twentyFiveSecondsDuration)) shouldBe true
      await(lockRepo.release(csId, ownerId2)) should be ((): Unit)
      await(lockRepo.lock(csId, ownerId2, twentyFiveSecondsDuration)) shouldBe false
    }

    "when requesting to refresh a lock that exists and is owned by caller, should refresh successfully" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val ownerId1 = new LockOwnerId("worker1")

      await(lockRepo.lock(csId, ownerId1, twentyFiveSecondsDuration)) shouldBe true
      await(lockRepo.refreshLock(csId, ownerId1, twentyFiveSecondsDuration)) shouldBe true
    }

    "when requesting to refresh a lock that exists and is NOT owned by caller, should fail to refresh lock" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val ownerId1 = new LockOwnerId("worker1")
      val ownerId2 = new LockOwnerId("worker2")

      await(lockRepo.lock(csId, ownerId1, twentyFiveSecondsDuration)) shouldBe true
      await(lockRepo.refreshLock(csId, ownerId2, twentyFiveSecondsDuration)) shouldBe false
    }

    "when requesting if a lock exists should return true if lock exists" in {
      val csId = ClientSubscriptionId(UUID.randomUUID())
      val ownerId1 = new LockOwnerId("worker1")

      await(lockRepo.lock(csId, ownerId1, twentyFiveSecondsDuration)) shouldBe true
      await(lockRepo.isLocked(csId, ownerId1)) shouldBe true
      await(lockRepo.release(csId, ownerId1)) shouldBe ((): Unit)
      await(lockRepo.isLocked(csId, ownerId1)) shouldBe false

    }

    "when requesting if a lock exists should return false if lock does not exist" in {
      val ownerId1 = new LockOwnerId("worker1")

      await(lockRepo.isLocked(ClientSubscriptionId(UUID.randomUUID()), ownerId1)) shouldBe false
    }

  }

}
