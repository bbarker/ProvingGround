package provingground

import HoTT._
//import Families._
import math.max
//import ScalaUniverses._
//import scala.util._
import scala.language.existentials

import scala.language.implicitConversions

/**
  * @author gadgil
  * A recursive function definition, i.e., rec_{W,X}(d1)(d2)...
  */
@deprecated("use orElse based case definitions", "April 22, 2016")
trait RecursiveDefinition[C <: Term with Subs[C], H <: Term with Subs[H]] {
  self =>

  /**
    * W in rec(W)(X)
    */
  val W: Typ[H]

  /**
    * X in rec(W)(X)
    */
  val X: Typ[C]

  /**
    * recursive definition, with offspring applying f.
    */
  def recursion(f: => Func[H, C]): Func[H, C]

  /**
    * the function to use, applying itself to recursion
    */
  def func: Func[H, C] = recursion(func)

  def prependPair(cons: Constructor[C, H])(
      arg: cons.pattern.RecDataType): RecursiveDefinition[C, H] = {
    type D = cons.pattern.RecDataType

    val caseFn: D => Func[H, C] => Func[H, C] => Func[H, C] = (d) =>
      (f) => (g) => cons.pattern.recModify(cons.cons)(d)(f)(g)

    RecDefinitionCons(arg, caseFn, self)
  }

//  import RecursiveDefinition.{recFn}

  def prepend(cons: Constructor[C, H], sym: AnySym) =
    prependPair(cons)(cons.pattern.recDataTyp(W, X).symbObj(sym))
}

/**
  * recursive definition with empty constructor, hence empty data
  */
@deprecated("use orElse based case definitions", "April 22, 2016")
case class RecDefinitionTail[C <: Term with Subs[C], H <: Term with Subs[H]](
    W: Typ[H], X: Typ[C])
    extends RecursiveDefinition[C, H] {
  def recursion(f: => Func[H, C]): Func[H, C] =
    new Func[H, C] {
      def newobj = this

      def subs(x: Term, y: Term) = this

      val dom = W

      val codom = X

      val typ = W ->: X

      def act(w: H) = X.symbObj(ApplnSym(f, w))
    }
}

@deprecated("use orElse based case definitions", "April 22, 2016")
case class RecDefinitionCons[
    D <: Term with Subs[D], C <: Term with Subs[C], H <: Term with Subs[H]](
    arg: D,
    caseFn: D => Func[H, C] => Func[H, C] => Func[H, C],
    tail: RecursiveDefinition[C, H])
    extends RecursiveDefinition[C, H] {

  lazy val W = tail.W

  lazy val X = tail.X

  def recursion(f: => Func[H, C]) = {
    def fn(x: H) = caseFn(arg)(f)(tail.recursion(f))(x)
    new FuncDefn(fn, W, X)
  }
}

object RecursiveDefinition {

  def recFn[C <: Term with Subs[C], H <: Term with Subs[H]](
      conss: List[Constructor[C, H]], W: Typ[H], X: Typ[C]) = {
    val namedConss = for (c <- conss) yield (c, NameFactory.get)

    def addCons(
        cn: (Constructor[C, H], String), defn: RecursiveDefinition[C, H]) =
      defn.prepend(cn._1, cn._2)

    val init: RecursiveDefinition[C, H] = RecDefinitionTail(W, X)
    val lambdaValue: Term = (namedConss :\ init)(addCons).func

    val variables: List[Term] = for ((c, name) <- namedConss) yield
      c.pattern.recDataTyp(W, X).symbObj(name)

    (variables :\ lambdaValue)(lmbda(_)(_))
  }
}