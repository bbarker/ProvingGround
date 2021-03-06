package provingground.learning
import provingground._, HoTT._

import spire.algebra._
import spire.implicits._

import provingground.{FiniteDistribution => FD, ProbabilityDistribution => PD}

import monix.eval._
import math._

import scala.concurrent._, duration._

import translation.FansiShow._

/**
  * A collection of functions to build provers;
  * some are abstract methods for exploring, searching etc., while others generate terms and types, sometimes as derivatives.
  * Some methods combine the two to give a ready to use function.
  * These are based on [[TermEvolver]] and `Monix`.
  */
object ProverTasks {

  /**
    * evolution of types, as a  (task with value) finite distribution;
    * obtained by lazy recursive definition in [[TermEvolver]] and trunctation.
    */
  def typdistTask(fd: FD[Term],
                  tv: TermEvolver,
                  cutoff: Double,
                  maxtime: FiniteDuration,
                  vars: Vector[Term] = Vector()) =
    Truncate
      .task(tv.baseEvolveTyps(fd), cutoff, maxtime)
      .memoize

  /**
    * evolution of terms, as a  (task with value) finite distribution;
    * obtained by lazy recursive definition in [[TermEvolver]] and trunctation.
    */
  def termdistTask(fd: FD[Term],
                   tv: TermEvolver,
                   cutoff: Double,
                   maxtime: FiniteDuration,
                   vars: Vector[Term] = Vector()) =
    Truncate
      .task(tv.baseEvolve(fd), cutoff, maxtime)
      .memoize

  /**
    * evolution of terms by the ''derivative'', as a  (task with value) finite distribution;
    * obtained by lazy recursive definition in [[TermEvolver]] and trunctation.
    */
  def termdistDerTask(fd: FD[Term],
                      tfd: FD[Term],
                      tv: TermEvolver,
                      cutoff: Double,
                      maxtime: FiniteDuration,
                      vars: Vector[Term] = Vector()) =
    Truncate
      .task(tv.evolve(TangVec(fd, tfd)).vec, cutoff, maxtime)
      .memoize

  /**
    * An entropy measure for parsimonious generation of terms inhabiting types.
    */
  def h0(p: Double, q: Double) = {
    require(q > 0, s"Entropy with p=$p, q = $q")
    -p / (q * log(q))
  }

  /**
    * Exponent of an entropy measure for parsimonious generation of terms inhabiting types;
    * low values correspond to important terms, with the `scale` increasing sensitivity.
    */
  def hExp(p: Double, q: Double, scale: Double) =
    math.exp(-(h0(p, q) - 1) * scale)

  def prsmEntTask(termsTask: Task[FD[Term]],
                  typsTask: Task[FD[Typ[Term]]],
                  scale: Double,
                  vars: Vector[Term] = Vector()) =
    for {
      terms <- termsTask
      typs  <- typsTask
      thmsByPf = terms.map(_.typ)
      thmsBySt = typs.filter(thmsByPf(_) > 0)
      pfSet    = terms.flatten.supp.filter((t) => thmsBySt(t.typ) > 0)
      fullPfSet = pfSet.flatMap((pf) =>
        partialLambdaClosures(vars)(pf).map((pf, _)))
    } yield
      fullPfSet
        .map {
          case (pf, fullPf) =>
            (fullPf,
             hExp(thmsBySt(pf.typ),
                  thmsByPf(pf.typ),
                  scale * terms(pf) / thmsByPf(pf.typ)))
        }
        .sortBy(_._2)

  def dervecTasks(base: FD[Term],
                  tv: TermEvolver,
                  termsTask: Task[FD[Term]],
                  typsTask: Task[FD[Typ[Term]]],
                  maxtime: FiniteDuration,
                  cutoff: Double,
                  scale: Double,
                  vars: Vector[Term] = Vector()): Task[Vector[Task[FD[Term]]]] =
    prsmEntTask(termsTask, typsTask, scale, vars = vars).map { (vec) =>
      {
        val scales = vec.collect {
          case (v, p) if p > cutoff && cutoff / p > 0 =>
            (v, cutoff / p)
        }
        val tot   = scales.map { case (_, x) => 1 / x }.sum
        val ratio = max(tot * cutoff, 1.0)
        // pprint.log(s"want: ${1/cutoff}, actual total: $tot from ${scales.size}")
        scales.map {
          case (v, sc) =>
            termdistDerTask(base, FD.unif(v), tv, sc * ratio, maxtime, vars)
        }
      }
    }

