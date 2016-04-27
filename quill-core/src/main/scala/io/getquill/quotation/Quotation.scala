package io.getquill.quotation

import io.getquill.util.Messages._

import scala.annotation.StaticAnnotation
import scala.reflect.ClassTag
import scala.reflect.macros.whitebox.Context
import io.getquill.ast._
import scala.reflect.internal.Symbols

import scala.collection.mutable
import scala.reflect.NameTransformer

trait Quoted[+T] {
  def ast: Ast
}

case class QuotedAst(ast: Ast) extends StaticAnnotation

trait Quotation extends Liftables with Unliftables with Parsing {

  val c: Context
  import c.universe._

  def quote[T: WeakTypeTag](body: Expr[T]) = {

    val ast = astParser(body.tree)
    
    def bindingName(s: String) =
      TermName(NameTransformer.encode(s))

    val runtimeBindings = RuntimeBindings(c)(ast).map {
      case (quoted, RuntimeBinding(name)) =>
        val n = bindingName(name)
        q"val $n = ${quoted}.bindings.$n"
    }
    val bindings =
      CollectAst[CompileTimeBinding](ast).map {
        case CompileTimeBinding(tree: Tree) =>
          q"val ${bindingName(tree.toString)} = $tree"
      }

    val id = TermName(s"id${ast.hashCode}")

    q"""
      new ${c.weakTypeOf[Quoted[T]]} {

        @${c.weakTypeOf[QuotedAst]}($ast)
        def quoted = ast

        override def ast = $ast
        override def toString = ast.toString

        def $id() = ()
        val bindings = new {
          ..$bindings
          ..$runtimeBindings
        }
      }
    """
  }

  def doubleQuote[T: WeakTypeTag](body: Expr[Quoted[T]]) =
    body.tree match {
      case q"null" => c.fail("Can't quote null")
      case tree    => q"io.getquill.unquote($tree)"
    }

  def quotedFunctionBody(func: Expr[Any]) =
    func.tree match {
      case q"(..$p) => $b" => q"io.getquill.quote((..$p) => io.getquill.unquote($b))"
    }

  protected def unquote[T](tree: Tree)(implicit ct: ClassTag[T]) =
    astTree(tree).flatMap(astUnliftable.unapply).map {
      case ast: T => ast
    }

  private def astTree(tree: Tree) =
    for {
      method <- tree.tpe.decls.find(_.name.decodedName.toString == "quoted")
      annotation <- method.annotations.headOption
      astTree <- annotation.tree.children.lastOption
    } yield (astTree)
}
