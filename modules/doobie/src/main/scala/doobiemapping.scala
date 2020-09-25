// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle
package doobie

import java.sql.ResultSet
import org.postgresql.util.PGobject
import scala.reflect.ClassTag
import scala.util.matching.Regex

import cats.effect.Sync
import cats.implicits._
import cats.kernel.Monoid
import _root_.doobie.{ ConnectionIO, Fragment, Fragments, Read }
import _root_.doobie.implicits._
import _root_.doobie.enum.Nullability._
import _root_.doobie.postgres.implicits._
import _root_.doobie.util.Put
import _root_.doobie.util.fragment.Elem.Arg
import _root_.doobie.util.meta.Meta
import _root_.doobie.util.transactor.Transactor
import io.chrisdavenport.log4cats.Logger
import io.circe.{ Encoder, Json }
import io.circe.parser._

import QueryCompiler._, ComponentElaborator.TrivialJoin
import DoobiePredicate._
import Predicate._
import Query._
import QueryInterpreter.mkErrorResult
import Row.FailedJoin
import ScalarType._
import circe.AbstractCirceMapping

trait DoobieMapping[F[_]] extends AbstractCirceMapping[Sync, F] {
  type Table = List[Row]

  val transactor: Transactor[F]
  val logger: Logger[F]

  import DoobieFieldMapping._

  override def rootMapping(tpe: Type, fieldName: String): Option[RootMapping] =
    if (tpe =:= schema.queryType) super.rootMapping(tpe, fieldName)
    else Some(DoobieRoot(fieldName, tpe))

  def doobieLeafMapping[T](tpe: Type): Option[DoobieLeafMapping[T]] =
    leafMapping[T](tpe).collectFirst {
      case dlm: DoobieLeafMapping[T] => dlm.asInstanceOf[DoobieLeafMapping[T]]
    }

  def typeToMeta(tpe: Type): (Meta[_], NullabilityKnown) = {
    def simpleTypeToMeta(tpe: Type): Meta[_] =
      doobieLeafMapping[Any](tpe).map(_.meta).getOrElse(
        (tpe: @unchecked) match {
          case IntType => Meta[Int]
          case FloatType => Meta[Double]
          case StringType => Meta[String]
          case BooleanType => Meta[Boolean]
          case IDType => Meta[String]
        }
      )

    def listMeta[T: ClassTag](implicit m: Meta[Array[T]]): Meta[List[T]] =
      m.imap(_.toList)(_.toArray)

    def listTypeToMeta(tpe: Type): Meta[_] =
      doobieLeafMapping[Any](ListType(tpe)).map(_.meta).getOrElse(
        (tpe: @unchecked) match {
          case IntType => listMeta[Int]
          case FloatType => listMeta[Double]
          case StringType => listMeta[String]
          case BooleanType => listMeta[Boolean]
          case IDType => listMeta[String]
          case NullableType(IntType) => listMeta[Option[Int]]
          case NullableType(FloatType) => listMeta[Option[Double]]
          case NullableType(StringType) => listMeta[Option[String]]
          case NullableType(BooleanType) => listMeta[Option[Boolean]]
          case NullableType(IDType) => listMeta[Option[String]]
        }
      )

    tpe match {
      case ListType(tpe) => (listTypeToMeta(tpe), NoNulls)
      case NullableType(ListType(tpe)) => (listTypeToMeta(tpe), Nullable)
      case NullableType(tpe) => (simpleTypeToMeta(tpe), Nullable)
      case tpe => (simpleTypeToMeta(tpe), NoNulls)
    }
  }

  def attributeMapping(tpe: RootedType, attrName: String): Option[DoobieAttribute] =
    fieldMapping(tpe, attrName) match {
      case Some(am: DoobieAttribute) => Some(am)
      case _ => None
    }

  def columnsForField(tpe: RootedType, fieldName: String): List[ColumnRef] = {
    val obj = tpe.underlyingObject
    fieldMapping(obj, fieldName) match {
      case Some(DoobieField(_, cr, _, _)) => List(cr)
      case Some(DoobieJson(_, cr)) => List(cr)
      case Some(DoobieObject(_, Subobject(joins, _))) => joins.map(_.parent) ++ joins.map(_.child)
      case _ => Nil
    }
  }

  def joinsForField(tpe: RootedType, fieldName: String): List[Join] = {
    val obj = tpe.underlyingObject
    fieldMapping(obj, fieldName) match {
      case Some(DoobieObject(_, Subobject(joins, _))) => joins
      case _ => Nil
    }
  }

  def columnForAttribute(tpe: RootedType, attrName: String): Option[ColumnRef] = {
    val obj = tpe.underlyingObject
    attributeMapping(obj, attrName) match {
      case Some(DoobieAttribute(_, cr, _, _, _, _)) => Some(cr)
      case _ => None
    }
  }

  def primaryColumnForField(tpe: RootedType, fieldName: String): Option[ColumnRef] = {
    val obj = tpe.underlyingObject
    fieldMapping(obj, fieldName) match {
      case Some(DoobieField(_, cr, _, _)) => Some(cr)
      case Some(DoobieJson(_, cr)) => Some(cr)
      case _ => None
    }
  }