  def dervecTraceTasks(base: FD[Term],
                       tv: TermEvolver,
                       termsTask: Task[FD[Term]],
                       typsTask: Task[FD[Typ[Term]]],
                       maxtime: FiniteDuration,
                       cutoff: Double,
                       scale: Double,
                       trace: Vector[Term],
                       vars: Vector[Term] = Vector())
    : Task[Vector[Task[(FD[Term], Vector[Term])]]] =
    prsmEntTask(termsTask, typsTask, scale, vars = vars).map { (vec) =>
      {
        val scales = vec.collect {
          case (v, p) if p > cutoff && cutoff / p > 0 && !(trace.contains(v)) =>
            (v, cutoff / p)
        }
        val tot   = scales.map { case (_, x) => 1 / x }.sum
        val ratio = max(tot * cutoff, 1.0)
        // pprint.log(s"want: ${1/cutoff}, actual total: $tot from ${scales.size}")
        scales.map {
          case (v, sc) =>
            for {
              fd <- termdistDerTask(base,
                                    FD.unif(v),
                                    tv,
                                    sc * ratio,
                                    maxtime,
                                    vars)
            } yield (fd, trace :+ v)
        }
      }
    }

  def dervecWeightedTraceTasks(base: FD[Term],
                               tv: TermEvolver,
                               termsTask: Task[FD[Term]],
                               typsTask: Task[FD[Typ[Term]]],
                               maxtime: FiniteDuration,
                               cutoff: Double,
                               scale: Double,
                               trace: Vector[(Term, Double)],
                               vars: Vector[Term] = Vector())
    : Task[Vector[Task[(FD[Term], Vector[(Term, Double)])]]] =
    prsmEntTask(termsTask, typsTask, scale, vars = vars).map { (vec) =>
      {
        val scales = vec.collect {
          case (v, p) if p > cutoff && cutoff / p > 0 && !(trace.contains(v)) =>
            (v, p, cutoff / p)
        }
        val tot   = scales.map { case (_, _, x) => 1 / x }.sum
        val ratio = max(tot * cutoff, 1.0)
        // pprint.log(s"want: ${1/cutoff}, actual total: $tot from ${scales.size}")
        scales.map {
          case (v, p, sc) =>
            for {
              fd <- termdistDerTask(base,
                                    FD.unif(v),
                                    tv,
                                    sc * ratio,
                                    maxtime,
                                    vars)
            } yield (fd, trace :+ (v -> sc * ratio))
        }
      }
    }

  def inTaskVec[X, Y](tv: Task[Vector[X]], p: X => Option[Y]): Task[Option[Y]] =
    tv.flatMap {
      case Vector() => Task.pure(None)
      case x +: ys =>
        Task.eval(p(x)).flatMap {
          case Some(z) => Task.pure(Some(z))
          case None    => inTaskVec(Task.eval(ys), p)
        }
    }

  def inTaskVecTask[X, Y](tv: Task[Vector[Task[X]]],
                          p: X => Option[Y]): Task[Option[Y]] =
    tv.flatMap {
      case Vector() => Task.pure(None)
      case x +: ys =>
        x.map(p).flatMap {
          case Some(z) => Task.pure(Some(z))
          case None    => inTaskVecTask(Task.eval(ys), p)
        }
    }

  def breadthFirstTask[X, Y](tv: Task[Vector[Task[X]]],
                             p: X => Option[Y],
                             spawn: Int => X => Task[Vector[Task[X]]],
                             depth: Int = 0): Task[Option[Y]] = {
    tv.flatMap { (vec) =>
      if (vec.isEmpty) Task.pure(None)
      else
        inTaskVecTask(tv, p).flatMap {
          case Some(z) => Task.pure(Some(z))
          case None =>
            val tskVecs: Vector[Task[Vector[Task[X]]]] = vec.map(
              (t) => t.flatMap(spawn(depth))
            )
            val w: Task[Vector[Task[X]]] = Task.gather(tskVecs).map(_.flatten)
            breadthFirstTask(w, p, spawn, depth + 1)
        }
    }
  }

  def branchedGatherTask[X, Y](tsk: Task[X],
                               results: Int => X => Task[Y],
                               combine: (Y, Y) => Y,
                               spawn: Int => X => Task[Vector[Task[X]]],
                               depth: Int = 0): Task[Y] = {
    for {
      x <- tsk
      res = results(depth)(x)
      nextGen <- spawn(depth)(x)
      childRes: Vector[Task[Y]] = nextGen.map((tx) =>
        branchedGatherTask(tx, results, combine, spawn, depth + 1))
      comb <- childRes.foldLeft(res) {
        case (t1, t2) =>
          for {
            x1 <- t1
            x2 <- t2
          } yield combine(x1, x2)
      }
    } yield comb
  }

