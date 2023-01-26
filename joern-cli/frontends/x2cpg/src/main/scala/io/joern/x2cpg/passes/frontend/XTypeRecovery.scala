package io.joern.x2cpg.passes.frontend

import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.nodes._
import io.shiftleft.codepropertygraph.generated.{Operators, PropertyNames}
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language._
import io.shiftleft.semanticcpg.language.operatorextension.OpNodes
import io.shiftleft.semanticcpg.language.operatorextension.OpNodes.Assignment
import org.slf4j.{Logger, LoggerFactory}
import overflowdb.BatchedUpdate.DiffGraphBuilder
import overflowdb.traversal.Traversal

import java.util.Objects
import java.util.concurrent.RecursiveTask
import scala.collection.MapView
import scala.collection.concurrent.TrieMap

/** Based on a flow-insensitive symbol-table-style approach. This pass aims to be fast and deterministic and does not
  * try to converge to some fixed point. This will help recover: <ol><li>Imported call signatures from external
  * dependencies</li><li>Dynamic type hints for mutable variables in a computational unit.</ol>
  *
  * The algorithm flows roughly as follows: <ol> <li> Scan for method signatures of methods for each compilation unit,
  * either by internally defined methods or by reading import signatures. This includes looking for aliases, e.g. import
  * foo as bar.</li><li>TODO: While performing the above, note field/member assignments in a symbol
  * table.</li><li>(Optionally) Prune these method signatures by checking their validity against the CPG.</li><li>Visit
  * assignments to populate where variables are assigned a value to extrapolate its type. Store these values in a local
  * symbol table.</li><li>Find instances of where these variables are used and update their type information.</li><li>If
  * this variable is the receiver of a call, make sure to set the type of the call accordingly.</li></ol>
  *
  * The symbol tables use the [[SymbolTable]] class to track possible type information.
  *
  * @param cpg
  *   the CPG to recovery types for.
  * @tparam ComputationalUnit
  *   the [[AstNode]] type used to represent a computational unit of the language.
  */
abstract class XTypeRecovery[ComputationalUnit <: AstNode](cpg: Cpg) extends CpgPass(cpg) {

  override def run(builder: DiffGraphBuilder): Unit =
    computationalUnit.map(unit => generateRecoveryForCompilationUnitTask(unit, builder).fork()).foreach(_.get())

  /** @return
    *   the computational units as per how the language is compiled. e.g. file.
    */
  def computationalUnit: Traversal[ComputationalUnit]

  /** A factory method to generate a [[RecoverForXCompilationUnit]] task with the given parameters.
    * @param unit
    *   the compilation unit.
    * @param builder
    *   the graph builder.
    * @return
    *   a forkable [[RecoverForXCompilationUnit]] task.
    */
  def generateRecoveryForCompilationUnitTask(
    unit: ComputationalUnit,
    builder: DiffGraphBuilder
  ): RecoverForXCompilationUnit[ComputationalUnit]

}

/** Defines how a procedure is available to be called in the current scope either by it being defined in this module or
  * being imported.
  *
  * @param callingName
  *   how this procedure is to be called, i.e., alias name, name with path, etc.
  * @param fullName
  *   the full name to where this method is defined.
  */
abstract class ScopedXProcedure(val callingName: String, val fullName: String, val isConstructor: Boolean = false) {

  /** @return
    *   there are multiple ways that this procedure could be resolved in some languages. This will be pruned later by
    *   comparing this to actual methods in the CPG using postVisitImport.
    */
  def possibleCalleeNames: Set[String] = Set()

  override def toString: String = s"ProcedureCalledAs(${possibleCalleeNames.mkString(", ")})"

  override def equals(obj: Any): Boolean = {
    obj match {
      case o: ScopedXProcedure =>
        callingName.equals(o.callingName) && fullName.equals(o.fullName) && isConstructor == o.isConstructor
      case _ => false
    }
  }

  override def hashCode(): Int = Objects.hash(callingName, fullName, isConstructor)

}

/** Tasks responsible for populating the symbol table with import data.
  *
  * @param node
  *   a node that references import information.
  */
abstract class SetXProcedureDefTask(node: CfgNode) extends RecursiveTask[Unit] {

  protected val logger: Logger = LoggerFactory.getLogger(classOf[SetXProcedureDefTask])

  override def compute(): Unit =
    node match {
      case x: Method => visitImport(x)
      case x: Call   => visitImport(x)
      case _         =>
    }

  /** Refers to the declared import information.
    *
    * @param importCall
    *   the call that imports entities into this scope.
    */
  def visitImport(importCall: Call): Unit

  /** Sets how an application method would be referred to locally.
    *
    * @param m
    *   an internal method
    */
  def visitImport(m: Method): Unit

}