  def primaryColumnForTerm[T](tpe: RootedType, term: Term[T]): Option[ColumnRef] =
    term match {
      case Const(_) => None
      case path: Path =>
        val obj = tpe.underlyingObject
        val prefix = path.path.init
        val parent = obj.path(prefix)
        val name = path.path.last
        if (isField(path))
          primaryColumnForField(parent, name)
        else
          columnForAttribute(parent, name)
    }

  def key(om: ObjectMapping): List[ColumnRef] =
    om.fieldMappings.collect {
      case cm: DoobieField if cm.key => cm.columnRef
      case am: DoobieAttribute if am.key => am.col
    }

  def keyColumnsForType(tpe: RootedType): List[ColumnRef] = {
    val obj = tpe.underlyingObject
    objectMapping(obj) match {
      case Some(om) => key(om)
      case _ => Nil
    }
  }

  def discriminator(om: ObjectMapping): List[ColumnRef] =
    om.fieldMappings.collect {
      case cm: DoobieField if cm.discriminator => cm.columnRef
      case am: DoobieAttribute if am.discriminator => am.col
    }

  def discriminatorColumnsForType(tpe: RootedType): List[ColumnRef] = {
    val obj = tpe.underlyingObject
    objectMapping(obj) match {
      case Some(om) => discriminator(om)
      case _ => Nil
    }
  }

  // This is partial, however, we should be able to perform a consistency check ahead of time
  // such that a valid query is guaranteed to be covered.
  def mapQuery(rootFieldName: String, q: Query, tpe: Type): MappedQuery = {
    type Acc = (List[ColumnRef], List[Join], List[(RootedType, Predicate)], List[(ObjectMapping, Type)])
    implicit object MAcc extends Monoid[Acc] {
      def combine(x: Acc, y: Acc): Acc =
        (x._1 ++ y._1, x._2 ++ y._2, x._3 ++ y._3, x._4 ++ y._4)

      def empty: Acc =  (Nil, Nil, Nil, Nil)
    }

    def loop(q: Query, tpe: RootedType, acc: Acc): Acc = {
      val obj = tpe.underlyingObject
      lazy val interfaces =
        obj.tpe.underlyingObject match {
          case ObjectType(_, _, _, interfaces) => interfaces.map(i => obj.copy(tpe = i))
          case _ => Nil
        }

      def requiredCols: List[ColumnRef] =
        discriminatorColumnsForType(obj) ++ interfaces.flatMap(discriminatorColumnsForType) ++ keyColumnsForType(obj)

      def requiredMappings: List[(ObjectMapping, Type)] =
        objectMapping(obj).map(om => (om, obj.tpe)).toList ++
        interfaces.flatMap(i => objectMapping(i).map(im => (im, i.tpe)).toList)

      def loopPredicate(pred: Predicate): Acc = {
        def loopPath(path: Path): Acc = {
          def mkSelects(path: List[String]): Query =
            path.foldRight(Empty: Query) { (fieldName, child) => Select(fieldName, Nil, child) }

          val prefix = path.path.init
          val parent = obj.path(prefix)
          val name = path.path.last

          if (isField(path)) {
            loop(mkSelects(path.path), obj, acc)
          } else {
            columnForAttribute(parent, name) match {
              case Some(pcol) =>
                (pcol :: requiredCols, List.empty[Join], List.empty[(RootedType, Predicate)], requiredMappings) |+| loop(mkSelects(prefix), obj, acc)
              case _ =>
                loop(mkSelects(prefix), obj, acc)
            }
          }
        }

        paths(pred).foldMap(loopPath) |+|
          ((List.empty[ColumnRef], List.empty[Join], List((tpe, pred)), List.empty[(ObjectMapping, Type)]))
      }

      q match {
        case Select(fieldName, _, child) =>
          val fieldTpe = obj.field(fieldName)
          val cols = columnsForField(obj, fieldName).toList ++ requiredCols
          val joins = joinsForField(obj, fieldName)

          loop(child, fieldTpe, (cols, joins, List.empty[(RootedType, Predicate)], requiredMappings) |+| acc)

        case Narrow(subtpe, child) =>
          loop(child, obj.copy(tpe = subtpe), acc)
        case Filter(pred, child) =>
          loop(child, obj, loopPredicate(pred) |+| acc)
        case Unique(pred, child) =>
          loop(child, obj, loopPredicate(pred) |+| acc)
        case Wrap(_, q) =>
          loop(q, obj, acc)
        case Rename(_, q) =>
          loop(q, obj, acc)
        case Group(queries) =>
          queries.foldLeft(acc) {
            case (acc, sibling) => loop(sibling, obj, acc)
          }
        case GroupList(queries) =>
          queries.foldLeft(acc) {
            case (acc, sibling) => loop(sibling, obj, acc)
          }
        case Limit(_, child) =>
          loop(child, obj, acc)
        case OrderBy(_, child) =>
          loop(child, obj, acc)

        case Empty | Query.Component(_, _, _) | (_: Introspect) | (_: Defer) | (_: UntypedNarrow) | (_: Skip) => acc
      }
    }

    val (columns0, joins0, predicates, mappings0) = loop(q, RootedType(List(rootFieldName), tpe), MAcc.empty)

    val columns = columns0.distinct
    val mappings = mappings0.toMap
    val joins = joins0.distinctBy(_.normalize)

    val tables = columns.map(_.table).distinct
    val childTables = joins.map(_.child.table).toSet
    val rootTable = tables.filterNot(childTables) match {
      case List(rt) => rt
      case otherwise => sys.error(s"Expected unique root table, found $otherwise")
    }

    val orderedJoins = {
      def orderJoins(seen: Set[String], joins: List[Join], acc: List[Join]): List[Join] = {
        if (joins.isEmpty) acc
        else {
          val (admissable, rest) = joins.partition(j => seen(j.parent.table))
          if (admissable.isEmpty) sys.error(s"unable to order joins $joins given $seen")
          val ats = admissable.map(_.child.table)
          orderJoins(seen ++ ats, rest, admissable ++ acc)
        }
      }

      orderJoins(Set(rootTable), joins, Nil).reverse
    }

    val jsonMeta: Meta[Json] =
      Meta.Advanced.other[PGobject]("json").timap[Json](
        a => parse(a.getValue).leftMap[Json](e => throw e).merge)(
        a => {
          val o = new PGobject
          o.setType("json")
          o.setValue(a.noSpaces)
          o
        }
      )

    val metas = {
      def getForColumn(col: ColumnRef): (Boolean, (Meta[_], NullabilityKnown)) = {
        // A column is the product of an outer join (and may therefore be null even if it's non-nullable
        // in the schema) if its table introduced on the child side of a `Join`.
        def isJoin(cr: ColumnRef): Boolean = childTables(cr.table)

        object Target {
          def unapply(om: ObjectMapping): Option[(Boolean, (Meta[_], NullabilityKnown))] = {
            om.fieldMappings.collectFirst {
              case DoobieField(fieldName, `col`, _, _) if mappings.contains(om) =>
                val obj = mappings(om)
                val fieldTpe0 = obj.field(fieldName)
                val fieldTpe =
                  if (obj.variantField(fieldName)) fieldTpe0.nullable
                  else fieldTpe0
                val meta = typeToMeta(fieldTpe)
                (isJoin(col), meta)
              case DoobieJson(fieldName, _) =>
                val nullable = mappings(om).field(fieldName).isNullable
                (false, (jsonMeta, if (nullable) Nullable else NoNulls))
              case DoobieAttribute(_, `col`, meta, _, nullable, _) =>
                (isJoin(col), (meta, if (nullable) Nullable else NoNulls))
            }
          }
        }

        (typeMappings.collectFirst {
          case Target(ij, get) => (ij, get)
        }).getOrElse(sys.error(s"No Get for $col"))
      }

      columns.map(getForColumn)
    }

    new MappedQuery(rootTable, columns, metas, predicates, orderedJoins)
  }

