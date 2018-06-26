package unit.repo

import java.util.UUID

import org.mockito.ArgumentMatchers.{eq => meq, _}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import reactivemongo.api.DB
import uk.gov.hmrc.customs.notification.domain.ClientSubscriptionId
import uk.gov.hmrc.customs.notification.repo.LockRepo
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.Future


class LockRepoSpec extends UnitSpec with MockitoSugar {

  val lockRepository = mock[LockRepository]

  val lockRepo = new LockRepo() {
    val db = () => mock[DB]
    override val repo: LockRepository = lockRepository
  }

  private val timeoutInSeconds = 5
  private val duration = org.joda.time.Duration.standardSeconds(timeoutInSeconds)

  "LockRepo" should {

    "when requesting a lock for a client subscription Id should return true if lock does not already exist" in {
      val csid = ClientSubscriptionId(UUID.randomUUID())
      when(lockRepository.renew(meq(csid.id.toString), any[String], meq(duration))).thenReturn(Future.successful(true))
      when(lockRepository.lock(meq(csid.id.toString), any[String], meq(duration))).thenReturn(Future.successful(false))
      await(lockRepo.lock(csid, duration)) shouldBe true
    }

    "when requesting a lock for a client subscription Id should return false if lock already exists" in {
      val csid = ClientSubscriptionId(UUID.randomUUID())
      when(lockRepository.renew(meq(csid.id.toString), any[String], meq(duration))).thenReturn(Future.successful(false))
      when(lockRepository.lock(meq(csid.id.toString), any[String], meq(duration))).thenReturn(Future.successful(false))
      await(lockRepo.lock(csid, duration)) shouldBe false
    }

  }


}