/** Performs type recovery from the root of a compilation unit level
  *
  * @param cu
  *   a compilation unit, e.g. file, procedure, type, etc.
  * @param builder
  *   the graph builder
  * @tparam ComputationalUnit
  *   the [[AstNode]] type used to represent a computational unit of the language.
  */
abstract class RecoverForXCompilationUnit[ComputationalUnit <: AstNode](
  cu: ComputationalUnit,
  builder: DiffGraphBuilder
) extends RecursiveTask[Unit] {

  /** Stores type information for local structures that live within this compilation unit, e.g. local variables.
    */
  protected val symbolTable = new SymbolTable[LocalKey](SBKey.fromNodeToLocalKey)

  /** Provides an entrypoint to add known symbols and their possible types.
    */
  def prepopulateSymbolTable(): Unit = {}

  private def assignments: Traversal[Assignment] =
    cu.ast.isCall.name(Operators.assignment).map(new OpNodes.Assignment(_))

  override def compute(): Unit = try {
    prepopulateSymbolTable()
    // Set known aliases that point to imports for local and external methods/modules
    setImportsFromDeclaredProcedures(importNodes(cu) ++ internalMethodNodes(cu))
    // Prune import names if the methods exist in the CPG
    postVisitImports()
    // Populate local symbol table with assignments
    assignments.foreach(visitAssignments)
    // Persist findings
    setTypeInformation()
  } finally {
    symbolTable.clear()
  }

  /** Using import information and internally defined procedures, will generate a mapping between how functions and
    * types are aliased and called and themselves.
    *
    * @param procedureDeclarations
    *   imports to types or functions and internally defined methods themselves.
    */
  private def setImportsFromDeclaredProcedures(procedureDeclarations: Traversal[CfgNode]): Unit =
    procedureDeclarations.map(f => generateSetProcedureDefTask(f, symbolTable).fork()).foreach(_.get())

  /** Generates a task to create an import task.
    *
    * @param node
    *   the import node or method definition node.
    * @param symbolTable
    *   the local table.
    * @return
    *   a forkable [[SetXProcedureDefTask]] task.
    */
  def generateSetProcedureDefTask(node: CfgNode, symbolTable: SymbolTable[LocalKey]): SetXProcedureDefTask

  /** @return
    *   the import nodes of this computational unit.
    */
  def importNodes(cu: AstNode): Traversal[CfgNode]

  /** @param cu
    *   the current computational unit.
    * @return
    *   the methods defined within this computational unit.
    */
  def internalMethodNodes(cu: AstNode): Traversal[Method] = cu.ast.isMethod.isExternal(false)

  /** The initial import setting is over-approximated, so this step checks the CPG for any matches and prunes against
    * these findings. If there are no findings, it will leave the table as is. The latter is significant for external
    * types or methods.
    */
  def postVisitImports(): Unit = {}

  /** Using assignment and import information (in the global symbol table), will propagate these types in the symbol
    * table.
    *
    * @param assignment
    *   assignment call pointer.
    */
  def visitAssignments(assignment: Assignment): Unit

  /** Using an entry from the symbol table, will queue the CPG modification to persist the recovered type information.
    */
  private def setTypeInformation(): Unit = {
    cu.ast
      .foreach {
        case x: Local if symbolTable.contains(x) =>
          builder.setNodeProperty(x, PropertyNames.DYNAMIC_TYPE_HINT_FULL_NAME, symbolTable.get(x).toSeq)
        case x: Identifier if symbolTable.contains(x) =>
          (x.inCall.headOption, x.inCall.argument.take(2).l) match {
            // Case 1: 'call' is an assignment from some dynamic dispatch call
            case (Some(call: Call), List(i: Identifier, c: Call)) if call.name.equals(Operators.assignment) =>
              val idTypes   = symbolTable.get(i)
              val callTypes = symbolTable.get(c)
              persistType(call, callTypes)(builder)
              if (idTypes.nonEmpty || callTypes.nonEmpty) {
                if (idTypes.equals(callTypes))
                  // Case 1.1: This is a function pointer or constructor
                  persistType(i, callTypes)(builder)
                else
                  // Case 1.2: This is the return value of the function
                  persistType(i, idTypes)(builder)
              }
            // Case 1: 'call' is an assignment from some other data structure
            case (Some(call: Call), List(i: Identifier, _)) if call.name.equals(Operators.assignment) =>
              val idHints = symbolTable.get(i)
              persistType(i, idHints)(builder)
              persistType(call, idHints)(builder)
            // Case 2: 'i' is the receiver of 'call'
            case (Some(call: Call), List(i: Identifier, _)) if !call.name.equals(Operators.fieldAccess) =>
              val idHints   = symbolTable.get(i)
              val callTypes = symbolTable.get(call)
              persistType(i, idHints)(builder)
              if (callTypes.isEmpty) {
                persistType(call, idHints)(builder)
              } else {
                persistType(call, callTypes)(builder)
              }
            // Case 3: 'i' is the receiver for a field access on member 'f'
            case (Some(call: Call), List(i: Identifier, _: FieldIdentifier))
                if call.name.equals(Operators.fieldAccess) =>
              persistType(i, symbolTable.get(x))(builder)
            // Case 4: We are elsewhere
            case _ => persistType(x, symbolTable.get(x))(builder)
          }
        case x: Call if symbolTable.contains(x) =>
          builder.setNodeProperty(x, PropertyNames.DYNAMIC_TYPE_HINT_FULL_NAME, symbolTable.get(x).toSeq)
        case _ =>
      }
  }

  private def persistType(x: StoredNode, types: Set[String])(implicit builder: DiffGraphBuilder): Unit =
    if (types.nonEmpty)
      if (types.size == 1)
        builder.setNodeProperty(x, PropertyNames.TYPE_FULL_NAME, types.head)
      else
        builder.setNodeProperty(x, PropertyNames.DYNAMIC_TYPE_HINT_FULL_NAME, types.toSeq)

}