  case class DoobieRoot(fieldName: String, rootTpe: Type = NoType) extends RootMapping {
    def cursor(query: Query): F[Result[Cursor]] = {
      val fieldTpe = rootTpe.field(fieldName)
      val mapped = mapQuery(fieldName, query, fieldTpe)

      val cursorType = fieldTpe.list

      for {
        table <- logger.info(s"fetch(${mapped.fragment})") *> mapped.fetch.transact(transactor)
      } yield DoobieCursor(RootedType(List(fieldName), cursorType), table, mapped).rightIor
    }
    def withParent(tpe: Type): DoobieRoot =
      copy(rootTpe = tpe)
  }

  trait DoobieInterfaceMapping extends ObjectMapping {
    def discriminate(cursor: Cursor): Result[Type]
  }

  object DoobieInterfaceMapping {
    abstract case class DefaultInterfaceMapping(tpe: Type, fieldMappings: List[FieldMapping], path: List[String])
      extends DoobieInterfaceMapping with RootedObjectMapping

    val defaultDiscriminator: Cursor => Result[Type] = (cursor: Cursor) => cursor.tpe.rightIor

    def apply(tpe: Type, fieldMappings: List[FieldMapping], path: List[String] = Nil, discriminator: Cursor => Result[Type] = defaultDiscriminator): ObjectMapping =
      new DefaultInterfaceMapping(tpe, fieldMappings.map(_.withParent(tpe)), path) {
        def discriminate(cursor: Cursor): Result[Type] = discriminator(cursor)
      }
  }

  case class DoobieAttribute(
    fieldName: String,
    col: ColumnRef,
    meta: Meta[_],
    key: Boolean,
    nullable: Boolean,
    discriminator: Boolean
  ) extends FieldMapping {
    def withParent(tpe: Type): FieldMapping = this
  }

  def DoobieAttribute[T](
    fieldName: String,
    col: ColumnRef,
    key: Boolean = false,
    nullable: Boolean = false,
    discriminator: Boolean = false
  )(implicit meta: Meta[T]): DoobieAttribute =
    new DoobieAttribute(fieldName, col, meta, key, nullable, discriminator)

