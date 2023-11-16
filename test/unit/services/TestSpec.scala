package unit.services

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.{AnyWordSpec, AsyncWordSpec}
import play.api.libs.json.{Format, Json}
import unit.services.Foo
import unit.services.Foo._
class TestSpec extends AnyWordSpec
  with Matchers{

"Test" when {
  "json" should {
    "do it" in {
      val x = new Foo("Hi", "ignored")

      info(Foo.testFormat.writes(x).toString())
    }
  }
}
}
class Foo(val s: String,
          x: String) {
}
object Foo {

  def apply(s: String, x: String) = new Foo(s, x)

  def unapply(f: Foo) = Some(f.s, "Bosk")

  val testFormat: Format[Foo] = Json.format[Foo]
}