  def theoremSearchTask(fd: FD[Term],
                        tv: TermEvolver,
                        cutoff: Double,
                        maxtime: FiniteDuration,
                        goal: Typ[Term],
                        decay: Double = 1.0,
                        scale: Double = 1.0,
                        vars: Vector[Term] = Vector()) = {
    val typsTask  = typdistTask(fd, tv, cutoff, maxtime, vars)
    val termsTask = termdistTask(fd, tv, cutoff, maxtime, vars)
    def spawn(d: Int)(vec: FD[Term]) = {
      if (fd.total == 0) Task.pure(Vector())
      else
        dervecTasks(
          fd.safeNormalized,
          tv,
          Task.eval(vec),
          typsTask,
          maxtime,
          cutoff * math.pow(decay, 1.0 + d.toDouble),
          scale,
          vars
        )
    }
    breadthFirstTask[FD[Term], Term](
      termsTask.map((fd) => Vector(Task.eval(fd))),
      (findist: FD[Term]) => findist.supp.find(_.typ == goal),
      spawn)
  }

  def theoremsExploreTask(fd: FD[Term],
                          tv: TermEvolver,
                          cutoff: Double,
                          maxtime: FiniteDuration,
                          decay: Double = 1.0,
                          scale: Double = 1.0,
                          vars: Vector[Term] = Vector()) = {
    val typsTask  = typdistTask(fd, tv, cutoff, maxtime, vars)
    val termsTask = termdistTask(fd, tv, cutoff, maxtime, vars)
    def spawn(d: Int)(vec: FD[Term]) = {
      if (fd.total == 0) Task.pure(Vector())
      else
        dervecTasks(
          fd.safeNormalized,
          tv,
          Task.eval(vec),
          typsTask,
          maxtime,
          cutoff * math.pow(decay, 1.0 + d.toDouble),
          scale,
          vars
        )
    }

    def result(d: Int)(fd: FD[Term]): Task[Vector[(Term, (Int, Double))]] =
      typsTask.map { (p) =>
        {
          val q = fd.map(_.typ)
          fd.flatten.supp.collect {
            case t if p(t.typ) > 0 && q(t.typ) > 0 && q(t.typ) < 1 =>
              (t, (d, fd(t)))
          }
        }
      }

    branchedGatherTask[FD[Term], Vector[(Term, (Int, Double))]](termsTask,
                                                                result,
                                                                _ ++ _,
                                                                spawn).map(
      (v) =>
        v.groupBy(_._1)
          .mapValues((v) => v.toVector.map(_._2).maxBy((dp) => (-dp._1, dp._2)))
          .toVector
          .groupBy(_._1.typ: Typ[Term])
          .mapValues(_.sortBy((tv) => (tv._2._1, -tv._2._2))))
  }

  def theoremSearchTraceTask(fd: FD[Term],
                             tv: TermEvolver,
                             cutoff: Double,
                             maxtime: FiniteDuration,
                             goal: Typ[Term],
                             decay: Double = 1.0,
                             scale: Double = 1.0,
                             vars: Vector[Term] = Vector()) = {
    val typsTask  = typdistTask(fd, tv, cutoff, maxtime, vars)
    val termsTask = termdistTask(fd, tv, cutoff, maxtime, vars)
    def spawn(d: Int)(vecAc: (FD[Term], Vector[Term])) = {
      val (vec, ac) = vecAc
      if (fd.total == 0) Task.pure(Vector())
      // pprint.log(s"spawning tasks from $vec")
      else
        dervecTraceTasks(
          fd.safeNormalized,
          tv,
          Task.eval(vec),
          typsTask,
          maxtime,
          cutoff * math.pow(decay, 1.0 + d.toDouble),
          scale,
          ac,
          vars
        )
    }
    breadthFirstTask[(FD[Term], Vector[Term]), (Term, Vector[Term])](
      termsTask.map((fd) => Vector(Task.eval((fd, Vector.empty[Term])))),
      (findistAc: (FD[Term], Vector[Term])) =>
        findistAc._1.supp.find(_.typ == goal).map((t) => (t, findistAc._2)),
      spawn
    )
  }