  sealed trait DoobieFieldMapping extends FieldMapping {
    def withParent(tpe: Type): FieldMapping = this
  }

  object DoobieFieldMapping {
    case class DoobieField(
      fieldName: String,
      columnRef: ColumnRef,
      key: Boolean = false,
      discriminator: Boolean = false
    ) extends DoobieFieldMapping
    case class DoobieObject(fieldName: String, subobject: Subobject) extends DoobieFieldMapping
    case class DoobieJson(fieldName: String, columnRef: ColumnRef) extends DoobieFieldMapping
  }

  case class DoobieLeafMapping[T](val tpe: Type, val encoder: Encoder[T], val meta: Meta[T]) extends LeafMapping[T]
  object DoobieLeafMapping {
    def apply[T](tpe: Type)(implicit encoder: Encoder[T], meta: Meta[T], dummy: DummyImplicit) =
      new DoobieLeafMapping(tpe, encoder, meta)
  }

  case class ColumnRef(table: String, column: String) {
    def toSql: String = s"$table.$column"
  }

  case class Subobject(joins: List[Join], stagingJoin: (Cursor, Query) => Result[Query] = TrivialJoin)

  case class Join(parent: ColumnRef, child: ColumnRef) {
    def normalize: Join = {
      if (parent.table > child.table) this
      else if (parent.table == child.table && parent.column >= child.column) this
      else Join(child, parent)
    }
    def swap: Join = Join(child, parent)

    def toSql: String = s"LEFT JOIN ${child.table} ON ${parent.toSql} = ${child.toSql}"
  }

