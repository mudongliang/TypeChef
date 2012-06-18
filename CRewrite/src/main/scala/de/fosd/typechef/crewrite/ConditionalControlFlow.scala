package de.fosd.typechef.crewrite


import de.fosd.typechef.conditional._
import de.fosd.typechef.parser.c._
import java.util.IdentityHashMap
import de.fosd.typechef.featureexpr.{FeatureExprFactory, FeatureExpr}

// implements conditional control flow (cfg) on top of the typechef
// infrastructure
// at first sight the implementation of succ with a lot of private
// function seems overly complicated; however the structure allows
// also to implement pred
// consider the following points:

// the function definition an ast belongs to serves as the entry
// and exit node of the cfg, because we do not have special ast
// nodes for that, or we store everything in a ccfg itself with
// special nodes for entry and exit such as
// [1] http://soot.googlecode.com/svn/DUA_Forensis/src/dua/method/CFG.java

// normally pred succ are the same except for the following cases:
// 1. code in switch body that does not belong to a case block, but that has a label and
//    can be reached otherwise, e.g., switch (x) { l1: <code> case 0: .... }
// 2. infinite for loops without break or return statements in them, e.g.,
//    for (;;) { <code without break or return> }
//    this way we do not have any handle to jump into the for block from
//    its successors; we work around this issue by introducing a break statement
//    that always evaluates to true; for (;;) => for (;1;)

// for more information:
// iso/iec 9899 standard; committee draft
// [2] http://www.open-std.org/jtc1/sc22/wg14/www/docs/n1124.pdf

// TODO handling empty { } e.g., void foo() { { } }
// TODO support for (expr) ? (expr) : (expr);
// TODO analysis static gotos should have a label (if more labels must be unique according to feature expresssions)
// TODO analysis dynamic gotos should have a label
// TODO analysis: The expression of each case label shall be an integer constant expression and no two of
//                the case constant expressions in the same switch statement shall have the same value
//                after conversion.
// TODO analysis: There may be at most one default label in a switch statement.
// TODO analysis: we can continue this list only by looking at constrains in [2]


class CCFGCache {
  private val cache: IdentityHashMap[Product, List[AST]] = new IdentityHashMap[Product, List[AST]]()

  def update(k: Product, v: List[AST]) {
    cache.put(k, v)
  }

  def lookup(k: Product): Option[List[AST]] = {
    val v = cache.get(k)
    if (v != null) Some(v)
    else None
  }
}

trait ConditionalControlFlow extends ASTNavigation {

  private implicit def optList2ASTList(l: List[Opt[AST]]) = l.map(_.entry)
  private implicit def opt2AST(s: Opt[AST]) = s.entry

  private val predCCFGCache = new CCFGCache()
  private val succCCFGCache = new CCFGCache()

  // equal annotated AST elements
  type IfdefBlock  = List[AST]

  // cfg result consists of
  type CCFGRes     = List[(FeatureExpr, AST)]

  // determines predecessor of a given element
  // results are cached for secondary evaluation
  def pred(source: Product, env: ASTEnv): List[AST] = {
    predCCFGCache.lookup(source) match {
      case Some(v) => v
      case None => {
        var oldres: CCFGRes = List()
        val ctx = env.featureExpr(source)
        val curctx = env.featureExpr(source)
        val curres = List[(FeatureExpr, AST)]()
        var newres = predHelper(source, ctx, curctx, curres, env)
        var changed = true

        while (changed) {
          changed = false
          oldres = newres
          newres = List()

          for (oldelem <- oldres) {
            var add2newres: CCFGRes = List()
            oldelem._2 match {

              case _: ReturnStatement if (!source.isInstanceOf[FunctionDef]) => add2newres = List()

              // a break statement shall appear only in or as a switch body or loop body
              // a break statement terminates execution of the smallest enclosing switch or
              // iteration statement (see standard [2])
              // so as soon as we hit a break statement and the break statement belongs to the same loop as we do
              // the break statement is not a valid predecessor
              case b: BreakStatement => {
                val b2b = findPriorASTElem2BreakStatement(b, env)

                assert(b2b.isDefined, "missing loop to break statement!")
                if (isPartOf(source, b2b.get)) add2newres = List()
                else add2newres = List((env.featureExpr(b), b))
              }
              // a continue statement shall appear only in a loop body
              // a continue statement causes a jump to the loop-continuation portion
              // of the smallest enclosing iteration statement
              case c: ContinueStatement => {
                val a2c = findPriorASTElem2ContinueStatement(source, env)
                val b2c = findPriorASTElem2ContinueStatement(c, env)

                if (a2c.isDefined && b2c.isDefined && a2c.get.eq(b2c.get)) {
                  a2c.get match {
                    case WhileStatement(expr, _) if (isPartOf(source, expr)) => add2newres = List((env.featureExpr(c), c))
                    case DoStatement(expr, _) if (isPartOf(source, expr)) => add2newres = List((env.featureExpr(c), c))
                    case ForStatement(_, Some(expr2), None, _) if (isPartOf(source, expr2)) => add2newres = List((env.featureExpr(c), c))
                    case ForStatement(_, _, Some(expr3), _) if (isPartOf(source, expr3)) => add2newres = List((env.featureExpr(c), c))
                    case _ => add2newres = List()
                  }
                } else add2newres = List()
              }
              // in case we hit an elif statement, we have to check whether a and the elif belong to the same if
              // if a belongs to an if
              // TODO should be moved to pred determination directly
              case e@ElifStatement(condition, _) => {
                val a2e = findPriorASTElem[IfStatement](source, env)
                val b2e = findPriorASTElem[IfStatement](e, env)

                if (a2e.isEmpty) { add2newres = rollUp(e, oldelem._2, ctx, oldelem._1, oldres, env) }
                else if (a2e.isDefined && b2e.isDefined && a2e.get.eq(b2e.get)) {
                  add2newres = getCondExprPred(condition, ctx, oldelem._1, oldres.filterNot(td => td._1.eq(oldelem._1)), env)
                }
                else {
                  add2newres = rollUp(e, oldelem._2, ctx, oldelem._1, oldres.filterNot(td => td._1.eq(oldelem._1)), env)
                }
              }

              // goto statements
              // in general only label statements can be the source of goto statements
              // and only the ones that have the same name
              case s@GotoStatement(Id(name)) => {
                if (source.isInstanceOf[LabelStatement]) {
                  val lname = source.asInstanceOf[LabelStatement].id.name
                  if (name == lname) add2newres = List((env.featureExpr(s), s))
                }
              }

              // for all other elements we use rollup and check whether the outcome of rollup differs from
              // its input (oldelem)
              case _: AST => {
                val foldres = oldres.filterNot(td => td._1.eq(oldelem._1) || td._1.isTautology())
                add2newres = rollUp(source, oldelem._2, ctx, oldelem._1, foldres, env)
              }
            }

            // check whether oldelem is present in add2newres;
            // if false oldelem has been replaced by another element => changed = true
            // if true oldelem has nott been replaced => changed remains the same
            if (! add2newres.exists(_._2.eq(oldelem._2))) changed = true

            // add only elements that are not in newres so far
            // add them add the end to keep the order of the elements
            for (e@(f, addnew) <- add2newres)
              if (! newres.exists({case (_, x) => x.eq(addnew)})) newres = newres ++ List(e)
          }
        }
        val newret = newres.map(_._2)
        predCCFGCache.update(source, newret)
        newret
      }
    }
  }

