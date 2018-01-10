package provingground.functionfinder

import provingground._, HoTT._
import ScalaRep._
// import translation._
//import scala.language.{existentials, implicitConversions}
import scala.util._
// import spire.algebra._
// import spire.implicits._
//import spire.syntax._
import cats.kernel._, cats.implicits._, cats.syntax._

class SymbolicGroup[A: Group] extends ScalaTyp[A] { self =>
  val group = implicitly[Group[A]]

  type LocalTerm = RepTerm[A]

  type Op = Func[LocalTerm, Func[LocalTerm, LocalTerm]]

  val e = group.empty.term

  object Comb {
    def unapply(term: Term): Option[(LocalTerm, LocalTerm)] = term match {
      case FormalAppln(FormalAppln(`mul`, x), y) =>
        Try((x.asInstanceOf[LocalTerm], y.asInstanceOf[LocalTerm])).toOption
      case _ => None
    }

    def apply(x: LocalTerm, y: LocalTerm) = FormalAppln(FormalAppln(mul, x), y)
  }

  object Literal extends ScalaSym[LocalTerm, A](self)

  case object inv extends Func[LocalTerm, LocalTerm] {
    override val toString = "inv"

    val dom = self

    val codom = self

    val typ = self ->: self

    def subs(x: Term, y: Term) = this

    def newobj =
      throw new IllegalArgumentException(
        s"trying to use the constant $this as a variable (or a component of one)")

    def act(y: LocalTerm) = y match {
      case Literal(b)                       => Literal(group.inverse(b))
      case FormalAppln(`inv`, p: LocalTerm) => p
      case Comb(x, y)                       => mul(inv(y))(inv(x))
      case p                                => FormalAppln(inv, p)
    }
  }

  case class MultLiteral(a: A) extends Func[LocalTerm, LocalTerm] {
    val dom = self

    val codom = self

    val typ = self ->: self

    def subs(x: Term, y: Term) = this

    def newobj =
      throw new IllegalArgumentException(
        s"trying to use the constant $this as a variable (or a component of one)")

    def act(y: LocalTerm) = y match {
      case Literal(b)          => Literal(group.combine(a, b))
      case Comb(Literal(b), v) => MultLiteral(group.combine(a, b))(v)
      case p                   => Comb(Literal(a), p)
    }
  }

  case class MultTerm(a: LocalTerm) extends Func[LocalTerm, LocalTerm] {
    val dom = self

    lazy val ia = inv(a)

    val codom = self

    val typ = self ->: self

    def subs(x: Term, y: Term) = mul(a.replace(x, y))

    def newobj =
      throw new IllegalArgumentException(
        s"trying to use the constant $this as a variable (or a component of one)")

    def act(y: LocalTerm) = y match {
      case Comb(u, v) =>
        if (u == ia) v else Comb(a, Comb(u, v))
      case `e` => a
      case p =>
        if (p == ia) e else Comb(a, p)
    }
  }

  case object mul extends Func[LocalTerm, Func[LocalTerm, LocalTerm]] { w =>
    val dom = self

    val codom = self ->: self

    val typ = dom ->: codom

    def subs(x: Term, y: Term) = this

    def newobj =
      throw new IllegalArgumentException(
        s"trying to use the constant $this as a variable (or a component of one)")

    def act(y: LocalTerm) = y match {
      case `e`        => HoTT.id(self)
      case Literal(a) => MultLiteral(a)
      case Comb(u, v) =>
        val x = self.Var
        x :-> mul(u)(mul(v)(x))
      case p => MultTerm(p)
    }

    override val toString = "mul"
  }

  implicit val groupStructure: Group[LocalTerm] = new Group[LocalTerm] {
    val empty = e

    def combine(x: LocalTerm, y: LocalTerm) = mul(x)(y)

    def inverse(x: LocalTerm) = inv(x)

  }

  val g = "g" :: self

  val h = "h" :: self

  val lm = g :-> (h :-> (g |+| h))

  val rm = g :-> (h :-> (h |+| g))

  val power = {
    import NatRing._
    val g                                          = "g" :: self
    val n                                          = "n" :: NatTyp
    val fng                                        = "f(n)(g)" :: self
    val step                                       = n :-> (fng :-> mul(fng)(g))
    val recFn: Func[NatRing.LocalTerm, RepTerm[A]] = Rec(e, step)
    g :-> recFn
  }
}

import andrewscurtis.FreeGroups._

object FreeGroup extends SymbolicGroup[Word] {
  def word(s: String) = Literal(Word.fromString(s))

  override def toString = "FreeGroup"
}
