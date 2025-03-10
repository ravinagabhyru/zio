package zio.internal.macros

import zio.ZLayer.Debug
import zio._
import zio.internal.TerminalRendering
import zio.internal.TerminalRendering.LayerWiringError
import zio.internal.ansi.AnsiStringOps

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.collection.mutable.ListBuffer
import scala.collection.{immutable, mutable}

/**
 * LayerBuilder houses the core logic for compile-time layer construction. It is
 * parameterized by `Type` and `Expr` such that it can be shared across Scala 2
 * and 3, which have incompatible macro libraries.
 *
 * @param target
 *   A list of types indicating the intended output of the final layer. This is
 *   generally determined by the `R` of the effect that [[ZIO.provide]] is
 *   called on.
 * @param remainder
 *   A list of types indicating the input of the final layer. This would be the
 *   parameter of [[ZIO.provideSome]]
 * @param providedLayers0
 *   A list of layers ASTs that have been provided by the user.
 * @param layerToDebug
 *   A method which allows LayerBuilder to filter/extract the special
 *   ZLayer.Debug layers from the provided layers.
 * @param typeEquals
 *   A method for comparing types: Used in the construction of the final layer
 * @param foldTree
 *   A method for folding a tree of layers into the final layer.
 * @param method
 *   The sort of method that is being called: `provide`, `provideSome`, or
 *   `provideCustom`. This is used to provide improved compilation warnings.
 * @param exprToNode
 *   A method for converting an Expr into a Node for use in the graph traversal.
 * @param typeToNode
 *   A method for converting a leftover type into a Node to be used in the graph
 *   traversal.
 * @param showExpr
 * @param showType
 * @param reportWarn
 * @param reportError
 */