  case class MappedQuery(
    table: String,
    columns: List[ColumnRef],
    metas: List[(Boolean, (Meta[_], NullabilityKnown))],
    predicates: List[(RootedType, Predicate)],
    joins: List[Join]
  ) {
    override def toString: String = {
      columns.toString
    }

    def index(col: ColumnRef): Int =
      columns.indexOf(col)

    def project(row: Row, cols: List[ColumnRef]): Row =
      Row(cols.map(cr => row(index(cr))))

    def select(row: Row, col: ColumnRef): Any =
      row(index(col))

    def hasField(tpe: RootedType, fieldName: String): Boolean = {
      val obj = tpe.dealias
      fieldMapping(obj, fieldName).map(_ => true).getOrElse(false)
    }

    def selectField(row: Row, tpe: RootedType, fieldName: String): Result[Any] = {
      val obj = tpe.dealias
      fieldMapping(obj, fieldName) match {
        case Some(DoobieField(_, col: ColumnRef, _, _)) => select(row, col).rightIor
        case Some(DoobieJson(_, col: ColumnRef)) => select(row, col).rightIor
        case other => mkErrorResult(s"Expected mapping for field $fieldName of type $obj, found $other")
      }
    }

    def hasAttribute(tpe: RootedType, attrName: String): Boolean = {
      val obj = tpe.dealias
      attributeMapping(obj, attrName).map(_ => true).getOrElse(false)
    }

    def selectAttribute(row: Row, tpe: RootedType, attrName: String): Result[Any] = {
      val obj = tpe.dealias
      attributeMapping(obj, attrName).map(_.col) match {
        case Some(col: ColumnRef) => select(row, col).rightIor
        case other => mkErrorResult(s"Expected mapping for attribute $attrName of type $obj, found $other")
      }
    }

    def hasSubobject(tpe: RootedType): Boolean = objectMapping(tpe).isDefined

    def stripNulls(table: Table, tpe: RootedType): Table =
      objectMapping(tpe.nonNull) match {
        case Some(om) if key(om).nonEmpty =>
          val cols = key(om)
          table.filterNot(row => project(row, cols).elems.exists(_ == FailedJoin))
        case _ => table
      }

    def narrowsTo(table: Table, tpe: RootedType): Boolean =
      objectMapping(tpe.nonNull) match {
        case Some(om) if key(om).nonEmpty =>
          val cols = key(om)
          !table.exists(row => project(row, cols).elems.exists(_ == FailedJoin))
        case _ => false
      }

    def group(table: Table, tpe: RootedType): List[Table] =
      objectMapping(tpe) match {
        case Some(om) if key(om).nonEmpty =>
          val cols = key(om)
          val nonNull = table.filterNot(row => project(row, cols).elems.exists(_ == FailedJoin))
          nonNull.groupBy(row => project(row, cols)).to(List).sortBy(_._1.toString).map(_._2)
        case _ => table.map(List(_))
      }

    def fetch: ConnectionIO[Table] =
      fragment.query[Row](Row.mkRead(metas)).to[List]

    def fragmentForPred(tpe: RootedType, pred: Predicate): Option[Fragment] = {
      def term[T](x: Term[T], put: Put[T]): Option[Fragment] =
        x match {
          case Const(value) => Some(Fragment("?", List(Arg[T](value, put)), None))
          case other =>
            primaryColumnForTerm(tpe, other).map(col => Fragment.const(s"${col.toSql}"))
        }

      def unify[T](x: Term[T], y: Term[T]): Option[Put[T]] = {
        def putForPath(p: List[String]) =
          (p match {
            case init :+ last =>
              val parentTpe = tpe.path(init).underlyingObject
              if (parentTpe.hasField(last)) {
                val fieldTpe = parentTpe.field(last).nonNull
                doobieLeafMapping[T](fieldTpe.tpe).map(_.meta.put).orElse(
                  fieldTpe.tpe match {
                    case StringType => Some(Put[String])
                    case IntType => Some(Put[Int])
                    case FloatType => Some(Put[Double])
                    case BooleanType => Some(Put[Boolean])
                    case _ => None
                  }
                )
              } else if (hasAttribute(parentTpe, last))
                attributeMapping(parentTpe, last).map(_.meta.put)
              else None
            case Nil => doobieLeafMapping[T](tpe.tpe.nonNull).map(_.meta.put)
          }).map(_.asInstanceOf[Put[T]])

        (x, y) match {
          case (path: Path, _) => putForPath(path.path)
          case (_, path: Path) => putForPath(path.path)
          case _ => None
        }
      }

      def loop(exp: Term[_]): Option[Fragment] =
        exp match {
          case And(x, y) =>
            Some(Fragments.andOpt(loop(x), loop(y)))
          case Or(x, y) =>
            Some(Fragments.orOpt(loop(x), loop(y)))
          case Not(x) =>
            loop(x).map(x => fr"NOT" ++ x)

          case Eql(x, y) =>
            for {
              p <- unify(x, y)
              x <- term(x, p)
              y <- term(y, p)
            } yield x ++ fr0" = "++ y
          case NEql(x, y) =>
            for {
              p <- unify(x, y)
              x <- term(x, p)
              y <- term(y, p)
            } yield x ++ fr0" != "++ y

          case Contains(x, y) =>
            for {
              p <- unify(x.asInstanceOf[Term[Any]], y)
              x <- term(x.asInstanceOf[Term[Any]], p)
              y <- term(y, p)
            } yield x ++ fr0" = "++ y

          case Lt(x, y) =>
            for {
              p <- unify(x, y)
              x <- term(x, p)
              y <- term(y, p)
            } yield x ++ fr0" < "++ y
          case LtEql(x, y) =>
            for {
              p <- unify(x, y)
              x <- term(x, p)
              y <- term(y, p)
            } yield x ++ fr0" <= "++ y
          case Gt(x, y) =>
            for {
              p <- unify(x, y)
              x <- term(x, p)
              y <- term(y, p)
            } yield x ++ fr0" > "++ y
          case GtEql(x, y) =>
            for {
              p <- unify(x, y)
              x <- term(x, p)
              y <- term(y, p)
            } yield x ++ fr0" >= "++ y

          case AndB(x, y) =>
            for {
              p <- unify(x, y)
              x <- term(x, p)
              y <- term(y, p)
            } yield x ++ fr0" & "++ y
          case OrB(x, y) =>
            for {
              p <- unify(x, y)
              x <- term(x, p)
              y <- term(y, p)
            } yield x ++ fr0" | "++ y
          case XorB(x, y) =>
            for {
              p <- unify(x, y)
              x <- term(x, p)
              y <- term(y, p)
            } yield x ++ fr0" # "++ y
          case NotB(x) =>
            loop(x).map(x => fr"~" ++ x)

          case StartsWith(x, prefix) =>
            for {
              x <- term(x, Put[String])
            } yield x ++ Fragment.const(s" LIKE ") ++ fr0"${prefix+"%"}"
          case ToUpperCase(x) =>
            loop(x).map(x => fr"upper(" ++ x ++ fr")")
          case ToLowerCase(x) =>
            loop(x).map(x => fr"lower(" ++ x ++ fr")")
          case Like(x, pattern, caseInsensitive) =>
            val op = if(caseInsensitive) "ILIKE" else "LIKE"
            term(x, Put[String]).map(x => x ++ Fragment.const(s" $op ") ++ fr0"$pattern")

          case _ => None
        }

      loop(pred)
    }

    lazy val fragment: Fragment = {
      val cols = columns.map(_.toSql)

      val preds = predicates.map((fragmentForPred _).tupled)
      val where = Fragments.whereAndOpt(preds: _*)

      val select =
        Fragment.const0(
          s"""
          |SELECT ${cols.mkString(", ")}
          |FROM $table${if (joins.isEmpty) "" else joins.map(_.toSql).mkString("\n", "\n", "")}
          |""".stripMargin
        )

      (select ++ where)
    }
  }

  class StagingElaborator extends Phase {
    val stagingJoin = (c: Cursor, q: Query) => q match {
      case Select(fieldName, _, _) =>
        val obj = RootedType(c.tpe.underlyingObject)

        val osj = fieldMapping(obj, fieldName).collect { case DoobieObject(_, so: Subobject) => so.stagingJoin }

        osj match {
          case Some(stagingJoin) => stagingJoin(c, q)
          case None =>
            mkErrorResult(s"No staging join for field '$fieldName' of type $obj")
        }
      case _ => mkErrorResult(s"No staging join for non-Select $q")
    }

