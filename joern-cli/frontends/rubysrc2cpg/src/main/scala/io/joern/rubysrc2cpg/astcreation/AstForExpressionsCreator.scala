package io.joern.rubysrc2cpg.astcreation

import io.joern.rubysrc2cpg.parser.RubyParser._
import io.joern.x2cpg.Ast
import io.shiftleft.codepropertygraph.generated.nodes.NewJumpTarget
import io.shiftleft.codepropertygraph.generated.{ControlStructureTypes, DispatchTypes, Operators}
import org.antlr.v4.runtime.ParserRuleContext

import scala.jdk.CollectionConverters.CollectionHasAsScala

trait AstForExpressionsCreator { this: AstCreator =>

  protected def astForPowerExpression(ctx: PowerExpressionContext): Ast =
    astForBinaryOperatorExpression(ctx, Operators.exponentiation, ctx.expression().asScala)

  protected def astForOrExpression(ctx: OperatorOrExpressionContext): Ast =
    astForBinaryOperatorExpression(ctx, Operators.or, ctx.expression().asScala)

  protected def astForAndExpression(ctx: OperatorAndExpressionContext): Ast =
    astForBinaryOperatorExpression(ctx, Operators.and, ctx.expression().asScala)

  protected def astForUnaryExpression(ctx: UnaryExpressionContext): Ast = ctx.op.getType match {
    case TILDE => astForBinaryOperatorExpression(ctx, Operators.not, Seq(ctx.expression()))
    case PLUS  => astForBinaryOperatorExpression(ctx, Operators.plus, Seq(ctx.expression()))
    case EMARK => astForBinaryOperatorExpression(ctx, Operators.not, Seq(ctx.expression()))
  }

  protected def astForUnaryMinusExpression(ctx: UnaryMinusExpressionContext): Ast =
    astForBinaryOperatorExpression(ctx, Operators.minus, Seq(ctx.expression()))

  protected def astForAdditiveExpression(ctx: AdditiveExpressionContext): Ast = ctx.op.getType match {
    case PLUS  => astForBinaryOperatorExpression(ctx, Operators.addition, ctx.expression().asScala)
    case MINUS => astForBinaryOperatorExpression(ctx, Operators.subtraction, ctx.expression().asScala)
  }

  protected def astForMultiplicativeExpression(ctx: MultiplicativeExpressionContext): Ast = ctx.op.getType match {
    case STAR    => astForMultiplicativeStarExpression(ctx)
    case SLASH   => astForMultiplicativeSlashExpression(ctx)
    case PERCENT => astForMultiplicativePercentExpression(ctx)
  }

  protected def astForMultiplicativeStarExpression(ctx: MultiplicativeExpressionContext): Ast =
    astForBinaryOperatorExpression(ctx, Operators.multiplication, ctx.expression().asScala)

  protected def astForMultiplicativeSlashExpression(ctx: MultiplicativeExpressionContext): Ast =
    astForBinaryOperatorExpression(ctx, Operators.division, ctx.expression().asScala)

  protected def astForMultiplicativePercentExpression(ctx: MultiplicativeExpressionContext): Ast =
    astForBinaryOperatorExpression(ctx, Operators.modulo, ctx.expression().asScala)

  protected def astForEqualityExpression(ctx: EqualityExpressionContext): Ast = ctx.op.getType match {
    case LTEQGT     => astForBinaryOperatorExpression(ctx, Operators.compare, ctx.expression().asScala)
    case EQ2        => astForBinaryOperatorExpression(ctx, Operators.equals, ctx.expression().asScala)
    case EQ3        => astForBinaryOperatorExpression(ctx, Operators.is, ctx.expression().asScala)
    case EMARKEQ    => astForBinaryOperatorExpression(ctx, Operators.notEquals, ctx.expression().asScala)
    case EQTILDE    => astForBinaryOperatorExpression(ctx, RubyOperators.patternMatch, ctx.expression().asScala)
    case EMARKTILDE => astForBinaryOperatorExpression(ctx, RubyOperators.notPatternMatch, ctx.expression().asScala)
  }

  protected def astForRelationalExpression(ctx: RelationalExpressionContext): Ast = ctx.op.getType match {
    case GT   => astForBinaryOperatorExpression(ctx, Operators.greaterThan, ctx.expression().asScala)
    case GTEQ => astForBinaryOperatorExpression(ctx, Operators.greaterEqualsThan, ctx.expression().asScala)
    case LT   => astForBinaryOperatorExpression(ctx, Operators.lessThan, ctx.expression().asScala)
    case LTEQ => astForBinaryOperatorExpression(ctx, Operators.lessEqualsThan, ctx.expression().asScala)
  }

  protected def astForBitwiseOrExpression(ctx: BitwiseOrExpressionContext): Ast = ctx.op.getType match {
    case BAR   => astForBinaryOperatorExpression(ctx, Operators.logicalOr, ctx.expression().asScala)
    case CARET => astForBinaryOperatorExpression(ctx, Operators.logicalOr, ctx.expression().asScala)
  }

  protected def astForBitwiseAndExpression(ctx: BitwiseAndExpressionContext): Ast =
    astForBinaryOperatorExpression(ctx, Operators.logicalAnd, ctx.expression().asScala)

  protected def astForBitwiseShiftExpression(ctx: BitwiseShiftExpressionContext): Ast = ctx.op.getType match {
    case LT2 => astForBinaryOperatorExpression(ctx, Operators.shiftLeft, ctx.expression().asScala)
    case GT2 => astForBinaryOperatorExpression(ctx, Operators.logicalShiftRight, ctx.expression().asScala)
  }