  // checks reference equality of e in a given struture t (either product or list)
  def isPartOf(subterm: Product, term: Any): Boolean = {
    term match {
      case _: Product if (subterm.asInstanceOf[AnyRef].eq(term.asInstanceOf[AnyRef])) => true
      case l: List[_] => l.map(isPartOf(subterm, _)).exists(_ == true)
      case p: Product => p.productIterator.toList.map(isPartOf(subterm, _)).exists(_ == true)
      case _ => false
    }
  }

  def predHelper(source: Product, ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {

    // helper method to handle a switch, if we come from a case or a default statement
    def handleSwitch(t: AST) = {
      val prior_switch = findPriorASTElem[SwitchStatement](t, env)
      assert(prior_switch.isDefined, "default or case statements should always occur withing a switch definition")
      prior_switch.get match {
        case SwitchStatement(expr, _) => {
          getExprPred(expr, ctx, curctx, curres, env) ++ getStmtPred(t, ctx, curctx, curres, env)
        }
      }
    }

    source match {
      case t: CaseStatement => handleSwitch(t)
      case t: DefaultStatement => handleSwitch(t)

      case t@LabelStatement(Id(n), _) => {
        findPriorASTElem[FunctionDef](t, env) match {
          case None => assert(false, "label statements should always occur within a function definition"); curres
          case Some(f) => {
            val l_gotos = filterASTElems[GotoStatement](f, env.featureExpr(t), env)
            // filter gotostatements with the same id as the labelstatement
            // and all gotostatements with dynamic target
            val l_gotos_filtered = l_gotos.filter({
              case GotoStatement(Id(name)) => if (n == name) true else false
              case _ => true
            })
            val l_preds = getStmtPred(t, ctx, curctx, curres, env).
              flatMap({ x => rollUp(source, x._2, ctx, x._1, curres, env) })
            l_gotos_filtered.map(x => (env.featureExpr(x), x)) ++ l_preds
          }
        }
      }

      case o: Opt[_] => predHelper(childAST(o), ctx, curctx, curres, env)
      case c: Conditional[_] => predHelper(childAST(c), ctx, curctx, curres, env)

      case f@FunctionDef(_, _, _, CompoundStatement(List())) => List((env.featureExpr(f), f))
      case f@FunctionDef(_, _, _, stmt) => {
        predHelper(childAST(stmt), ctx, curctx, curres, env) ++
          filterAllASTElems[ReturnStatement](f, env.featureSet(f)).map(x => (env.featureExpr(x), x))
      }
      case c@CompoundStatement(innerStatements) => getCompoundPred(innerStatements, c, ctx, curctx, curres, env)

      case s: Statement => getStmtPred(s, ctx, curctx, curres, env)
      case _ => followPred(source, ctx, curctx, curres, env)
    }
  }

  def succ(source: Product, env: ASTEnv): List[AST] = {
    succCCFGCache.lookup(source) match {
      case Some(v) => v
      case None => {
        var oldres: CCFGRes = List()
        val ctx = env.featureExpr(source)
        val curctx = env.featureExpr(source)
        val curres: CCFGRes = List()
        var newres = succHelper(source, ctx, curctx, curres, env)
        var changed = true

        while (changed) {
          changed = false
          oldres = newres
          newres = List()
          for (oldelem <- oldres) {
            var add2newres: CCFGRes = List()
            val newctx = oldelem._1
            oldelem._2 match {
              case _: IfStatement => changed = true; add2newres = succHelper(oldelem._2, ctx, newctx, oldres.filterNot(td => td._2.eq(oldelem._2)), env)
              case _: ElifStatement => changed = true; add2newres = succHelper(oldelem._2, ctx, newctx, oldres.filterNot(td => td._2.eq(oldelem._2)), env)
              case _: SwitchStatement => changed = true; add2newres = succHelper(oldelem._2, ctx, newctx, oldres.filterNot(td => td._2.eq(oldelem._2)), env)
              case _: CompoundStatement => changed = true; add2newres = succHelper(oldelem._2, ctx, newctx, oldres.filterNot(td => td._2.eq(oldelem._2)), env)
              case _: DoStatement => changed = true; add2newres = succHelper(oldelem._2, newctx, ctx, oldres.filterNot(td => td._2.eq(oldelem._2)), env)
              case _: WhileStatement => changed = true; add2newres = succHelper(oldelem._2, ctx, newctx, oldres.filterNot(td => td._2.eq(oldelem._2)), env)
              case _: ForStatement => changed = true; add2newres = succHelper(oldelem._2, ctx, newctx, oldres.filterNot(td => td._2.eq(oldelem._2)), env)
              case _: DefaultStatement => changed = true; add2newres = succHelper(oldelem._2, ctx, newctx, oldres.filterNot(td => td._2.eq(oldelem._2)), env)
              case _ => add2newres = List(oldelem)
            }

            // add only elements that are not in newres so far
            // add them add the end to keep the order of the elements
            for (addnew <- add2newres)
              if (! newres.exists(_.eq(addnew))) newres ::= addnew
          }
        }
        val ret = newres.map(_._2)
        succCCFGCache.update(source, ret)
        ret
      }
    }
  }

  private def succHelper(source: Product, ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {
    source match {
      // ENTRY element
      case f@FunctionDef(_, _, _, CompoundStatement(List())) => curres ++ List((env.featureExpr(f), f)) // TODO after rewrite of compound handling -> could be removed
      case f@FunctionDef(_, _, _, stmt) => succHelper(stmt, ctx, curctx, curres, env)

      // EXIT element
      case t@ReturnStatement(_) => {
        findPriorASTElem[FunctionDef](t, env) match {
          case None => assert(false, "return statement should always occur within a function statement"); List()
          case Some(f) => curres ++ List((env.featureExpr(f), f))
        }
      }

      case t@CompoundStatement(l) => getCompoundSucc(l, t, ctx, curctx, curres, env)

      case o: Opt[_] => succHelper(o.entry.asInstanceOf[Product], ctx, curctx, curres, env)
      case t: Conditional[_] => succHelper(childAST(t), ctx, curctx, curres, env)

      // loop statements
      case ForStatement(None, Some(expr2), None, One(EmptyStatement())) => getExprSucc(expr2, ctx, curctx, curres, env)
      case ForStatement(None, Some(expr2), None, One(CompoundStatement(List()))) => getExprSucc(expr2, ctx, curctx, curres, env)
      case t@ForStatement(expr1, expr2, expr3, s) => {
        if (expr1.isDefined) getExprSucc(expr1.get, ctx, curctx, curres, env)
        else if (expr2.isDefined) getExprSucc(expr2.get, ctx, curctx, curres, env)
        else getCondStmtSucc(t, s, ctx, curctx, curres, env)
      }
      case WhileStatement(expr, One(EmptyStatement())) => getExprSucc(expr, ctx, curctx, curres, env)
      case WhileStatement(expr, One(CompoundStatement(List()))) => getExprSucc(expr, ctx, curctx, curres, env)
      case WhileStatement(expr, _) => getExprSucc(expr, ctx, curctx, curres, env)
      case DoStatement(expr, One(CompoundStatement(List()))) => getExprSucc(expr, ctx, curctx, curres, env)
      case t@DoStatement(_, s) => getCondStmtSucc(t, s, ctx, curctx, curres, env)

      // conditional statements
      case t@IfStatement(condition, _, _, _) => getCondExprSucc(condition, ctx, curctx, curres, env)
      case t@ElifStatement(condition, _) => getCondExprSucc(condition, ctx, curctx, curres, env)
      case SwitchStatement(expr, _) => getExprSucc(expr, ctx, curctx, curres, env)

      case t@BreakStatement() => {
        val e2b = findPriorASTElem2BreakStatement(t, env)
        assert(e2b.isDefined, "break statement should always occur within a for, do-while, while, or switch statement")
        getStmtSucc(e2b.get, ctx, env.featureExpr(e2b.get), curres, env)
      }
      case t@ContinueStatement() => {
        val e2c = findPriorASTElem2ContinueStatement(t, env)
        assert(e2c.isDefined, "continue statement should always occur within a for, do-while, or while statement")
        e2c.get match {
          case t@ForStatement(_, expr2, expr3, s) => {
            if (expr3.isDefined) getExprSucc(expr3.get, ctx, curctx, curres, env)
            else if (expr2.isDefined) getExprSucc(expr2.get, ctx, curctx, curres, env)
            else getCondStmtSucc(t, s, ctx, curctx, curres, env)
          }
          case WhileStatement(expr, _) => getExprSucc(expr, ctx, curctx, curres, env)
          case DoStatement(expr, _) => getExprSucc(expr, ctx, curctx, curres, env)
          case _ => List()
        }
      }
      case t@GotoStatement(Id(l)) => {
        findPriorASTElem[FunctionDef](t, env) match {
          case None => assert(false, "goto statement should always occur within a function definition"); List()
          case Some(f) => {
            val l_list = filterAllASTElems[LabelStatement](f, env.featureExpr(t), env).filter(_.id.name == l)
            if (l_list.isEmpty) getStmtSucc(t, ctx, curctx, curres, env)
            else l_list.map(x => (env.featureExpr(x), x))
          }
        }
      }
      // in case we have an indirect goto dispatch all goto statements
      // within the function (this is our invariant) are possible targets of this goto
      // so fetch the function statement and filter for all label statements
      case t@GotoStatement(PointerDerefExpr(_)) => {
        findPriorASTElem[FunctionDef](t, env) match {
          case None => assert(false, "goto statement should always occur within a function definition"); List()
          case Some(f) => {
            val l_list = filterAllASTElems[LabelStatement](f, env.featureExpr(t))
            if (l_list.isEmpty) getStmtSucc(t, ctx, curctx, curres, env)
            else l_list.map(x => (env.featureExpr(x), x))
          }
        }
      }

      case t: CaseStatement => getStmtSucc(t, ctx, curctx, curres, env)

      case t@DefaultStatement(Some(s)) => getCondStmtSucc(t, s, ctx, curctx, curres, env)
      case t: DefaultStatement => getStmtSucc(t, ctx, curctx, curres, env)

      case t: Statement => getStmtSucc(t, ctx, curctx, curres, env)
      case t => followSucc(t, ctx, curctx, curres, env)
    }
  }

  private def getCondStmtSucc(p: AST, c: Conditional[_], ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {
    c match {
      case Choice(_, thenBranch, elseBranch) => {
        var res = curres
        res = getCondStmtSucc(p, thenBranch, ctx, curctx, res, env)
        res = getCondStmtSucc(p, elseBranch, ctx, curctx, res, env)
        res
      }
      case One(CompoundStatement(l)) => getCompoundSucc(l, c, ctx, curctx, curres, env)
      case One(s: Statement) => curres ++ List((env.featureExpr(s), s))
    }
  }

  private def getCondStmtPred(p: AST, c: Conditional[_], ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {
    c match {
      case Choice(_, thenBranch, elseBranch) => {
        val res = getCondStmtPred(p, thenBranch, ctx, curctx, curres, env)
        getCondStmtPred(p, elseBranch, ctx, curctx, res, env)
      }
      case o@One(CompoundStatement(l)) => getCompoundPred(l, o, ctx, curctx, curres, env)
      case One(s: Statement) => curres ++ List((env.featureExpr(s), s))
    }
  }

  private def getExprSucc(e: Expr, ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {
    e match {
      case c@CompoundStatementExpr(CompoundStatement(innerStatements)) =>
        getCompoundSucc(innerStatements, c, ctx, curctx, curres, env)
      case _ => List((env.featureExpr(e), e))
    }
  }

  private def getCondExprSucc(cexp: Conditional[Expr], ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {
    cexp match {
      case One(value) => getExprSucc(value, ctx, curctx, curres, env)
      case Choice(_, thenBranch, elseBranch) => {
        getCondExprSucc(thenBranch, ctx, env.featureExpr(thenBranch), curres, env) ++
          getCondExprSucc(elseBranch, ctx, env.featureExpr(elseBranch), curres, env)
      }
    }
  }

  private def getExprPred(exp: Expr, ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {
    exp match {
      case t@CompoundStatementExpr(CompoundStatement(innerStatements)) => getCompoundPred(innerStatements, t, ctx, curctx, curres, env)
      case _ => {
        val expfexp = env.featureExpr(exp)
        if (expfexp implies curres.map(_._1).fold(FeatureExprFactory.False)(_ or _) isTautology()) curres
        else curres ++ List((expfexp, exp))
      }
    }
  }

  private def getCondExprPred(cexp: Conditional[Expr], ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {
    cexp match {
      case One(value) => getExprPred(value, ctx, curctx, curres, env)
      case Choice(_, thenBranch, elseBranch) => {
        getCondExprPred(thenBranch, ctx, env.featureExpr(thenBranch), curres, env) ++
          getCondExprPred(elseBranch, ctx, env.featureExpr(elseBranch), curres, env)
      }
    }
  }

  // handling of successor determination of nested structures, such as for, while, ... and next element in a list
  // of statements
  private def followSucc(nested_ast_elem: Product, ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {
    nested_ast_elem match {
      case t: ReturnStatement => {
        findPriorASTElem[FunctionDef](t, env) match {
          case None => assert(false, "return statement should always occur within a function statement"); List()
          case Some(f) => List((env.featureExpr(f), f))
        }
      }
      case _ => {
        val surrounding_parent = parentAST(nested_ast_elem, env)
        surrounding_parent match {
          // loops
          case t@ForStatement(Some(expr1), expr2, _, s) if (isPartOf(nested_ast_elem, expr1)) =>
            if (expr2.isDefined) getExprSucc(expr2.get, ctx, curctx, curres, env)
            else getCondStmtSucc(t, s, ctx, curctx, curres, env)
          case t@ForStatement(_, Some(expr2), _, s) if (isPartOf(nested_ast_elem, expr2)) => {
            getStmtSucc(t, ctx, curctx, curres, env) ++ getCondStmtSucc(t, s, ctx, curctx, curres, env)
          }
          case t@ForStatement(_, expr2, Some(expr3), s) if (isPartOf(nested_ast_elem, expr3)) =>
            if (expr2.isDefined) getExprSucc(expr2.get, ctx, curctx, curres, env)
            else getCondStmtSucc(t, s, ctx, curctx, curres, env)
          case t@ForStatement(_, expr2, expr3, s) if (isPartOf(nested_ast_elem, s)) => {
            if (expr3.isDefined) getExprSucc(expr3.get, ctx, curctx, curres, env)
            else if (expr2.isDefined) getExprSucc(expr2.get, ctx, curctx, curres, env)
            else getCondStmtSucc(t, s, ctx, curctx, curres, env)
          }
          case t@WhileStatement(expr, s) if (isPartOf(nested_ast_elem, expr)) => {
            getCondStmtSucc(t, s, ctx, curctx, curres, env) ++ getStmtSucc(t, ctx, curctx, curres, env)
          }
          case WhileStatement(expr, s) => getExprSucc(expr, ctx, curctx, curres, env)
          case t@DoStatement(expr, s) if (isPartOf(nested_ast_elem, expr)) => {
            getCondStmtSucc(t, s, ctx, curctx, curres, env) ++ getStmtSucc(t, ctx, curctx, curres, env)
          }

          case DoStatement(expr, s) => getExprSucc(expr, ctx, curctx, curres, env)

          // conditional statements
          // we are in the condition of the if statement
          case t@IfStatement(condition, thenBranch, elifs, elseBranch) if (isPartOf(nested_ast_elem, condition)) => {
            var res = getCondStmtSucc(t, thenBranch, ctx, curctx, curres, env)
            if (!elifs.isEmpty) res ++= getCompoundSucc(elifs, t, ctx, curctx, curres, env)
            if (elifs.isEmpty && elseBranch.isDefined) res ++= getCondStmtSucc(t, elseBranch.get, ctx, curctx, curres, env)
            if (elifs.isEmpty && !elseBranch.isDefined) res ++= getStmtSucc(t, ctx, env.featureExpr(t), curres, env)
            res
          }

          // either go to next ElifStatement, ElseBranch, or next statement of the surrounding IfStatement
          // filtering is necessary, as else branches are not considered by getSuccSameLevel
          case t@ElifStatement(condition, thenBranch) if (isPartOf(nested_ast_elem, condition)) => {
            var res: CCFGRes = List()
            getElifSucc(t, ctx, env.featureExpr(t), curres, env) match {
              case Left(l)  => res ++= l
              case Right(l) => {
                res ++= l
                parentAST(t, env) match {
                  case tp@IfStatement(_, _, _, None) => res ++= getStmtSucc(tp, ctx, curctx, curres, env)
                  case IfStatement(_, _, _, Some(elseBranch)) => res ++= getCondStmtSucc(t, elseBranch, ctx, curctx, curres, env)
                }
              }
            }

            res ++ getCondStmtSucc(t, thenBranch, ctx, curctx, curres, env)
          }
          case t: ElifStatement => followSucc(t, ctx, curctx, curres, env)

          // the switch statement behaves like a dynamic goto statement;
          // based on the expression we jump to one of the case statements or default statements
          // after the jump the case/default statements do not matter anymore
          // when hitting a break statement, we jump to the end of the switch
          case t@SwitchStatement(expr, s) if (isPartOf(nested_ast_elem, expr)) => {
            var res: CCFGRes = List()
            if (isPartOf(nested_ast_elem, expr)) {
              res ++= filterCaseStatements(s, ctx, env.featureExpr(t), env).map(x => (env.featureExpr(x), x))
              val dcase = filterDefaultStatements(s, ctx, env.featureExpr(t), env)

              if (dcase.isEmpty) res ++= getStmtSucc(t, ctx, curctx, curres, env)
              else res ++= dcase.map(x => (env.featureExpr(x), x))
            }
            res
          }

          case t: Expr => followSucc(t, ctx, curctx, curres, env)
          case t: Statement => getStmtSucc(t, ctx, env.featureExpr(t), curres, env)

          case t: FunctionDef => curres ++ List((env.featureExpr(t), t))
          case _ => curres
        }
      }
    }
  }

  // method to catch surrounding ast element, which precedes the given nested_ast_element
  private def followPred(nested_ast_elem: Product, ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {

    def handleSwitch(t: AST, curres: CCFGRes) = {
      val prior_switch = findPriorASTElem[SwitchStatement](t, env)
      assert(prior_switch.isDefined, "default statement without surrounding switch")
      prior_switch.get match {
        case SwitchStatement(expr, _) => {
          var res = getExprPred(expr, ctx, curctx, curres, env)
          if (env.previous(t) != null) res ++ getStmtPred(t, ctx, curctx, curres, env)
          else {
            val tparent = parentAST(t, env)
            if (tparent.isInstanceOf[CaseStatement]) curres ++ List((env.featureExpr(tparent), tparent))  // TODO rewrite, nested cases.
            else getStmtPred(tparent, ctx, curctx, curres, env)
          }
        }
      }
    }

    nested_ast_elem match {

      // case or default statements belong only to switch statements
      case t: CaseStatement => handleSwitch(t, curres)
      case t: DefaultStatement => handleSwitch(t, curres)

      case _ => {
        val surrounding_parent = parentAST(nested_ast_elem, env)
        surrounding_parent match {

          // loop statements

          // for statements consists of of (init, break, inc, body)
          // we are in one of these elements
          // init
          case t@ForStatement(Some(expr1), _, _, _) if (isPartOf(nested_ast_elem, expr1)) =>
            getStmtPred(t, ctx, env.featureExpr(t), curres, env)
          // inc
          case t@ForStatement(_, _, Some(expr3), s) if (isPartOf(nested_ast_elem, expr3)) => {
            getCondStmtPred(t, s, ctx, curctx, curres, env) ++
              filterContinueStatements(s, ctx, env.featureExpr(t), env).map(x => (env.featureExpr(x), x))
          }
          // break
          case t@ForStatement(None, Some(expr2), None, One(CompoundStatement(List()))) => {
            getExprPred(expr2, ctx, curctx, curres, env) ++ getStmtPred(t, ctx, curctx, curres, env)
          }
          case t@ForStatement(expr1, Some(expr2), expr3, s) if (isPartOf(nested_ast_elem, expr2)) => {
            var res: CCFGRes = List()
            if (expr1.isDefined) res ++= getExprPred(expr1.get, ctx, curctx, curres, env)
            else res ++= getStmtPred(t, ctx, curctx, curres, env)
            if (expr3.isDefined) res ++= getExprPred(expr3.get, ctx, curctx, curres, env)
            else {
              res ++= getCondStmtPred(t, s, ctx, curctx, curres, env)
              res ++= filterContinueStatements(s, ctx, env.featureExpr(t), env).map(x => (env.featureExpr(x), x))
            }
            res
          }
          // s
          case t@ForStatement(expr1, expr2, expr3, s) if (isPartOf(nested_ast_elem, s)) =>
            if (expr2.isDefined) getExprPred(expr2.get, ctx, curctx, curres, env)
            else if (expr3.isDefined) getExprPred(expr3.get, ctx, curctx, curres, env)
            else {
              var res: CCFGRes = List()
              if (expr1.isDefined) res ++= getExprPred(expr1.get, ctx, curctx, curres, env)
              else res ++= getStmtPred(t, ctx, env.featureExpr(t), curres, env)
              res ++= getCondStmtPred(t, s, ctx, curctx, curres, env)
              res
            }

          // while statement consists of (expr, s)
          // special case; we handle empty compound statements here directly because otherwise we do not terminate
          case t@WhileStatement(expr, One(CompoundStatement(List()))) if (isPartOf(nested_ast_elem, expr)) => {
            getStmtPred(t, ctx, curctx, curres, env) ++
              getExprPred(expr, ctx, curctx, curres, env)
          }
          case t@WhileStatement(expr, s) if (isPartOf(nested_ast_elem, expr)) => {
            getStmtPred(t, ctx, curctx, curres, env) ++
              getCondStmtPred(t, s, ctx, curctx, curres, env) ++
              filterContinueStatements(s, ctx, env.featureExpr(t), env).map(x => (env.featureExpr(x), x))
          }
          case t@WhileStatement(expr, _) => {
            if (nested_ast_elem.eq(expr)) getStmtPred(t, ctx, env.featureExpr(t), curres, env)
            else getExprPred(expr, ctx, curctx, curres, env)
          }

          // do statement consists of (expr, s)
          // special case: we handle empty compound statements here directly because otherwise we do not terminate
          case t@DoStatement(expr, One(CompoundStatement(List()))) if (isPartOf(nested_ast_elem, expr)) => {
            getStmtPred(t, ctx, curctx, curres, env) ++
              getExprPred(expr, ctx, curctx, curres, env)
          }
          case t@DoStatement(expr, s) => {
            if (isPartOf(nested_ast_elem, expr)) {
              getCondStmtPred(t, s, ctx, curctx, curres, env) ++
                filterContinueStatements(s, ctx, env.featureExpr(t), env).map(x => (env.featureExpr(x), x))
            }
            else {
              getExprPred(expr, ctx, curctx, curres, env) ++
                getStmtPred(t, ctx, env.featureExpr(t), curres, env)
            }
          }

          // conditional statements
          // if statement: control flow comes either out of:
          // elseBranch: elifs + condition is the result
          // elifs: rest of elifs + condition
          // thenBranch: condition
          case t@IfStatement(condition, thenBranch, elifs, elseBranch) => {
            if (isPartOf(nested_ast_elem, condition)) getStmtPred(t, ctx, env.featureExpr(t), curres, env)
            else if (isPartOf(nested_ast_elem, thenBranch)) getCondExprPred(condition, ctx, curctx, curres, env)
            else if (isPartOf(nested_ast_elem, elseBranch)) {
              if (elifs.isEmpty) getCondExprPred(condition, ctx, curctx, curres, env)
              else {
                getCompoundPred(elifs, t, ctx, curctx, curres, env).flatMap({
                  case (_, ElifStatement(elif_condition, _)) =>
                    getCondExprPred(elif_condition, ctx, curctx, curres, env)
                  case x => List(x)
                })
              }
            } else {
              getStmtPred(nested_ast_elem.asInstanceOf[AST], ctx, curctx, curres, env)
            }
          }

          // pred of thenBranch is the condition itself
          // and if we are in condition, we strike for a previous elifstatement or the if itself using
          // getPredSameLevel
          case t@ElifStatement(condition, thenBranch) => {
            if (isPartOf(nested_ast_elem, condition)) predElifStatement(t, ctx, curctx, curres, env)
            else getCondExprPred(condition, ctx, curctx, curres, env)
          }

          case SwitchStatement(expr, s) if (isPartOf(nested_ast_elem, s)) => getExprPred(expr, ctx, curctx, curres, env)
          case t: CaseStatement => curres ++ List((env.featureExpr(t), t))

          // pred of default is either the expression of the switch, which is
          // returned by handleSwitch, or a previous statement (e.g.,
          // switch (exp) {
          // ...
          // label1:
          // default: ...)
          // as part of a fall through (sequence of statements without a break and that we catch
          // with getStmtPred
          case t: DefaultStatement => {
            getStmtPred(t, ctx, curctx, curres, env) ++ handleSwitch(t, curres)
          }

          case t: CompoundStatementExpr => followPred(t, ctx, curctx, curres, env)
          case t: Statement => getStmtPred(t, ctx, curctx, curres, env)
          case t: FunctionDef => {
            val ffexp = env.featureExpr(t)
            if (ffexp implies curres.map(_._1).fold(FeatureExprFactory.False)(_ or _) isTautology()) List()
            else List((env.featureExpr(t), t))
          }
          case _ => List()
        }
      }
    }
  }

  private def predElifStatement(a: ElifStatement, ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {
    val surrounding_if = parentAST(a, env)
    surrounding_if match {
      case IfStatement(condition, thenBranch, elifs, elseBranch) => {
        val prev_elifs = elifs.reverse.dropWhile(_.entry.eq(a.asInstanceOf[AnyRef]).unary_!).drop(1)
        val ifdef_blocks = determineIfdefBlocks(prev_elifs, env)

        determineFollowingElementsPred(ctx, curres.map(_._1), ifdef_blocks, env) match {
          case Left(res) => res
          case Right(res) => getCondExprPred(condition, ctx, curctx, res, env)
        }
      }
      case _ => curres
    }
  }

  // method to find a prior loop statement that belongs to a given break statement
  private def findPriorASTElem2BreakStatement(a: Product, env: ASTEnv): Option[AST] = {
    val aparent = env.parent(a)
    aparent match {
      case t: ForStatement => Some(t)
      case t: WhileStatement => Some(t)
      case t: DoStatement => Some(t)
      case t: SwitchStatement => Some(t)
      case null => None
      case p: Product => findPriorASTElem2BreakStatement(p, env)
    }
  }

  // method to find prior element to a continue statement
  private def findPriorASTElem2ContinueStatement(a: Product, env: ASTEnv): Option[AST] = {
    val aparent = env.parent(a)
    aparent match {
      case t: ForStatement => Some(t)
      case t: WhileStatement => Some(t)
      case t: DoStatement => Some(t)
      case null => None
      case p: Product => findPriorASTElem2ContinueStatement(p, env)
    }
  }

  // we have to check possible successor nodes in at max three steps:
  // 1. get direct successors with same annotation; if yes stop; if not go to step 2.
  // 2. get all annotated elements at the same level and check whether we find a definite set of successor nodes
  //    if yes stop; if not go to step 3.
  // 3. get the parent of our node and determine successor nodes of it
  private def getStmtSucc(s: AST, ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {

    // check whether next statement has the same annotation if yes return it, if not
    // check the following ifdef blocks; 1.
    val snext = nextAST(s, env)
    if (snext != null && (env.featureExpr(snext) equivalentTo curctx)) curres ++ List((env.featureExpr(snext), snext))
    else {
      val snexts = nextASTElems(s, env)
      val ifdefblocks = determineIfdefBlocks(snexts, env)
      val taillist = getTailListSucc(s, ifdefblocks)
      determineFollowingElementsSucc(ctx, curres.map(_._1), taillist.drop(1), env) match {
        case Left(res) => res // 2.
        case Right(res) => curres ++ followSucc(s, ctx, curctx, res, env) // 3.
      }
    }
  }

  // specialized version of getStmtSucc for ElifStatements
  private def getElifSucc(s: ElifStatement, ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv):
      Either[CCFGRes, CCFGRes] = {

    val snext = nextAST(s, env)
    if (snext != null && (env.featureExpr(snext) equivalentTo curctx)) Left(curres ++ List((env.featureExpr(snext), snext)))
    else {
      val snexts = nextASTElems(s, env)
      val ifdefblocks = determineIfdefBlocks(snexts, env)
      val taillist = getTailListSucc(s, ifdefblocks)
      determineFollowingElementsSucc(ctx, curres.map(_._1), taillist.drop(1), env)
    }
  }

  // this method filters BreakStatements
  // a break belongs to next outer loop (for, while, do-while)
  // or a switch statement (see [2])
  // use this method with the loop or switch body!
  // so we recursively go over the structure of the ast elems
  // in case we find a break, we add it to the result list
  // in case we hit another loop or switch we return the empty list
  private def filterBreakStatements(c: Conditional[Statement], ctx: FeatureExpr, curctx: FeatureExpr, env: ASTEnv): List[BreakStatement] = {
    def filterBreakStatementsHelper(a: Any): List[BreakStatement] = {
      a match {
        case t: BreakStatement => if (env.featureExpr(t) implies ctx isTautology()) List(t) else List()
        case _: SwitchStatement => List()
        case _: ForStatement => List()
        case _: WhileStatement => List()
        case _: DoStatement => List()
        case l: List[_] => l.flatMap(filterBreakStatementsHelper(_))
        case x: Product => x.productIterator.toList.flatMap(filterBreakStatementsHelper(_))
        case _ => List()
      }
    }
    filterBreakStatementsHelper(c)
  }

  // this method filters ContinueStatements
  // according to [2]: A continue statement shall appear only in or as a
  // loop body
  // use this method only with the loop body!
  private def filterContinueStatements(c: Conditional[Statement], ctx: FeatureExpr, curctx: FeatureExpr, env: ASTEnv): List[ContinueStatement] = {
    def filterContinueStatementsHelper(a: Any): List[ContinueStatement] = {
      a match {
        case t: ContinueStatement => if (env.featureExpr(t) implies ctx isTautology()) List(t) else List()
        case _: ForStatement => List()
        case _: WhileStatement => List()
        case _: DoStatement => List()
        case l: List[_] => l.flatMap(filterContinueStatementsHelper(_))
        case x: Product => x.productIterator.toList.flatMap(filterContinueStatementsHelper(_))
        case _ => List()
      }
    }
    filterContinueStatementsHelper(c)
  }

  // this method filters all CaseStatements
  private def filterCaseStatements(c: Conditional[Statement], ctx: FeatureExpr, curctx: FeatureExpr, env: ASTEnv): List[CaseStatement] = {
    def filterCaseStatementsHelper(a: Any): List[CaseStatement] = {
      a match {
        case t@CaseStatement(_) =>
          if (env.featureExpr(t) implies ctx isSatisfiable()) List(t) else List()
        case _: SwitchStatement => List()
        case l: List[_] => l.flatMap(filterCaseStatementsHelper(_))
        case x: Product => x.productIterator.toList.flatMap(filterCaseStatementsHelper(_))
        case _ => List()
      }
    }
    filterCaseStatementsHelper(c)
  }

  // although the standard says that a case statement only has one default statement
  // we may have differently annotated default statements
  private def filterDefaultStatements(c: Conditional[Statement], ctx: FeatureExpr, curctx: FeatureExpr, env: ASTEnv): List[DefaultStatement] = {
    def filterDefaultStatementsHelper(a: Any): List[DefaultStatement] = {
      a match {
        case _: SwitchStatement => List()
        case t: DefaultStatement => if (env.featureExpr(t) implies ctx isSatisfiable()) List(t) else List()
        case l: List[_] => l.flatMap(filterDefaultStatementsHelper(_))
        case x: Product => x.productIterator.toList.flatMap(filterDefaultStatementsHelper(_))
        case _ => List()
      }
    }
    filterDefaultStatementsHelper(c)
  }

  // in predecessor determination we have to dig in into elements at certain points
  // we dig into ast that have an Conditional part, such as for, while, ...
  // source is the element that we compute the predecessor for
  // target is the current determined predecessor that might be evaluated further
  // ctx stores the context of target element
  // env is the ast environment that stores references to parents, siblings, and children
  private def rollUp(source: Product, target: AST, ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {
    target match {

      // in general all elements from the different branches (thenBranch, elifs, elseBranch)
      // can be predecessors
      case t@IfStatement(condition, thenBranch, elifs, elseBranch) => {
        var res: CCFGRes = List()

        if (elseBranch.isDefined) res ++= getCondStmtPred(t, elseBranch.get, ctx, curctx, curres, env)
        if (!elifs.isEmpty) {
          for (Opt(f, elif@ElifStatement(_, thenBranch)) <- elifs) {
            if (f.implies(ctx).isSatisfiable())
              res ++= getCondStmtPred(elif, thenBranch, ctx, env.featureExpr(elif), curres, env)
          }

          // without an else branch, the condition of elifs are possible predecessors of a
          if (elseBranch.isEmpty) res ++= getCompoundPred(elifs, t, ctx, curctx, curres, env)
        }
        res ++= getCondStmtPred(t, thenBranch, ctx, curctx, curres, env)

        if (elifs.isEmpty && elseBranch.isEmpty)
          res ++= getCondExprPred(condition, ctx, curctx, curres, env)
        res.flatMap({ x => rollUp(source, x._2, ctx, x._1, res.filterNot(td => td._1.eq(x._1)), env) })
      }
      case ElifStatement(condition, thenBranch) => {
        var res: CCFGRes = List()
        res ++= getCondExprPred(condition, ctx, curctx, curres, env)

        // check wether source is part of a possibly exising elsebranch;
        // if so we do not roll up the thenbranch
        findPriorASTElem[IfStatement](source, env) match {
          case None =>
          case Some(IfStatement(_, _, _, None)) => res ++= getCondStmtPred(target, thenBranch, ctx, curctx, curres, env)
          case Some(IfStatement(_, _, _, Some(x))) => if (! isPartOf(source, x))
            res ++= getCondStmtPred(target, thenBranch, ctx, curctx, curres, env)
        }

        res.flatMap({ x => rollUp(source, x._2, ctx, x._1, res.filterNot(td => td._1.eq(x._1)), env) })
      }
      case t@SwitchStatement(expr, s) => {
        val lbreaks = filterBreakStatements(s, ctx, env.featureExpr(t), env)
        lazy val ldefaults = filterDefaultStatements(s, ctx, env.featureExpr(t), env)

        // if no break and default statement is there, possible predecessors are the expr of the switch itself
        // and the code after the last case
        if (lbreaks.isEmpty && ldefaults.isEmpty) {
          var res = getExprPred(expr, ctx, curctx, curres, env)
          getCondStmtPred(t, s, ctx, curctx, res, env)
        }
        else if (ldefaults.isEmpty) {
          getExprPred(expr, ctx, curctx, curres, env) ++
          lbreaks.map(x => (env.featureExpr(x), x))
        }
        else lbreaks.map(x => (env.featureExpr(x), x)) ++
          ldefaults.flatMap({ x => rollUpJumpStatement(x, true, ctx, env.featureExpr(x), curres, env) })
      }

      case t@WhileStatement(expr, s) => {
        getExprPred(expr, ctx, curctx, curres, env) ++
          filterBreakStatements(s, ctx, env.featureExpr(t), env).map(x => (env.featureExpr(x), x))
      }
      case t@DoStatement(expr, s) => {
        getExprPred(expr, ctx, curctx, curres, env) ++
          filterBreakStatements(s, ctx, env.featureExpr(t), env).map(x => (env.featureExpr(x), x))
      }
      case t@ForStatement(_, Some(expr2), _, s) => {
        getExprPred(expr2, ctx, curctx, curres, env) ++
          filterBreakStatements(s, ctx, env.featureExpr(t), env).map(x => (env.featureExpr(x), x))
      }
      case t@ForStatement(_, _, _, s) => curres ++ filterBreakStatements(s, ctx, env.featureExpr(t), env).map(x => (env.featureExpr(x), x))

      case c@CompoundStatement(innerStatements) => {
        val res = getCompoundPred(innerStatements, c, ctx, curctx, curres, env)
        val newres = res.flatMap({ x => rollUp(source, x._2, ctx, x._1, res.filterNot(td => td._1.eq(x._1)), env) })
        newres
      }

      case t@GotoStatement(PointerDerefExpr(_)) => {
        if (source.isInstanceOf[LabelStatement]) curres ++ List((env.featureExpr(target), target))
        else {
          findPriorASTElem[FunctionDef](t, env) match {
            case None => assert(false, "goto statement should always occur within a function definition"); curres
            case Some(f) => {
              val l_list = filterAllASTElems[LabelStatement](f, env.featureExpr(t))
              if (l_list.isEmpty) curres ++ List((env.featureExpr(target), target))
              else curres
            }
          }
        }
      }

      case _ => List((env.featureExpr(target), target))
    }
  }

  // we have a separate rollUp function for CaseStatement, DefaultStatement, and BreakStatement
  // because using rollUp in pred determination (see above) will return wrong results
  private def rollUpJumpStatement(a: AST, fromSwitch: Boolean, ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {
    a match {
      // the code that belongs to the jump target default is either reachable via nextAST from the
      // default statement: this first case statement here
      // or the code is nested in the DefaultStatement, so we match it with the next case statement
      case t@DefaultStatement(_) if (nextAST(t, env) != null && fromSwitch) => {
        val dparent = findPriorASTElem[CompoundStatement](t, env)
        assert(dparent.isDefined, "default statement always occurs in a compound statement of a switch")
        dparent.get match {
          case c@CompoundStatement(innerStatements) => getCompoundPred(innerStatements, c, ctx, curctx, curres, env)
        }
      }
      case t@DefaultStatement(Some(s)) => getCondStmtPred(t, s, ctx, curctx, curres, env).
        flatMap({ x => rollUpJumpStatement(x._2, false, ctx, x._1, curres.filterNot(td => td._2.eq(x)), env) })
      case _: BreakStatement => List()
      case _ => List((env.featureExpr(a), a))
    }
  }

  // we have to check possible predecessor nodes in at max three steps:
  // 1. get direct predecessor with same annotation; if yes stop; if not go to step 2.
  // 2. get all annotated elements at the same level and check whether we find a definite set of predecessor nodes
  //    if yes stop; if not go to step 3.
  // 3. get the parent of our node and determine predecessor nodes of it
  private def getStmtPred(s: AST, ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {

    // 1.
    val sprev = prevAST(s, env)
    if (sprev != null && (env.featureExpr(sprev) equivalentTo curctx)) {
      sprev match {
        case BreakStatement() => List()
        case a => List(a).flatMap({ x => rollUpJumpStatement(x, false, ctx, env.featureExpr(x), curres, env) })
      }
    } else {
      val sprevs = prevASTElems(s, env)
      val ifdefblocks = determineIfdefBlocks(sprevs, env)
      val taillist = getTailListPred(s, ifdefblocks)
      val taillistreversed = taillist.map(_.reverse).reverse

      determineFollowingElementsPred(ctx, curres.map(_._1), taillistreversed.drop(1), env) match {
        case Left(res) => res.flatMap({ x => rollUpJumpStatement(x._2, false, ctx, x._1, res, env)}) // 2.
        case Right(res) => {
          res.flatMap({ x => rollUpJumpStatement(x._2, false, ctx, x._1, res, env)}) ++
            followPred(s, ctx, curctx, res, env) // 3.
        }
      }
    }
  }

  // given a list of AST elements, determine successor AST elements based on feature expressions
  private def getCompoundSucc(l: List[AST], parent: Product, ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {
    val ifdefblocks = determineIfdefBlocks(l, env)

    determineFollowingElementsSucc(ctx, curres.map(_._1), ifdefblocks, env) match {
      case Left(res) => res
      case Right(res) => curres ++ (if (l.isEmpty) followSucc(parent, ctx, curctx, res, env)
                                    else followSucc(l.head, ctx, curctx, res, env))
    }
  }

  // given a list of AST elements, determine predecessor AST elements based on feature expressions
  private def getCompoundPred(l: List[AST], parent: Product, ctx: FeatureExpr, curctx: FeatureExpr, curres: CCFGRes, env: ASTEnv): CCFGRes = {
    val ifdefblocks = determineIfdefBlocks(l, env)
    val ifdefblocksreverse = ifdefblocks.map(_.reverse).reverse

    determineFollowingElementsPred(ctx, curres.map(_._1), ifdefblocksreverse, env) match {
      case Left(res) => res
      case Right(res) => curres ++ (if (l.isEmpty) followPred(parent, ctx, curctx, res, env)
                                    else followPred(l.reverse.head, ctx, curctx, res, env))
    }
  }

  // get list with rev and all following lists
  private def getTailListSucc(rev: AST, l: List[IfdefBlock]): List[IfdefBlock] = {
    // iterate each sublist of the incoming tuples TypedOptAltBlock combine equality check
    // and drop elements in which s occurs

    def contains(ifdefbl: IfdefBlock): Boolean = {
      ifdefbl.exists( x => x.eq(rev) )
    }

    l.dropWhile(x => contains(x).unary_!)
  }

  // same as getTailListSucc but with reversed input
  // result list ist reversed again so we have a list of TypedOptBlocks with the last
  // block containing rev
  private def getTailListPred(rev: AST, l: List[IfdefBlock]): List[IfdefBlock] = {
    getTailListSucc(rev, l.reverse).reverse
  }

  private def determineFollowingElementsPred(ctx: FeatureExpr,
                                             curres: List[FeatureExpr],
                                             l: List[IfdefBlock],
                                             env: ASTEnv): Either[CCFGRes, CCFGRes] = {
    // context of all added AST nodes that have been added to res
    var res: CCFGRes = List()
    var curresctx = curres

    for (ifdefblock <- l) {
      // get the first element of the ifdef block and check
      val head = ifdefblock.head
      val bfexp = env.featureExpr(head)

      // annotation of the block contradicts with context; do nothing
      if ((ctx and bfexp) isContradiction()) { }

      // nodes of annotations that have been added before: e.g., ctx is true; A B A true
      // the second A should not be added again because if A is selected the first A would have been selected
      // and not the second one
      else if (bfexp implies curresctx.fold(FeatureExprFactory.False)(_ or _) isTautology()) { }

      // otherwise add element and update resulting context
      else {res = res ++ List((bfexp, head)); curresctx ::= bfexp}

      if (curresctx.fold(FeatureExprFactory.False)(_ or _) equivalentTo ctx) return Left(res)
      if (bfexp equivalentTo ctx) return Left(res)
      if (bfexp isTautology()) return Left(res)
    }
    Right(res)
  }

  // code works both for succ and pred determination
  // based on the type of the IfdefBlocks (True(0), Optional (1), Alternative (2))
  // the function computes the following elements
  //   context - represents of the element we come frome
  //   l - list of grouped/typed ifdef blocks
  //   env - hold AST environment (parent, children, next, ...)
  private def determineFollowingElementsSucc(ctx: FeatureExpr,
                                             curres: List[FeatureExpr],
                                             l: List[IfdefBlock],
                                             env: ASTEnv): Either[CCFGRes, CCFGRes] = {
    // context of all added AST nodes that have been added to res
    var res: CCFGRes = List()
    var curresctx = curres

    for (ifdefblock <- l) {
      // get the first element of the ifdef block and check
      val head = ifdefblock.head
      val bfexp = env.featureExpr(head)

      // annotation of the block contradicts with context; do nothing
      if ((ctx and bfexp) isContradiction()) { }

      // nodes of annotations that have been added before: e.g., ctx is true; A B A true
      // the second A should not be added again because if A is selected the first A would have been selected
      // and not the second one
      else if (curresctx.fold(FeatureExprFactory.False)(_ or _) equivalentTo bfexp) { }

      // otherwise add element and update resulting context
      else {res = res ++ List((bfexp, head)); curresctx ::= bfexp}

      if (ctx implies curresctx.fold(FeatureExprFactory.False)(_ or _) isTautology()) return Left(res)
    }
    Right(res)
  }

  // determine recursively all succs check
  def getAllSucc(i: AST, env: ASTEnv) = {
    var r = List[(AST, List[AST])]()
    var s = List(i)
    var d = List[AST]()
    var c: AST = null

    while (!s.isEmpty) {
      c = s.head
      s = s.drop(1)

      if (d.filter(_.eq(c)).isEmpty) {
        r = (c, succ(c, env)) :: r
        s = s ++ r.head._2
        d = d ++ List(c)
      }
    }
    r
  }

  // determine recursively all pred
  def getAllPred(i: AST, env: ASTEnv) = {
    var r = List[(AST, List[AST])]()
    var s = List(i)
    var d = List[AST]()
    var c: AST = null

    while (!s.isEmpty) {
      c = s.head
      s = s.drop(1)

      if (d.filter(_.eq(c)).isEmpty) {
        r = (c, pred(c, env)) :: r
        s = s ++ r.head._2
        d = d ++ List(c)
      }
    }
    r
  }

  // given an ast element x and its successors lx: x should be in pred(lx)
  def compareSuccWithPred(lsuccs: List[(AST, List[AST])], lpreds: List[(AST, List[AST])], env: ASTEnv): List[CCFGError] = {
    var errors: List[CCFGError] = List()

    // check that number of nodes match
    val sdiff = lsuccs.map(_._1).diff(lpreds.map(_._1))
    val pdiff = lpreds.map(_._1).diff(lsuccs.map(_._1))

    for (sdelem <- sdiff)
      errors = new CCFGErrorMis("is not present in preds!", sdelem, env.featureExpr(sdelem)) :: errors


    for (pdelem <- pdiff)
      errors = new CCFGErrorMis("is not present in succs!", pdelem, env.featureExpr(pdelem)) :: errors

    // check that number of edges match
    var succ_edges: List[(AST, AST)] = List()
    for ((ast_elem, succs) <- lsuccs) {
      for (succ <- succs) {
        succ_edges = (ast_elem, succ) :: succ_edges
      }
    }

    var pred_edges: List[(AST, AST)] = List()
    for ((ast_elem, preds) <- lpreds) {
      for (pred <- preds) {
        pred_edges = (ast_elem, pred) :: pred_edges
      }
    }

    // check succ/pred connection and print out missing connections
    // given two ast elems:
    //   a
    //   b
    // we check (a1, b1) successor
    // against  (b2, a2) predecessor
    for ((a1, b1) <- succ_edges) {
      var isin = false
      for ((b2, a2) <- pred_edges) {
        if (a1.eq(a2) && b1.eq(b2))
          isin = true
      }
      if (!isin) {
        errors = new CCFGErrorDir("is missing in preds", b1, env.featureExpr(b1), a1, env.featureExpr(a1)) :: errors
      }
    }

    // check pred/succ connection and print out missing connections
    // given two ast elems:
    //  a
    //  b
    // we check (b1, a1) predecessor
    // against  (a2, b2) successor
    for ((b1, a1) <- pred_edges) {
      var isin = false
      for ((a2, b2) <- succ_edges) {
        if (a1.eq(a2) && b1.eq(b2))
          isin = true
      }
      if (!isin) {
        errors = new CCFGErrorDir("is missing in succs", a1, env.featureExpr(a1), b1, env.featureExpr(b1)) :: errors
      }
    }

    errors
  }

  // given a comparison function f: pack consecutive elements of list elements into sublists
  private def pack[T](f: (T, T) => Boolean)(l: List[T]): List[List[T]] = {
    if (l.isEmpty) List()
    else (l.head :: l.tail.takeWhile(f(l.head, _))) :: pack[T](f)(l.tail.dropWhile(f(l.head, _)))
  }

  // given a list of Opt elements; pack elements with the same annotation into sublists
  private def determineIfdefBlocks(l: List[AST], env: ASTEnv): List[IfdefBlock] = {
    pack[AST](env.featureExpr(_) equivalentTo env.featureExpr(_))(l)
  }
}