    override def transform(query: Query, env: Env, schema: Schema, tpe: Type): Result[Query] = {
      def nonLeafList(tpe: RootedType, fieldName: String): Boolean = {
        val fieldTpe = tpe.underlyingField(fieldName).nonNull
        fieldTpe.isList &&
          (fieldMapping(tpe.underlyingObject, fieldName) match {
            case Some(DoobieObject(_, Subobject(joins, _))) if joins.nonEmpty => true
            case _ => false
          })
      }

      case class Seen[T](context: T, seenTypes: Set[Type], seenList: Boolean) {
        def map[U](f: T => U): Seen[U] = copy(context = f(context))
        def withQuery(q: Query): Seen[Query] = copy(context = q)
        def withType(q: Query, tpe: Type): Seen[Query] = copy(context = q, seenTypes = seenTypes + tpe.underlyingObject)
        def withList(q: Query, seen: => Boolean) = copy(context = q, seenList = seenList || seen)
        def forGroup: Seen[List[Query]] = copy(context = List.empty)
      }

      object Seen {
        def apply(q: Query): Seen[Query] = new Seen(q, Set.empty, false)
      }

      def loop(tpe: RootedType, seen: Seen[Query]): Result[Seen[Query]] = {
        seen.context match {
          case s@Select(fieldName, _, child) =>
            tpe.withUnderlyingField(fieldName) { childTpe =>
              if(seen.seenTypes(childTpe.tpe.underlyingObject) || (seen.seenList && nonLeafList(tpe, fieldName))) {
                val elaboratedSelect = loop(childTpe, Seen(child)).map(ec => s.copy(child = ec.context))
                elaboratedSelect.map(ec => seen.withQuery(Wrap(fieldName, Defer(stagingJoin, ec, tpe.underlyingObject.tpe))))
              } else if(childTpe.dealias.isInterface) {
                val elaboratedSelect = loop(childTpe, Seen(child)).map(ec => s.copy(child = ec.context))
                elaboratedSelect.map(ec => seen.withQuery(Wrap(fieldName, Defer(stagingJoin, ec, schema.queryType))))
              } else {
                val elaboratedSelect = loop(childTpe, seen.withType(child, tpe.tpe))
                elaboratedSelect.map(ec => ec.withList(s.copy(child = ec.context), nonLeafList(tpe, fieldName)))
              }
            }

          case n@Narrow(subtpe, child) => loop(tpe.copy(tpe = subtpe), seen.withQuery(child)).map(_.map(q => n.copy(child = q)))
          case w@Wrap(_, child)        => loop(tpe, seen.withQuery(child)).map(_.map(q => w.copy(child = q)))
          case r@Rename(_, child)      => loop(tpe, seen.withQuery(child)).map(_.map(q => r.copy(child = q)))

          case g@Group(queries)        =>
            queries.foldM(seen.forGroup) {
              case (acc, q) => loop(tpe, acc.withQuery(q)).map(_.map(q => q :: acc.context))
            }.map(_.map(qs => g.copy(queries = qs.reverse)))

          case g@GroupList(queries)    =>
            queries.foldM(seen.forGroup) {
              case (acc, q) => loop(tpe, acc.withQuery(q)).map(_.map(q => q :: acc.context))
            }.map(_.map(qs => g.copy(queries = qs.reverse)))

          case u@Unique(_, child)      =>
            loop(tpe.nonNull, seen.withType(child, tpe.tpe)).map(_.map(q => u.copy(child = q)))

          case f@Filter(_, child)      =>
            loop(tpe.item, seen.withType(child, tpe.tpe)).map(_.map(q => f.copy(child = q)))

          case l@Limit(_, child)      =>
            loop(tpe.item, seen.withType(child, tpe.tpe)).map(_.map(q => l.copy(child = q)))

          case s@OrderBy(_, child)      =>
            loop(tpe.item, seen.withType(child, tpe.tpe)).map(_.map(q => s.copy(child = q)))

          case c@Query.Component(_, _, _) => seen.withQuery(c).rightIor
          case i: Introspect           => seen.withQuery(i).rightIor
          case d: Defer                => seen.withQuery(d).rightIor
          case Empty                   => seen.withQuery(Empty).rightIor

          case s: Skip                 => mkErrorResult(s"Unexpected Skip ${s.render}")
          case n: UntypedNarrow        => mkErrorResult(s"Unexpected UntypeNarrow ${n.render}")
        }
      }

      query match {
        case g@Group(queries) =>
          queries.traverse(q => loop(RootedType(tpe), Seen(q))).map(eqs => g.copy(queries = eqs.map(_.context)))
        case g@GroupList(queries) =>
          queries.traverse(q => loop(RootedType(tpe), Seen(q))).map(eqs => g.copy(queries = eqs.map(_.context)))
        case other =>
          loop(RootedType(tpe), Seen(other)).map(_.context)
      }
    }
  }

  override def compilerPhases: List[QueryCompiler.Phase] = (new StagingElaborator) :: super.compilerPhases