  private def astForBinaryOperatorExpression(
    ctx: ParserRuleContext,
    name: String,
    arguments: Iterable[ExpressionContext]
  ): Ast = {
    val argsAst = arguments.flatMap(astForExpressionContext)
    val call    = callNode(ctx, ctx.getText, name, name, DispatchTypes.STATIC_DISPATCH)
    callAst(call, argsAst.toList)
  }

  protected def astForIsDefinedExpression(ctx: IsDefinedExpressionContext): Ast =
    astForBinaryOperatorExpression(ctx, RubyOperators.defined, Seq(ctx.expression()))

  // TODO: Maybe merge (in RubyParser.g4) isDefinedExpression with isDefinedPrimaryExpression?
  protected def astForIsDefinedPrimaryExpression(ctx: IsDefinedPrimaryContext): Ast = {
    val argsAst = astForExpressionOrCommand(ctx.expressionOrCommand())
    val call = callNode(ctx, ctx.getText, RubyOperators.defined, RubyOperators.defined, DispatchTypes.STATIC_DISPATCH)
    callAst(call, argsAst.toList)
  }

  protected def astForLiteralPrimaryExpression(ctx: LiteralPrimaryContext): Ast = ctx.literal() match {
    case ctx: NumericLiteralLiteralContext     => astForNumericLiteral(ctx.numericLiteral())
    case ctx: SymbolLiteralContext             => astForSymbolLiteral(ctx.symbol())
    case ctx: SingleQuotedStringLiteralContext => astForSingleQuotedStringLiteral(ctx)
    case ctx: DoubleQuotedStringLiteralContext => astForDoubleQuotedStringLiteral(ctx)
    case ctx: RegularExpressionLiteralContext  => astForRegularExpressionLiteral(ctx)
  }

  protected def astForTernaryConditionalOperator(ctx: ConditionalOperatorExpressionContext): Ast = {
    val testAst = astForExpressionContext(ctx.expression(0))
    val thenAst = astForExpressionContext(ctx.expression(1))
    val elseAst = astForExpressionContext(ctx.expression(2))
    val ifNode  = controlStructureNode(ctx, ControlStructureTypes.IF, ctx.getText)
    controlStructureAst(ifNode, testAst.headOption, thenAst ++ elseAst)
  }

  def astForRangeExpressionContext(ctx: RangeExpressionContext): Seq[Ast] =
    Seq(astForBinaryOperatorExpression(ctx, Operators.range, ctx.expression().asScala))

  protected def astForSuperExpression(ctx: SuperExpressionPrimaryContext): Ast =
    astForSuperCall(ctx, astForArgumentsWithParenthesesContext(ctx.argumentsWithParentheses))

  // TODO: Handle the optional block.
  // NOTE: `super` is quite complicated semantically speaking. We'll need
  //       to revisit how to represent them.
  protected def astForSuperCall(ctx: ParserRuleContext, arguments: Seq[Ast]): Ast = {
    val call =
      callNode(ctx, ctx.getText, RubyOperators.superKeyword, RubyOperators.superKeyword, DispatchTypes.STATIC_DISPATCH)
    callAst(call, arguments.toList)
  }

  protected def astForYieldCall(ctx: ParserRuleContext, argumentsCtx: Option[ArgumentsContext]): Ast = {
    val args = argumentsCtx.map(astForArguments).getOrElse(Seq())
    val call = callNode(ctx, ctx.getText, UNRESOLVED_YIELD, UNRESOLVED_YIELD, DispatchTypes.STATIC_DISPATCH)
    callAst(call, args)
  }

  protected def astForUntilExpression(ctx: UntilExpressionContext): Ast = {
    val testAst = astForExpressionOrCommand(ctx.expressionOrCommand()).headOption
    val bodyAst = astForCompoundStatement(ctx.doClause().compoundStatement())
    // TODO: testAst should be negated if it's going to be modelled as a while stmt.
    whileAst(testAst, bodyAst, Some(ctx.getText), line(ctx), column(ctx))
  }

  protected def astForForExpression(ctx: ForExpressionContext): Ast = {
    val forVarAst  = astForForVariableContext(ctx.forVariable())
    val forExprAst = astForExpressionOrCommand(ctx.expressionOrCommand())
    val forBodyAst = astForCompoundStatement(ctx.doClause().compoundStatement())
    // TODO: for X in Y is not properly modelled by while Y
    val forRootAst = whileAst(forExprAst.headOption, forBodyAst, Some(ctx.getText), line(ctx), column(ctx))
    forVarAst.headOption.map(forRootAst.withChild).getOrElse(forRootAst)
  }

  protected def astForIfExpression(ctx: IfExpressionContext): Ast = {
    val testAst   = astForExpressionOrCommand(ctx.expressionOrCommand())
    val thenAst   = astForCompoundStatement(ctx.thenClause().compoundStatement())
    val elsifAsts = Option(ctx.elsifClause).map(_.asScala).getOrElse(Seq()).map(astForElsifClause)
    val elseAst = Option(ctx.elseClause()).map(ctx => astForCompoundStatement(ctx.compoundStatement())).getOrElse(Seq())
    val ifNode  = controlStructureNode(ctx, ControlStructureTypes.IF, ctx.getText)
    controlStructureAst(ifNode, testAst.headOption)
      .withChildren(thenAst)
      .withChildren(elsifAsts.toSeq)
      .withChildren(elseAst)
  }

  private def astForElsifClause(ctx: ElsifClauseContext): Ast = {
    val ifNode  = controlStructureNode(ctx, ControlStructureTypes.IF, ctx.getText)
    val testAst = astForExpressionOrCommand(ctx.expressionOrCommand())
    val bodyAst = astForCompoundStatement(ctx.thenClause().compoundStatement())
    controlStructureAst(ifNode, testAst.headOption, bodyAst)
  }

}
