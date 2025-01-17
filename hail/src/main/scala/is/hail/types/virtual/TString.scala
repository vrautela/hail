package is.hail.types.virtual

import is.hail.annotations._
import is.hail.check.Arbitrary._
import is.hail.check.Gen
import is.hail.types.physical.PString
import is.hail.utils._

import scala.reflect.{ClassTag, _}

case object TString extends Type {
  def _toPretty = "String"

  override def pyString(sb: StringBuilder): Unit = {
    sb.append("str")
  }

  override def _showStr(a: Annotation): String = "\"" + a.asInstanceOf[String] + "\""

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[String]

  override def genNonmissingValue: Gen[Annotation] = arbitrary[String]

  override def scalaClassTag: ClassTag[String] = classTag[String]

  override val ordering: ExtendedOrdering = mkOrdering()

  override def mkOrdering(missingEqual: Boolean): ExtendedOrdering =
    ExtendedOrdering.extendToNull(implicitly[Ordering[String]], missingEqual)
}