  case class DoobieCursor(val rtpe: RootedType, val focus: Any, mapped: MappedQuery) extends Cursor {
    def tpe = rtpe.tpe

    def asTable: Result[Table] = focus match {
      case table@((_: Row) :: _ | Nil) => table.asInstanceOf[Table].rightIor
      case _ => mkErrorResult(s"Not a table")
    }

    def isUnstructured(tpe: Type): Boolean =
      tpe match {
        case NullableType(tpe) => isUnstructured(tpe)
        case ListType(tpe) => isUnstructured(tpe)
        case TypeRef(_, _) => tpe.dealias.isLeaf
        case _: ScalarType => true
        case _: EnumType => true
        case _ => false
      }

    def isLeaf: Boolean = false
    def asLeaf: Result[Json] =
      mkErrorResult(s"Not a leaf: $rtpe")

    def isList: Boolean =
      tpe.isList

    def asList: Result[List[Cursor]] =
      if (!tpe.isList) mkErrorResult(s"Not a list: $tpe")
      else {
        val itemTpe = rtpe.item.dealias
        asTable.map { table =>

          // The object mapping for `tpe`.
          val objectMapping0: ObjectMapping =
            objectMapping(itemTpe).getOrElse(sys.error(s"No ObjectMapping for $itemTpe"))

          // If this mapping is a list of child objects then its fields came from an outer join. If
          // there are no children then all keys defined in the mapping will have the `FailedJoin`
          // value.
          val isEmpty: Boolean =
            key(objectMapping0).forall { cr =>
              val ix = mapped.index(cr)
              table.forall(r => r(ix) == Row.FailedJoin)
            }

          // Sanity check: isEmpty implies that we had zero rows, or one row with failed joins.
          if (isEmpty)
            assert(table.length <= 1)

          // Done!
          if (isEmpty) Nil
          else mapped.group(table, itemTpe).map(table => copy(rtpe = itemTpe, focus = table))
        }
      }

    def isNullable: Boolean =
      tpe.isNullable

    def asNullable: Result[Option[Cursor]] =
      (tpe, focus) match {
        case (NullableType(_), Nil) => None.rightIor
        case (NullableType(tpe), _) => Some(copy(rtpe = RootedType(rtpe.path, tpe))).rightIor // non-nullable column as nullable schema type (ok)
        case _ => mkErrorResult("Not nullable")
      }

    def narrowsTo(subtpe: TypeRef): Boolean = {
      val ctpe =
        objectMapping(rtpe) match {
          case Some(im: DoobieInterfaceMapping) =>
            im.discriminate(this).getOrElse(tpe)
          case _ => tpe
        }
      if (ctpe =:= tpe)
        asTable.map(table => mapped.narrowsTo(table, rtpe.copy(tpe = subtpe))).right.getOrElse(false)
      else ctpe <:< subtpe
    }

    def narrow(subtpe: TypeRef): Result[Cursor] =
      if (narrowsTo(subtpe)) copy(rtpe = rtpe.copy(tpe = subtpe)).rightIor
      else mkErrorResult(s"Cannot narrow $tpe to $subtpe")

    def hasField(fieldName: String): Boolean = {
      val fieldTpe = rtpe.field(fieldName)
      if (fieldTpe.isLeaf)
        mapped.hasField(rtpe, fieldName)
      else
        mapped.hasSubobject(fieldTpe.underlyingObject)
    }

    def field(fieldName: String): Result[Cursor] = {
      val fieldTpe = tpe.field(fieldName)
      fieldMapping(rtpe.underlyingObject, fieldName) match {
        case Some(CursorField(_, f, _)) =>
          f(this).map(res => LeafCursor(tpe = fieldTpe, focus = res))

        case Some(DoobieJson(_, _)) =>
          asTable.flatMap { table =>
            mapped.selectField(table.head, rtpe, fieldName).flatMap(_ match {
              case Some(j: Json) if fieldTpe.isNullable =>
                CirceCursor(tpe = fieldTpe, focus = j).rightIor
              case None =>
                CirceCursor(tpe = fieldTpe, focus = Json.Null).rightIor
              case j: Json if !fieldTpe.isNullable =>
                CirceCursor(tpe = fieldTpe, focus = j).rightIor
              case other =>
                mkErrorResult(s"Expected jsonb value found $other")
            })
          }

        case _ =>
          if (isUnstructured(fieldTpe))
            asTable.flatMap(table =>
              mapped.selectField(table.head, rtpe, fieldName).map { leaf =>
                val leafFocus = leaf match {
                  case Some(f) if tpe.variantField(fieldName) && !fieldTpe.isNullable => f
                  case other => other
                }
                LeafCursor(tpe = fieldTpe, focus = leafFocus)
              }
            )
          else {
            val rfieldTpe = rtpe.field(fieldName)
            asTable.map(table => copy(rtpe = rfieldTpe, focus = mapped.stripNulls(table, rfieldTpe)))
          }
      }
    }

    def hasAttribute(attributeName: String): Boolean =
      fieldMapping(rtpe, attributeName) match {
        case Some(CursorAttribute(_, _)) => true
        case _ => mapped.hasAttribute(rtpe, attributeName)
      }

    def attribute(attributeName: String): Result[Any] =
      fieldMapping(rtpe, attributeName) match {
        case Some(CursorAttribute(_, f)) => f(this)
        case _ =>
          asTable.flatMap(table => mapped.selectAttribute(table.head, rtpe, attributeName))
      }
  }

