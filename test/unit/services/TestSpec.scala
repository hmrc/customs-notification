package unit.services

import org.scalatest.AppendedClues._
import org.mockito.scalatest.MockitoSugar
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.{AnyWordSpec, AsyncWordSpec}
import play.api.libs.json.{Format, Json}

class TestSpec extends AnyWordSpec
  with MockitoSugar
  with Inside
  with Matchers {

  "Test" when {
    "x" should {
      "y" in {
        val y: Option[Int] = None
        y shouldBe defined withClue "i.e. that the request body was not valid JSON"
//        None shouldBe defined withClue "This wasn't defined?!?!?!"
      }
    }
  }
}