  def theoremsExploreTraceTask(fd: FD[Term],
                               tv: TermEvolver,
                               cutoff: Double,
                               maxtime: FiniteDuration,
                               decay: Double = 1.0,
                               scale: Double = 1.0,
                               vars: Vector[Term] = Vector()) = {
    val typsTask  = typdistTask(fd, tv, cutoff, maxtime, vars)
    val termsTask = termdistTask(fd, tv, cutoff, maxtime, vars)
    def spawn(d: Int)(vecAc: (FD[Term], Vector[(Term, Double)])) = {
      val (vec, ac) = vecAc
      if (fd.total == 0) Task.pure(Vector())
      else
        dervecWeightedTraceTasks(
          fd.safeNormalized,
          tv,
          Task.eval(vec),
          typsTask,
          maxtime,
          cutoff * math.pow(decay, 1.0 + d.toDouble),
          scale,
          ac,
          vars
        )
    }

    def result(d: Int)(fdAc: (FD[Term], Vector[(Term, Double)]))
      : Task[Vector[(Term, Vector[(Term, Double)], (Int, Double))]] =
      typsTask.map { (p) =>
        {
          val (fd, ac) = fdAc
          val q        = fd.map(_.typ)
          fd.flatten.supp.collect {
            case t if p(t.typ) > 0 && q(t.typ) > 0 && q(t.typ) < 1 =>
              (t, ac, (d, fd(t)))
          }
        }
      }

    for {
      res <- branchedGatherTask[(FD[Term], Vector[(Term, Double)]),
                                Vector[(Term,
                                        Vector[(Term, Double)],
                                        (Int, Double))]](
        termsTask.map((fd) => (fd, Vector())),
        result,
        _ ++ _,
        spawn);
      typs  <- typsTask
      terms <- termsTask

    } yield (res, terms, typs)
  }

  def h[A](fd: FD[A]) = {
    val fd0 = fd.flatten
    fd0.supp.map((x) => -fd(x) * log(fd(x))).sum
  }

  def kl[A](p: FD[A], q: FD[A]) = {
    val p0 = p.filter(q(_) > 0).flatten.safeNormalized
    p0.supp.map { (x) =>
      p(x) * log(p(x) / q(x))
    }.sum
  }

  def minOn[A](fd: FD[A], minValue: Double, supp: Set[A]) = {
    FD(
      for {
        Weighted(x, p) <- fd.pmf
      } yield Weighted(x, if (supp.contains(x)) math.min(p, minValue) else p)
    )
  }

  def pfMatch(ev: FD[Term] => Task[FD[Term]],
              typs: Task[FD[Typ[Term]]],
              wt: Double = 1.0)(gen: FD[Term]) =
    for {
      p  <- typs
      qt <- ev(gen)
      q = qt.map(_.typ)
    } yield kl(p, q) - (h(gen) * wt)

  def pfMatchDiff(
      ev: FD[Term] => Task[FD[Term]],
      typs: Task[FD[Typ[Term]]],
      cutoff: Double,
      wt: Double = 1.0)(gen0: FD[Term], gen1: FD[Term]): Task[Double] =
    for {
      p   <- typs
      qt0 <- ev(gen0)
      qt1 <- ev(gen1)
      q0      = qt0.map(_.typ)
      q1      = qt1.map(_.typ)
      totSupp = q0.support union (q1.support)
      q0m     = minOn(q0, cutoff, totSupp)
      q1m     = minOn(q1, cutoff, totSupp)
      h0      = kl(p, q0m) - (h(gen0) * wt)
      h1      = kl(p, q1m) - (h(gen1) * wt)
    } yield h1 - h0

  def quasiGradShift[A](base: A,
                        neighbours: Vector[A],
                        derTask: (A, A) => Task[Double])(
      implicit ls: VectorSpace[A, Double]
  ): Task[A] = {
    val shiftsTask =
      Task.gather {
        for { x <- neighbours } yield {
          derTask(base, x).map { (der) =>
            -der *: (base + x)
          }
        }
      }
    shiftsTask.map { (shifts) =>
      shifts.foldLeft(base)(_ + _)
    }
  }

  def quasiGradFlowTask[A](base: A,
                           neighMap: A => Vector[A],
                           derTask: (A, A) => Task[Double],
                           halt: (A, A) => Boolean)(
      implicit ls: VectorSpace[A, Double]
  ): Task[A] = Task.tailRecM(base)(
    (x: A) =>
      quasiGradShift(x, neighMap(x), derTask).map { (y) =>
        if (halt(x, y)) Right(y) else Left(x)
    }
  )

  def selfNeighbours[A](fd: FD[A], epsilon: Double) =
    fd.flatten.supp.map { (x) =>
      (fd + (x, fd(x) * epsilon)).safeNormalized
    }

  def newNeighbours[A](fd: FD[A], pert: Vector[A], epsilon: Double) =
    pert.map { (x) =>
      (fd + (x, fd(x) * epsilon)).safeNormalized
    }

  def stabHalt(x: FD[Term], y: FD[Term], level: Double): Boolean =
    (x -- y).norm < level

}
