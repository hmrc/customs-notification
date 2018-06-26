package unit.repo

import java.util.UUID
import java.util.concurrent.TimeUnit

import org.scalatest.mockito.MockitoSugar
import reactivemongo.api.{DB, DefaultDB}
import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId
import uk.gov.hmrc.customs.notification.repo.{LockRepo, MongoDbProvider}
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{FailOnUnindexedQueries, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec




class LockRepoSpec extends UnitSpec with MockitoSugar with MongoSpecSupport {

  val lockRepository = new LockRepository

  val lockRepo = new LockRepo(){
    val db = () => mock[DB]
    override val repo: LockRepository = lockRepository
  }

  "LockRepo" should {

    "when requesting a lock for a client subscription Id should return true if lock does not already exist" in {
      await(lockRepo.lock(ClientSubscriptionId(UUID.randomUUID()), org.joda.time.Duration.standardSeconds(5))) shouldBe true
    }

    "when requesting a lock for a client subscription Id should return false if lock already exists" in {

    }

  }




}