  case class LeafCursor(tpe: Type, focus: Any) extends Cursor {
    def isLeaf: Boolean = tpe.isLeaf

    def asLeaf: Result[Json] =
      leafMapping[Any](tpe).map(_.encoder(focus).rightIor).getOrElse(
        focus match {
          case s: String => Json.fromString(s).rightIor
          case i: Int => Json.fromInt(i).rightIor
          case d: Double => Json.fromDouble(d) match {
              case Some(j) => j.rightIor
              case None => mkErrorResult(s"Unrepresentable double %d")
            }
          case b: Boolean => Json.fromBoolean(b).rightIor

          // This means we are looking at a column with no value because it's the result of a failed
          // outer join. This is an implementation error.
          case Row.FailedJoin => sys.error("Unhandled failed join.")

          case other =>
            mkErrorResult(s"Not a leaf: $other")
        }
      )

    def isList: Boolean =
      tpe match {
        case ListType(_) => true
        case _ => false
      }

    def asList: Result[List[Cursor]] = (tpe, focus) match {
      case (ListType(tpe), it: List[_]) => it.map(f => copy(tpe = tpe, focus = f)).rightIor
      case _ => mkErrorResult(s"Expected List type, found $tpe")
    }

    def isNullable: Boolean =
      tpe match {
        case NullableType(_) => true
        case _ => false
      }

    def asNullable: Result[Option[Cursor]] =
      (tpe, focus) match {
        case (NullableType(_), None) => None.rightIor
        case (NullableType(tpe), Some(v)) => Some(copy(tpe = tpe, focus = v)).rightIor
        case _ => mkErrorResult("Not nullable")
      }

    def narrowsTo(subtpe: TypeRef): Boolean = false
    def narrow(subtpe: TypeRef): Result[Cursor] =
      mkErrorResult(s"Cannot narrow $tpe to $subtpe")

    def hasField(fieldName: String): Boolean = false
    def field(fieldName: String): Result[Cursor] =
      mkErrorResult(s"Cannot select field '$fieldName' from leaf type $tpe")

    def hasAttribute(attributeName: String): Boolean = false
    def attribute(attributeName: String): Result[Any] =
      mkErrorResult(s"Cannot read attribute '$attributeName' from leaf type $tpe")
  }
}

object DoobiePredicate {
  def paths(t: Term[_]): List[Path] =
    t match {
      case p: Path => List(p)
      case And(x, y) => paths(x) ++ paths(y)
      case Or(x, y) => paths(x) ++ paths(y)
      case Not(x) => paths(x)
      case Eql(x, y) => paths(x) ++ paths(y)
      case NEql(x, y) => paths(x) ++ paths(y)
      case Contains(x, y) => paths(x) ++ paths(y)
      case Lt(x, y) => paths(x) ++ paths(y)
      case LtEql(x, y) => paths(x) ++ paths(y)
      case Gt(x, y) => paths(x) ++ paths(y)
      case GtEql(x, y) => paths(x) ++ paths(y)
      case AndB(x, y) => paths(x) ++ paths(y)
      case OrB(x, y) => paths(x) ++ paths(y)
      case XorB(x, y) => paths(x) ++ paths(y)
      case NotB(x) => paths(x)
      case StartsWith(x, _) => paths(x)
      case ToUpperCase(x) => paths(x)
      case ToLowerCase(x) => paths(x)
      case Matches(x, _) => paths(x)
      case Like(x, _, _) => paths(x)
      case _ => Nil
    }

  def isField(p: Path): Boolean =
    p match {
      case FieldPath(_) | CollectFieldPath(_) => true
      case _ => false
    }

  def likeToRegex(pattern: String, caseInsensitive: Boolean): Regex = {
    val csr = ("^"+pattern.replace("%", ".*").replace("_", ".")+"$")
    (if (caseInsensitive) s"(?i:$csr)" else csr).r
  }

  case class Like(x: Term[String], pattern: String, caseInsensitive: Boolean) extends Predicate {
    lazy val r = likeToRegex(pattern, caseInsensitive)

    def apply(c: Cursor): Result[Boolean] = x(c).map(r.matches(_))
  }
}

case class Row(elems: List[Any]) {
  def apply(i: Int): Any = elems(i)
}

object Row {
  // Placeholder for nulls read from non-nullable columns introduced via an outer join.
  case object FailedJoin

  def mkRead(metas: List[(Boolean, (Meta[_], NullabilityKnown))]): Read[Row] = {
    def unsafeGet(rs: ResultSet, n: Int): Row =
      Row {
        metas.zipWithIndex.map {
          case ((isJoin, (m, NoNulls)),  i) =>
            if (isJoin) m.get.unsafeGetNullable(rs, n+i).getOrElse(FailedJoin)
            else m.get.unsafeGetNonNullable(rs, n+i)
          case ((_, (m, Nullable)), i) => m.get.unsafeGetNullable(rs, n+i)
        }
      }

    new Read(metas.map { case (_, (m, n)) => (m.get, n) }, unsafeGet)
  }
}
