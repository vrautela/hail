package is.hail.expr.ir

import is.hail.asm4s.Code
import is.hail.expr.ir.orderings.{CodeOrdering, StructOrdering}
import is.hail.expr.ir.orderings.CodeOrdering.F
import is.hail.types.physical.PType
import is.hail.types.physical.stypes.SType
import is.hail.types.physical.stypes.interfaces.SBaseStruct
import is.hail.types.virtual.{TStruct, Type}

object ComparisonOp {

  def checkCompatible[T](lt: Type, rt: Type): Unit =
    if (lt != rt)
      throw new RuntimeException(s"Cannot compare types $lt and $rt")

  val fromStringAndTypes: PartialFunction[(String, Type, Type), ComparisonOp[_]] = {
    case ("==" | "EQ", t1, t2) =>
      checkCompatible(t1, t2)
      EQ(t1, t2)
    case ("!=" | "NEQ", t1, t2) =>
      checkCompatible(t1, t2)
      NEQ(t1, t2)
    case (">=" | "GTEQ", t1, t2) =>
      checkCompatible(t1, t2)
      GTEQ(t1, t2)
    case ("<=" | "LTEQ", t1, t2) =>
      checkCompatible(t1, t2)
      LTEQ(t1, t2)
    case (">" | "GT", t1, t2) =>
      checkCompatible(t1, t2)
      GT(t1, t2)
    case ("<" | "LT", t1, t2) =>
      checkCompatible(t1, t2)
      LT(t1, t2)
    case ("Compare", t1, t2) =>
      checkCompatible(t1, t2)
      Compare(t1, t2)
  }

  def invert[T](op: ComparisonOp[Boolean]): ComparisonOp[Boolean] = {
    assert(!op.isInstanceOf[Compare])
    op match {
      case GT(t1, t2) => LTEQ(t1, t2)
      case LT(t1, t2) => GTEQ(t1, t2)
      case GTEQ(t1, t2) => LT(t1, t2)
      case LTEQ(t1, t2) => GT(t1, t2)
      case EQ(t1, t2) => NEQ(t1, t2)
      case NEQ(t1, t2) => EQ(t1, t2)
      case EQWithNA(t1, t2) => NEQWithNA(t1, t2)
      case NEQWithNA(t1, t2) => EQWithNA(t1, t2)
    }
  }
}

sealed trait ComparisonOp[ReturnType] {
  def t1: Type
  def t2: Type
  val op: CodeOrdering.Op
  val strict: Boolean = true
  def codeOrdering(ecb: EmitClassBuilder[_], t1p: SType, t2p: SType): CodeOrdering.F[op.ReturnType] = {
    ComparisonOp.checkCompatible(t1p.virtualType, t2p.virtualType)
    ecb.getOrderingFunction(t1p, t2p, op).asInstanceOf[CodeOrdering.F[op.ReturnType]]
  }

  def render(): is.hail.utils.prettyPrint.Doc = Pretty.prettyClass(this)
}

case class GT(t1: Type, t2: Type) extends ComparisonOp[Boolean] { val op: CodeOrdering.Op = CodeOrdering.Gt() }
object GT { def apply(typ: Type): GT = GT(typ, typ) }
case class GTEQ(t1: Type, t2: Type) extends ComparisonOp[Boolean] { val op: CodeOrdering.Op = CodeOrdering.Gteq() }
object GTEQ { def apply(typ: Type): GTEQ = GTEQ(typ, typ) }
case class LTEQ(t1: Type, t2: Type) extends ComparisonOp[Boolean] { val op: CodeOrdering.Op = CodeOrdering.Lteq() }
object LTEQ { def apply(typ: Type): LTEQ = LTEQ(typ, typ) }
case class LT(t1: Type, t2: Type) extends ComparisonOp[Boolean] { val op: CodeOrdering.Op = CodeOrdering.Lt() }
object LT { def apply(typ: Type): LT = LT(typ, typ) }
case class EQ(t1: Type, t2: Type) extends ComparisonOp[Boolean] { val op: CodeOrdering.Op = CodeOrdering.Equiv() }
object EQ { def apply(typ: Type): EQ = EQ(typ, typ) }
case class NEQ(t1: Type, t2: Type) extends ComparisonOp[Boolean] { val op: CodeOrdering.Op = CodeOrdering.Neq() }
object NEQ { def apply(typ: Type): NEQ = NEQ(typ, typ) }
case class EQWithNA(t1: Type, t2: Type) extends ComparisonOp[Boolean] {
  val op: CodeOrdering.Op = CodeOrdering.Equiv()
  override val strict: Boolean = false
}
object EQWithNA { def apply(typ: Type): EQWithNA = EQWithNA(typ, typ) }
case class NEQWithNA(t1: Type, t2: Type) extends ComparisonOp[Boolean] {
  val op: CodeOrdering.Op = CodeOrdering.Neq()
  override val strict: Boolean = false
}
object NEQWithNA { def apply(typ: Type): NEQWithNA = NEQWithNA(typ, typ) }
case class Compare(t1: Type, t2: Type) extends ComparisonOp[Int] {
  override val strict: Boolean = false
  val op: CodeOrdering.Op = CodeOrdering.Compare()
}
object Compare { def apply(typ: Type): Compare = Compare(typ, typ) }

trait StructComparisonOp[T] extends ComparisonOp[T] {
  val sortFields: Array[SortField]

  override def codeOrdering(ecb: EmitClassBuilder[_], t1: SType, t2: SType): F[op.ReturnType] = {
    ComparisonOp.checkCompatible(t1.virtualType, t2.virtualType)
    ecb.getStructOrderingFunction(t1.asInstanceOf[SBaseStruct], t2.asInstanceOf[SBaseStruct], sortFields, op).asInstanceOf[CodeOrdering.F[op.ReturnType]]
  }
}

case class StructCompare(t1: Type, t2: Type, sortFields: Array[SortField]) extends StructComparisonOp[Int] {
  val op: CodeOrdering.Op = CodeOrdering.StructCompare()
  override val strict: Boolean = false
}

case class StructLT(t1: Type, t2: Type, sortFields: Array[SortField]) extends StructComparisonOp[Boolean] {
  val op: CodeOrdering.Op = CodeOrdering.StructLt()
}

object StructLT { def apply(typ: Type, sortFields: IndexedSeq[SortField]): StructLT = StructLT(typ, typ, sortFields.toArray) }

case class StructLTEQ(t1: Type, t2: Type, sortFields: Array[SortField]) extends StructComparisonOp[Boolean] {
  val op: CodeOrdering.Op = CodeOrdering.StructLteq()
}

object StructLTEQ { def apply(typ: Type, sortFields: IndexedSeq[SortField]): StructLTEQ = StructLTEQ(typ, typ, sortFields.toArray) }

case class StructGT(t1: Type, t2: Type, sortFields: Array[SortField]) extends StructComparisonOp[Boolean] {
  val op: CodeOrdering.Op = CodeOrdering.StructGt()
}

object StructGT { def apply(typ: Type, sortFields: IndexedSeq[SortField]): StructGT = StructGT(typ, typ, sortFields.toArray) }