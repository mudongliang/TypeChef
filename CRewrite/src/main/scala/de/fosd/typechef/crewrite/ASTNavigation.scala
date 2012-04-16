package de.fosd.typechef.crewrite

import de.fosd.typechef.parser.c.AST
import de.fosd.typechef.conditional._
import org.kiama.rewriting.Rewriter._

// simplified navigation support
// reimplements basic navigation between AST nodes not affected by Opt and Choice nodes
// see old version: https://github.com/ckaestne/TypeChef/blob/ConditionalControlFlow/CParser/src/main/scala/de/fosd/typechef/parser/c/ASTNavigation.scala
trait ASTNavigation {

  // method simply goes up the hierarchy and looks for next AST element and returns it
  def parentAST(e: Product, env: ASTEnv): AST = {
    val eparent = env.parent(e)
    eparent match {
      case o: Opt[_] => parentAST(o, env)
      case c: Conditional[_] => parentAST(c, env)
      case a: AST => a
      case _ => null
    }
  }

  // getting the previous element of an AST element in the presence of Opt and Choice
  // has to consider the following situation
  // Opt elements usually appear in the presence of lists
  // List[Opt[_]] (see AST for more information)
  // having a list of Opt elements, AST elements usually appear in those Opt elements
  // [ Opt(f1, AST), Opt(f2, AST), ..., Opt(fn, AST)]
  // to get the previous AST element of each AST element in that list, we have to
  // go one level up and look for previous Opt elements and their children
  def prevAST(e: Product, env: ASTEnv): AST = {
    val eprev = env.previous(e)
    eprev match {
      case c: Choice[_] => lastChoice(c)
      case o: One[_] => o.value.asInstanceOf[AST]
      case a: AST => a
      case Opt(_, v: Choice[_]) => lastChoice(v)
      case Opt(_, v: One[_]) => v.value.asInstanceOf[AST]
      case Opt(_, v: AST) => v
      case null => {
        val eparent = env.parent(e)
        eparent match {
          case o: Opt[_] => prevAST(o, env)
          case c: Choice[_] => prevAST(c, env)
          case c: One[_] => prevAST(c, env)
          case _ => null
        }
      }
    }
  }

  // similar to prevAST but with next
  def nextAST(e: Product, env: ASTEnv): AST = {
    val enext = env.next(e)
    enext match {
      case c: Choice[_] => firstChoice(c)
      case o: One[_] => o.value.asInstanceOf[AST]
      case a: AST => a
      case Opt(_, v: Choice[_]) => firstChoice(v)
      case Opt(_, v: One[_]) => v.value.asInstanceOf[AST]
      case Opt(_, v: AST) => v
      case null => {
        val eparent = env.parent(e)
        eparent match {
          case o: Opt[_] => nextAST(o, env)
          case c: Choice[_] => nextAST(c, env)
          case c: One[_] => nextAST(c, env)
          case _ => null
        }
      }
    }
  }

  // returns a list of all previous AST elements including e
  // useful in compound statements that have a list of Opt elements
  // prevASTElems(e, env) // ei == e
  // [ Opt(f1, e1), Opt(f2, e2), ..., Opt(fi, ei), ..., Opt(fn, en) ]
  // returns [e1, e2, ..., ei]
  def prevASTElems(e: Product, env: ASTEnv): List[AST] = {
    e match {
      case null => List()
      case s => prevASTElems(prevAST(s, env), env) ++ List(childAST(s))
    }
  }

  // returns a list of all next AST elements including e
  // [ Opt(f1, e1), Opt(f2, e2), ..., Opt(fi, ei), ..., Opt(fn, en) ]
  // returns [ei, ..., en]
  def nextASTElems(e: Product, env: ASTEnv): List[AST] = {
    e match {
      case null => List()
      case s => List(childAST(s)) ++ nextASTElems(nextAST(s, env), env)
    }
  }

  // returns the first AST element that is nested in the following elements
  // or null; elements are Opt, Conditional, and Some
  // function does not work for type List[_]
  def childAST(e: Product): AST = {
    e match {
      case Opt(_, v: AST) => v
      case Opt(_, v: One[_]) => v.value.asInstanceOf[AST]
      case Opt(_, v: Choice[_]) => firstChoice(v)
      case x: One[_] => x.value.asInstanceOf[AST]
      case a: AST => a
      case x: Option[_] if (x.isDefined) => childAST(x.get.asInstanceOf[Product])
      case _ => null
    }
  }

  // method recursively filters all AST elements for a given type T
  // method is broken, because case x always matches and therefore the filtering does not work properly
  def filterASTElems[T <: AST](a: Product)(implicit m: ClassManifest[T]): List[T] = {
    var res: List[T] = List()
    val filter = oncetd(query{ case x if (m.erasure.isInstance(x)) => res ::= x.asInstanceOf[T] })
    filter(a)
    res
  }

  def filterAllASTElems[T <: AST](a: Product)(implicit m: ClassManifest[T]): List[T] = {
    var res: List[T] = List()
    val filter = manytd(query{ case x if (m.erasure.isInstance(x)) => res ::= x.asInstanceOf[T] })
    filter(a)
    res
  }

  // go up the AST hierarchy and look for a specific AST element with type T
  def findPriorASTElem[T <: AST](a: Product, env: ASTEnv)(implicit m: ClassManifest[T]): Option[T] = {
    a match {
      case x if (m.erasure.isInstance(x)) => Some(x.asInstanceOf[T])
      case x: Product => findPriorASTElem[T](parentAST(x, env), env)
      case null => None
    }
  }

  // recursively walk right branch of Choice structure until we hit an AST element
  private def lastChoice(x: Choice[_]): AST = {
    x.elseBranch match {
      case c: Choice[_] => lastChoice(c)
      case One(c) => c.asInstanceOf[AST]
    }
  }

  // recursively walk left branch of Choice structure until we hit an AST element
  private def firstChoice(x: Choice[_]): AST = {
    x.thenBranch match {
      case c: Choice[_] => firstChoice(c)
      case One(c) => c.asInstanceOf[AST]
    }
  }
}