/** Represents an identifier of some AST node at a specific scope.
  */
abstract class SBKey {

  /** Convenience methods to convert a node to a [[SBKey]].
    *
    * @param node
    *   the node to convert.
    * @return
    *   the corresponding [[SBKey]].
    */
  def fromNode(node: AstNode): SBKey = SBKey.fromNodeToLocalKey(node)

}

object SBKey {
  protected val logger: Logger = LoggerFactory.getLogger(getClass)
  def fromNodeToLocalKey(node: AstNode): LocalKey = {
    node match {
      case n: FieldIdentifier => FieldVar(n.canonicalName)
      case n: Identifier      => LocalVar(n.name)
      case n: Local           => LocalVar(n.name)
      case n: Call            => CallAlias(n.name)
      case n: Method          => CallAlias(n.name)
      case n: MethodRef       => CallAlias(n.code)
      case _ => throw new RuntimeException(s"Node of type ${node.label} is not supported in the type recovery pass.")
    }
  }

}

/** Represents an identifier of some AST node at an intraprocedural scope.
  */
sealed trait LocalKey extends SBKey

/** A variable that can hold data within an interprocedural scope.
  */
case class FieldVar(identifier: String) extends LocalKey

/** A variable that holds data within an intraprocedural scope.
  */
case class LocalVar(identifier: String) extends LocalKey

/** A name that refers to some kind of callee.
  */
case class CallAlias(identifier: String) extends LocalKey

/** A thread-safe symbol table that can represent multiple types per symbol. Each node in an AST gets converted to an
  * [[SBKey]] which gives contextual information to identify an AST entity. Each value in this table represents a set of
  * types that the key could be in a flow-insensitive manner.
  *
  * The [[SymbolTable]] operates like a map with a few convenient methods that are designed for this structure's
  * purpose.
  */
class SymbolTable[K <: SBKey](fromNode: AstNode => K) {

  private val table = TrieMap.empty[K, Set[String]]

  def apply(sbKey: K): Set[String] = table(sbKey)

  def apply(node: AstNode): Set[String] = table(fromNode(node))

  def from(sb: IterableOnce[(K, Set[String])]): SymbolTable[K] = {
    table.addAll(sb); this
  }

  def put(sbKey: K, typeFullNames: Set[String]): Option[Set[String]] =
    table.put(sbKey, typeFullNames)

  def put(sbKey: K, typeFullName: String): Option[Set[String]] =
    put(sbKey, Set(typeFullName))

  def put(node: AstNode, typeFullNames: Set[String]): Option[Set[String]] =
    put(fromNode(node), typeFullNames)

  def append(node: AstNode, typeFullName: String): Option[Set[String]] =
    append(node, Set(typeFullName))

  def append(node: AstNode, typeFullNames: Set[String]): Option[Set[String]] =
    append(fromNode(node), typeFullNames)

  private def append(sbKey: K, typeFullNames: Set[String]): Option[Set[String]] = {
    table.get(sbKey) match {
      case Some(ts) => table.put(sbKey, ts ++ typeFullNames)
      case None     => table.put(sbKey, typeFullNames)
    }
  }

  def contains(sbKey: K): Boolean = table.contains(sbKey)

  def contains(node: AstNode): Boolean = contains(fromNode(node))

  def get(sbKey: K): Set[String] = table.getOrElse(sbKey, Set.empty)

  def get(node: AstNode): Set[String] = get(fromNode(node))

  def view: MapView[K, Set[String]] = table.view

  def clear(): Unit = table.clear()

}