final case class LayerBuilder[Type, Expr](
  target0: List[Type],
  remainder: Type,
  remainders: List[Type],
  providedLayers0: List[Expr],
  layerToDebug: PartialFunction[Expr, Debug],
  sideEffectType: Type,
  typeEquals: (Type, Type) => Boolean,
  foldTree: LayerTree[Expr] => Expr,
  method: ProvideMethod,
  exprToNode: Expr => Node[Type, Expr],
  typeToNode: Type => Node[Type, Expr],
  showExpr: Expr => String,
  showType: Type => String,
  reportWarn: String => Unit,
  reportError: String => Nothing
) {

  lazy val target =
    if (method.isProvideSomeShared) target0.filterNot(t1 => remainders.exists(t2 => typeEquals(t1, t2)))
    else target0

  private lazy val remainderNode: Node[Type, Expr] =
    typeToNode(remainder)

  private lazy val remainderNodes: List[Node[Type, Expr]] =
    remainders.map(typeToNode).distinct

  private val (providedLayers, maybeDebug): (List[Expr], Option[ZLayer.Debug]) = {
    val maybeDebug = providedLayers0.collectFirst(layerToDebug)
    val layers     = providedLayers0.filterNot(layerToDebug.isDefinedAt)
    (layers.distinct, maybeDebug)
  }

  private val (sideEffectNodes, providedLayerNodes): (List[Node[Type, Expr]], List[Node[Type, Expr]]) =
    providedLayers.map(exprToNode).partition(_.outputs.exists(typeEquals(_, sideEffectType)))

  def build: Expr = {
    assertNoAmbiguity()

    /**
     * Build the layer tree. This represents the structure of a successfully
     * constructed ZLayer that will build the target types. This, of course, may
     * fail with one or more GraphErrors.
     */
    val layerTreeEither: Either[::[GraphError[Type, Expr]], LayerTree[Expr]] = {
      val nodes: List[Node[Type, Expr]] = providedLayerNodes ++ remainderNodes ++ sideEffectNodes
      val graph                         = Graph(nodes, typeEquals)

      for {
        original    <- graph.buildComplete(target)
        sideEffects <- graph.buildNodes(sideEffectNodes)
      } yield sideEffects ++ original
    }

    layerTreeEither match {
      case Left(buildErrors) =>
        reportBuildErrors(buildErrors)

      case Right(tree) =>
        warnUnused(tree)
        maybeDebug.foreach(debugLayer(_, tree))
        val remainder  = remainderNode.value
        val remainders = remainderNodes.map(_.value)
        val patched    = patchRemainder(tree, remainder, remainders)
        foldTree(patched)
    }
  }

  /**
   * Folds over the layer tree, replacing each layer accessing part of the
   * remainder with a layer accessing the entire remainder.
   */
  private def patchRemainder(tree: LayerTree[Expr], remainder: Expr, remainders: List[Expr]): LayerTree[Expr] =
    tree.fold[LayerTree[Expr]](
      LayerTree.Empty,
      value => if (remainders.contains(value)) LayerTree.Value(remainder) else LayerTree.Value(value),
      {
        case (LayerTree.Value(left), LayerTree.Value(right)) if left == remainder && right == remainder =>
          LayerTree.Value(remainder)
        case (left, right) =>
          LayerTree.ComposeH(left, right)
      },
      LayerTree.ComposeV(_, _)
    )

  /**
   * Checks to see if any type is provided by multiple layers. If so, this will
   * report a compilation error about ambiguous layers. Otherwise, our algorithm
   * would arbitrarily choose one of the layers to satisfy the type, possibly
   * confusing the user and breaking stuff.
   */
  private def assertNoAmbiguity(): Unit = {
    val typesToExprs: Map[String, List[String]] =
      groupMap(providedLayerNodes.flatMap { node =>
        node.outputs.map(output => showType(output) -> showExpr(node.value))
      })(_._1)(_._2)

    val duplicates: List[(String, List[String])] =
      typesToExprs.toList.filter { case (_, list) => list.size > 1 }

    if (duplicates.nonEmpty) {
      val message = "\n" + TerminalRendering.ambiguousLayersError(duplicates)
      reportError(message)
    }
  }

  /**
   * Given a LayerTree, this will warn about all provided layers that are not
   * used. This will also warn about any specified remainders that aren't
   * actually required, in the case of provideSome/provideCustom.
   */
  private def warnUnused(tree: LayerTree[Expr]): Unit = {
    val usedLayers =
      tree.map(showExpr).toSet

    val unusedUserLayers =
      providedLayerNodes.map(n => showExpr(n.value)).toSet -- usedLayers -- remainderNodes.map(n => showExpr(n.value))

    if (unusedUserLayers.nonEmpty) {
      val message = "\n" + TerminalRendering.unusedLayersError(unusedUserLayers.toList)
      reportWarn(message)
    }

    val unusedRemainderLayers = remainderNodes.filterNot(node => usedLayers(showExpr(node.value)))

    method match {
      case ProvideMethod.Provide => ()
      case ProvideMethod.ProvideSome | ProvideMethod.ProvideSomeShared =>
        if (!method.isProvideSomeShared && unusedRemainderLayers.nonEmpty) {
          val message = "\n" + TerminalRendering.unusedProvideSomeLayersError(
            unusedRemainderLayers.map(node => showType(node.outputs.head))
          )
          reportWarn(message)
        }

        if (remainders.isEmpty) {
          val message = "\n" + TerminalRendering.provideSomeNothingEnvError
          reportWarn(message)
        }
      case ProvideMethod.ProvideCustom =>
        if (unusedRemainderLayers.length == remainderNodes.length) {
          val message = "\n" + TerminalRendering.superfluousProvideCustomError
          reportWarn(message)
        }
    }
  }

  private def reportBuildErrors(errors: ::[GraphError[Type, Expr]]): Nothing = {
    val allErrors = sortErrors(errors).map(renderError).toList

    val topLevelErrors = allErrors.collect { case top: LayerWiringError.MissingTopLevel =>
      top.layer
    }.distinct

    val transitive = allErrors.collect { case LayerWiringError.MissingTransitive(layer, deps) =>
      layer -> deps
    }.groupBy(_._1).map { case (key, value) => key -> value.flatMap(_._2).distinct }

    val circularErrors = allErrors.collect { case LayerWiringError.Circular(layer, dep) =>
      layer -> dep
    }

    if (circularErrors.nonEmpty)
      reportError(TerminalRendering.circularityError(circularErrors))
    else
      reportError(TerminalRendering.missingLayersError(topLevelErrors, transitive, method.isProvideSome))
  }

  /**
   * Return only the first level of circular dependencies, as these will be the
   * most relevant.
   */
  private def sortErrors(errors: ::[GraphError[Type, Expr]]): Chunk[GraphError[Type, Expr]] = {
    val (circularDependencyErrors, otherErrors) =
      NonEmptyChunk.fromIterable(errors.head, errors.tail).distinct.partitionMap {
        case circularDependency: GraphError.CircularDependency[Type, Expr] => Left(circularDependency)
        case other                                                         => Right(other)
      }
    val sorted                = circularDependencyErrors.sortBy(_.depth)
    val initialCircularErrors = sorted.takeWhile(_.depth == sorted.headOption.map(_.depth).getOrElse(0))

    val (transitiveDepErrors, remainingErrors) = otherErrors.partitionMap {
      case es: GraphError.MissingTransitiveDependencies[Type, Expr] => Left(es)
      case other                                                    => Right(other)
    }

    val groupedTransitiveErrors = transitiveDepErrors.groupBy(_.node).map { case (node, errors) =>
      val layer = errors.flatMap(_.dependency)
      GraphError.MissingTransitiveDependencies(node, layer)
    }

    initialCircularErrors ++ groupedTransitiveErrors ++ remainingErrors
  }

  private def renderError(error: GraphError[Type, Expr]): LayerWiringError =
    error match {
      case GraphError.MissingTransitiveDependencies(node, dependencies) =>
        LayerWiringError.MissingTransitive(showExpr(node.value), dependencies.map(showType).toList)

      case GraphError.MissingTopLevelDependency(dependency) =>
        LayerWiringError.MissingTopLevel(showType(dependency))

      case GraphError.CircularDependency(node, dependency, _) =>
        LayerWiringError.Circular(showExpr(node.value), showExpr(dependency.value))
    }

  /**
   * Emits a compilation notice with a visual representation of the constructed
   * Layer.
   */
  private def debugLayer(debug: ZLayer.Debug, tree: LayerTree[Expr]): Unit = {
    val renderedTree: String =
      tree
        .map(expr => RenderedGraph(showExpr(expr)))
        .fold[RenderedGraph](RenderedGraph.Row(List.empty), identity, _ ++ _, _ >>> _)
        .render

    val title   = "  ZLayer Wiring Graph  ".yellow.bold.inverted
    val builder = new StringBuilder
    builder ++= "\n" + title + "\n\n" + renderedTree + "\n\n"

    if (debug == ZLayer.Debug.Mermaid) {
      val mermaidLink: String = generateMermaidJsLink(tree)
      builder ++= "Mermaid Live Editor Link".underlined + "\n" + mermaidLink.faint + "\n\n"
    }

    reportWarn(builder.result())
  }

  /**
   * Generates a link of the layer graph for the Mermaid.js graph viz library's
   * live-editor (https://mermaid-js.github.io/mermaid-live-editor)
   */
  private def generateMermaidJsLink(tree: LayerTree[Expr]): String = {

    def escapeString(string: String): String =
      "\\\"" + string.replace("\"", "&quot") + "\\\""

    val map = tree
      .map(expr => escapeString(showExpr(expr)))
      .fold[MermaidGraph](
        z = MermaidGraph.empty,
        value = MermaidGraph.make,
        composeH = _ ++ _,
        composeV = _ >>> _
      )
      .deps

    val aliases = mutable.Map.empty[String, String]

    def getAlias(name: String): String =
      aliases.getOrElse(
        name, {
          val alias = s"L${aliases.size}"
          aliases += name -> alias
          s"$alias($name)"
        }
      )

    val mermaidCode: String =
      map.flatMap {
        case (key, children) if children.isEmpty =>
          List(getAlias(key))
        case (key, children) =>
          children.map { child =>
            s"${getAlias(child)} --> ${getAlias(key)}"
          }
      }
        .mkString("\\n")

    val mermaidGraph =
      s"""{"code":"graph BT\\n$mermaidCode","mermaid": "{\\"theme\\": \\"default\\"}"}"""

    val encodedMermaidGraph: String =
      new String(Base64.getEncoder.encode(mermaidGraph.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)

    val mermaidLink = s"https://mermaid-js.github.io/mermaid-live-editor/edit/#$encodedMermaidGraph"
    mermaidLink
  }

  // Backwards compatibility for 2.11/2.12
  private def groupMap[A, K, B](as: List[A])(key: A => K)(f: A => B): Map[K, List[B]] = {
    val m = mutable.Map.empty[K, mutable.Builder[B, List[B]]]
    for (elem <- as) {
      val k       = key(elem)
      val builder = m.getOrElseUpdate(k, new ListBuffer[B])
      builder += f(elem)
    }
    var result = immutable.Map.empty[K, List[B]]
    m.foreach { case (k, v) =>
      result = result + ((k, v.result()))
    }
    result
  }

  final case class MermaidGraph(
    topLevel: Chunk[String],
    deps: Map[String, Chunk[String]]
  ) {
    def ++(that: MermaidGraph): MermaidGraph =
      MermaidGraph(topLevel ++ that.topLevel, deps ++ that.deps)

    def >>>(that: MermaidGraph): MermaidGraph = {
      val newDeps =
        that.deps.map { case (key, values) =>
          key -> (values ++ topLevel)
        }
      MermaidGraph(that.topLevel, deps ++ newDeps)
    }
  }

  object MermaidGraph {
    def empty: MermaidGraph = MermaidGraph(Chunk.empty, Map.empty)

    def make(string: String): MermaidGraph =
      MermaidGraph(Chunk(string), Map(string -> Chunk.empty))
  }

}

sealed trait ProvideMethod extends Product with Serializable {
  def isProvideSomeShared: Boolean = this == ProvideMethod.ProvideSomeShared
  def isProvideSome: Boolean       = this == ProvideMethod.ProvideSome || this == ProvideMethod.ProvideSomeShared

}

object ProvideMethod {
  case object Provide           extends ProvideMethod
  case object ProvideSome       extends ProvideMethod
  case object ProvideSomeShared extends ProvideMethod
  case object ProvideCustom     extends ProvideMethod
}
