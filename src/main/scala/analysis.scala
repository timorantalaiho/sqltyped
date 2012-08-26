package sqltyped

object Analyzer {
  import Ast._

  def refine(stmt: TypedStatement): TypedStatement = 
    if (returnsMultipleResults(stmt)) stmt else stmt.copy(multipleResults = false)

  /**
   * Statement returns 0 - 1 results if,
   * 
   * - It is SQL insert, update or delete
   * - It has no joins and it contains only 'and' expressions in its where clause and at least one of
   *   those targets unique constraint with '=' operator
   * - It has LIMIT 1 clause
   */
  private def returnsMultipleResults(stmt: TypedStatement) = {
    def hasNoOrExprs(s: Select) = {
      s.where.map(w => !w.expr.find { case e: Or => true; case _ => false }.isDefined).getOrElse(false)
    }

    def inWhereClause(s: Select, cols: List[Column]) = {
      def inExpr(e: Expr, col: Column): Boolean = e match {
        // note, column comparision works since we only examine statements with one table
        case Predicate(Column(n, _, _), Eq, _) => col.name == n 
        case Predicate(_, Eq, Column(n, _, _)) => col.name == n
        case Predicate(_, _, _)                => false
        case And(e1, e2)                       => inExpr(e1, col) || inExpr(e2, col)
        case Or(e1, e2)                        => inExpr(e1, col) || inExpr(e2, col)
      }
      s.where.map(w => cols.map(col => inExpr(w.expr, col)).forall(identity)).getOrElse(false)
    }

    def hasLimit1(s: Select) = s.limit.map(_.count == 1) getOrElse false

    stmt.stmt match {
      case s: Select => 
        if ((s.from.length == 1 && s.from.head.join.length == 0 && 
             s.where.isDefined && hasNoOrExprs(s) && 
             stmt.uniqueConstraints(s.from.head.table).exists(c => inWhereClause(s, c))) || 
            hasLimit1(s))
          false
        else 
          true
      case _ => false
    }
  }
}