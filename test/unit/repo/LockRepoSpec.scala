package unit.repo

import java.util.UUID
import java.util.concurrent.TimeUnit

import org.scalatest.mockito.MockitoSugar
import reactivemongo.api.DB
import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId
import uk.gov.hmrc.customs.notification.repo.LockRepo
import uk.gov.hmrc.play.test.UnitSpec




class LockRepoSpec extends UnitSpec with MockitoSugar {

  val lockRepo = new LockRepo(){
    val db = () => mock[DB]
  }

  "LockRepo" should {

    "when requesting a lock for a client subscription Id should return true if lock does not already exist" in {
      await(lockRepo.lock(ClientSubscriptionId(UUID.randomUUID()), org.joda.time.Duration.standardSeconds(5))) shouldBe true
    }

    "when requesting a lock for a client subscription Id should return false if lock already exists" in {

    }

  }
}
