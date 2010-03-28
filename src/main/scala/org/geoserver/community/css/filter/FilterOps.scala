package org.geoserver.community.css.filter

import collection.jcl.Conversions._

import org.opengis.filter.{
  And,
  BinaryComparisonOperator,
  Filter,
  Not,
  Or,
  PropertyIsEqualTo,
  PropertyIsGreaterThanOrEqualTo,
  PropertyIsGreaterThan,
  PropertyIsLessThanOrEqualTo,
  PropertyIsLessThan,
  PropertyIsLike,
  PropertyIsNotEqualTo
}

import org.opengis.filter.expression.{Expression, Literal, PropertyName}

/**
 * The FilterOps trait provides some facilities for manipulating GeoAPI 
 * Filter objects, such as simplification.
 *
 * @author David Winslow <cdwinslow@gmail.com>
 */
trait FilterOps {
  val filters = org.geotools.factory.CommonFactoryFinder.getFilterFactory2(null)

  private implicit def comparisonOps(me: Comparable[AnyRef]) = new Ordered[AnyRef] {
    def compare(you: AnyRef) = me.compareTo(you)
  }

  private def conflicting(a: Filter, b: Filter) =
    constrain(a, b) == Filter.EXCLUDE

  private val constraintRules: PartialFunction[(Filter, Filter),Filter] = {
    case (a, b) if redundant(a, b) => a
    case (Filter.INCLUDE, f) => f
    case (BinOp(('=), lA, Lit(rA)), BinOp('=, lB, Lit(rB))) 
      if equivalent(lA, lB) && rA != rB
      => Filter.EXCLUDE
    case (BinOp(('= | '< | '<=), lA, Lit(rA)), BinOp('>, lB, Lit(rB))) 
      if equivalent(lA, lB) && rA <= rB
      => Filter.EXCLUDE
    case (BinOp(('= | '> | '>=), lA, Lit(rA)), BinOp('<, lB, Lit(rB))) 
      if equivalent(lA, lB) && rA >= rB
      => Filter.EXCLUDE
    case (filter@BinOp('<=, lA, Lit(rA)), BinOp('< | '<=, lB, Lit(rB)))
      if equivalent(lA, lB) && rA < rB
      => filter
    case (filter@BinOp('<, lA, Lit(rA)), BinOp('< | '<=, lB, Lit(rB)))
      if equivalent(lA, lB) && rA <= rB
      => filter
    case (filter@BinOp('>=, lA, Lit(rA)), BinOp('> | '>=, lB, Lit(rB)))
      if equivalent(lA, lB) && rA > rB
      => filter
    case (filter@BinOp('>, lA, Lit(rA)), BinOp('< | '>=, lB, Lit(rB)))
      if equivalent(lA, lB) && rA >= rB
      => filter
    case (a: Not, b) if equivalent(a.getFilter, b) =>
      Filter.EXCLUDE
    case (a: And, b) =>
      if (a.getChildren().exists(conflicting(_, b))) {
        Filter.EXCLUDE
      } else {
        val children = new java.util.ArrayList[Filter]
        children.addAll(a.getChildren())
        children.add(b)
        filters.and(children)
      }
    case (a: Or, b) =>
      val children = new java.util.ArrayList[Filter]
      for (child <- a.getChildren() if !conflicting(child, b)) {
        children.add(constrain(child, b))
      }
      children.size() match {
        case 0 => Filter.EXCLUDE
        case 1 => children.get(0)
        case _ => filters.or(children)
      }
  }

  private val redundancyRules: PartialFunction[(Filter, Filter), Boolean] = {
    case (a, b) if equivalent(a, b) => true
    case (Filter.EXCLUDE, _) => true
    case (_, Filter.INCLUDE) => true
    case (BinOp(opA, lA, Lit(rA)), BinOp(opB, lB, Lit(rB)))
      if (equivalent(lA, lB)) 
      => (opA, opB) match {
        case ('=, '>) => rA > rB
        case ('=, '>=) => rA >= rB
        case ('=, '<) => rA < rB
        case ('=, '<=) => rA <= rB
        case ('=, '=) => rA == rB
        case ('<, '<) => rA < rB
        case ('<, '<>) => rA <= rB
        case ('<=, '<) => rA < rB
        case ('<, '<=) => rA < rB
        case ('<=, '<=) => rA <= rB
        case ('>, '>) => rA > rB
        case ('>=, '>) => rA > rB
        case ('>, '>=) => rA > rB
        case ('>=, '>=) => rA >= rB
        case _ => false
      }
    case _ => false
  }

  def constrain(a: Filter, b: Filter): Filter = 
    if (constraintRules isDefinedAt (a, b)) {
      constraintRules((a, b)) 
    } else if (constraintRules isDefinedAt (b, a)) {
      constraintRules((b, a))
    } else {
      a
    }

  def redundant(a: Filter, b: Filter): Boolean =
    redundancyRules((negate(negate(a)), negate(negate(b))))

  def negate(x: Filter): Filter = x match {
    case BinOp(op, lhs, rhs) =>
      op match {
        case '=  => filters.notEqual(lhs, rhs)
        case '<> => filters.equals(lhs, rhs)
        case '<  => filters.greaterOrEqual(lhs, rhs)
        case '<= => filters.greater(lhs, rhs)
        case '>  => filters.lessOrEqual(lhs, rhs)
        case '>= => filters.less(lhs, rhs)
      }
    case (n: Not) => n.getFilter()
    case (a: And) => 
      val children = new java.util.ArrayList[Filter]
      for (c <- a.getChildren()) {
        children.add(negate(c))
      }
      children.size() match {
        case 0 => Filter.EXCLUDE
        case 1 => children.get(0)
        case _ => filters.or(children)
      }
    case (o: Or) => 
      val children = new java.util.ArrayList[Filter]
      for (c <- o.getChildren()) {
        children.add(negate(c))
      }
      children.size() match {
        case 0 => Filter.EXCLUDE
        case 1 => children.get(0)
        case _ => filters.and(children)
      }
    case _ => filters.not(x)
  }

  def equivalent(a: Filter, b: Filter): Boolean = {
    if (a.getClass != b.getClass) {
      false
    } else {
      (a, b) match {
        case (a: BinaryComparisonOperator, b: BinaryComparisonOperator) =>
          equivalent(a.getExpression1, b.getExpression1) && 
          equivalent(a.getExpression2, b.getExpression2)
        case (a: PropertyIsLike, b: PropertyIsLike) =>
          equivalent(a.getExpression, b.getExpression) &&
          a.getLiteral == b.getLiteral &&
          a.getEscape == b.getEscape &&
          a.getSingleChar == b.getSingleChar &&
          a.getWildCard == b.getWildCard &&
          a.isMatchingCase == b.isMatchingCase
        case (Filter.EXCLUDE, Filter.EXCLUDE) => true
        case (Filter.INCLUDE, Filter.INCLUDE) => true
        case _ => false
      }
    }
  }

  def equivalent(a: Expression, b: Expression): Boolean = (a, b) match {
    case (Lit(a), Lit(b)) if a == b => true
    case (Prop(a), Prop(b)) if a == b => true
    case _ => false
  }

  object BinOp {
    def unapply(f: Filter): Option[(Symbol, Expression, Expression)] =
      f match {
        case binop: BinaryComparisonOperator => 
          val symbol = binop match {
            case _: PropertyIsEqualTo => '=
            case _: PropertyIsGreaterThan => '>
            case _: PropertyIsGreaterThanOrEqualTo => '>=
            case _: PropertyIsLessThan => '<
            case _: PropertyIsLessThanOrEqualTo => '<=
            case _: PropertyIsNotEqualTo => '<>
          }
          Some((symbol, binop.getExpression1(), binop.getExpression2()))
        case _ => None
      }
  }

  object Not {
    def unapply(f: Filter): Option[Filter] = f match {
      case f: Not => Some(f.getFilter)
      case _ => None
    }
  }

  object Prop {
    def unapply(e: Expression): Option[String] = e match {
      case e: PropertyName => Some(e.getPropertyName)
      case _ => None
    }
  }

  object Lit {
    def unapply(e: Expression): Option[Comparable[_]] = e match {
      case e: Literal if e.getValue.isInstanceOf[Comparable[_]] => {
        Some(e.getValue.asInstanceOf[Comparable[_]])
      }
      case _ => None
    }
  }
}
