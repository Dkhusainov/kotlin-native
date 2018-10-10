package org.jetbrains.kotlin.backend.konan.lower.loops

import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.irasdescriptors.isSubtypeOf
import org.jetbrains.kotlin.backend.konan.lower.createFunctionMatcher
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

enum class ProgressionType(val numberCastFunctionName: Name) {
    INT_PROGRESSION(Name.identifier("toInt")),
    LONG_PROGRESSION(Name.identifier("toLong")),
    CHAR_PROGRESSION(Name.identifier("toChar"));
}

internal data class ProgressionInfo(
        val progressionType: ProgressionType,
        val first: IrExpression,
        val bound: IrExpression,
        val step: IrExpression? = null,
        val increasing: Boolean = true,
        var needLastCalculation: Boolean = false,
        val closed: Boolean = true)

private fun IrConst<*>.isOne() = when (kind) {
            IrConstKind.Long -> value as Long == 1L
            IrConstKind.Int -> value as Int == 1
            else -> false
        }

// Used only by the assert.
private fun stepHasRightType(step: IrExpression, progressionType: ProgressionType) = when(progressionType) {
    ProgressionType.CHAR_PROGRESSION, ProgressionType.INT_PROGRESSION -> step.type.isInt()

    ProgressionType.LONG_PROGRESSION -> step.type.isLong()
}

internal class ProgressionInfoBuilder(val context: Context) : IrElementVisitor<ProgressionInfo?, Nothing?> {

    private val symbols = context.ir.symbols

    private val progressionElementClasses = symbols.integerClasses + symbols.char

    private val indicesMatcher = createFunctionMatcher {

        val supportedArrays = symbols.primitiveArrays.values + symbols.array

        fqNameRestriction { it == FqName("kotlin.collections.<get-indices>") }

        parametersSizeRestriction { it == 0 }

        extensionReceiverRestriction { it.type.classifierOrNull in supportedArrays }
    }

    private val untilMatcher = createFunctionMatcher {

        parametersSizeRestriction { it == 1 }

        parameterRestriction(0) { it.type.classifierOrNull in progressionElementClasses }

        fqNameRestriction { it == FqName("kotlin.ranges.until") }

        extensionReceiverRestriction { it.type.classifierOrNull in progressionElementClasses }
    }

    private val rangeToMatcher = createFunctionMatcher {

        dispatchReceiverRestriction { it.type.classifierOrNull in progressionElementClasses }

        fqNameRestriction { it.pathSegments().last() == Name.identifier("rangeTo") }

        parametersSizeRestriction { it == 1 }

        parameterRestriction(0) { it.type.classifierOrNull in progressionElementClasses }
    }

    private val downToMatcher = createFunctionMatcher {

        extensionReceiverRestriction { it.type.classifierOrNull in progressionElementClasses }

        fqNameRestriction { it == FqName("kotlin.ranges.downTo") }

        parametersSizeRestriction { it == 1 }

        parameterRestriction(0) { it.type.classifierOrNull in progressionElementClasses }
    }

    private val stepMatcher = createFunctionMatcher {

        extensionReceiverRestriction { it.type.classifierOrNull in symbols.progressionClasses }

        fqNameRestriction { it == FqName("kotlin.ranges.step") }

        parametersSizeRestriction { it == 1 }

        parameterRestriction(0) { it.type.isInt() || it.type.isLong() }
    }

    // TODO: Process constructors and other factory functions.
    private val callHandlers = listOf(
            indicesMatcher::match to ::buildIndices,
            rangeToMatcher::match to ::buildRangeTo,
            untilMatcher::match   to ::buildUntil,
            downToMatcher::match  to ::buildDownTo,
            stepMatcher::match    to ::buildStep
    )

    private fun handleCall(call: IrCall, progressionType: ProgressionType): ProgressionInfo? =
            callHandlers.firstOrNull { (checker, _) ->
                checker(call.symbol.owner)
            }?.let { (_, builder) -> builder(call, progressionType) }

    private fun buildIndices(expression: IrCall, progressionType: ProgressionType): ProgressionInfo? {
        val int0 = IrConstImpl.int(expression.startOffset, expression.endOffset, context.irBuiltIns.intType, 0)

        val bound = with(context.createIrBuilder(expression.symbol, expression.startOffset, expression.endOffset)) {
            val clazz = expression.extensionReceiver!!.type.classifierOrFail
            val symbol = symbols.arrayLastIndex[clazz] ?: return null
            irCall(symbol).apply {
                extensionReceiver = expression.extensionReceiver
            }
        }
        return ProgressionInfo(progressionType, int0, bound)
    }

    private fun buildRangeTo(expression: IrCall, progressionType: ProgressionType) =
            ProgressionInfo(progressionType,
                    expression.dispatchReceiver!!,
                    expression.getValueArgument(0)!!)

    private fun buildUntil(expression: IrCall, progressionType: ProgressionType) =
            ProgressionInfo(progressionType,
                    expression.extensionReceiver!!,
                    expression.getValueArgument(0)!!,
                    closed = false)

    private fun buildDownTo(expression: IrCall, progressionType: ProgressionType) =
            ProgressionInfo(progressionType,
                    expression.extensionReceiver!!,
                    expression.getValueArgument(0)!!,
                    increasing = false)

    private fun buildStep(expression: IrCall, progressionType: ProgressionType) =
            expression.extensionReceiver!!.accept(this, null)?.let {
                val newStep = expression.getValueArgument(0)!!
                val (newStepCheck, needBoundCalculation) = irCheckProgressionStep(progressionType, newStep)
                val step = when {
                    it.step == null -> newStepCheck
                    // There were step calls before. Just add our check in the container or create a new one.
                    it.step is IrStatementContainer -> {
                        it.step.statements.add(newStepCheck)
                        it.step
                    }
                    else -> IrCompositeImpl(expression.startOffset, expression.endOffset, newStep.type).apply {
                        statements.add(it.step)
                        statements.add(newStepCheck)
                    }
                }
                ProgressionInfo(progressionType, it.first, it.bound, step, it.increasing, needBoundCalculation, it.closed)
            }

    private fun irCheckProgressionStep(progressionType: ProgressionType,
                                       step: IrExpression): Pair<IrExpression, Boolean> {
        if (step is IrConst<*> &&
                ((step.kind == IrConstKind.Long && step.value as Long > 0) ||
                        (step.kind == IrConstKind.Int && step.value as Int > 0))) {
            return step to !step.isOne()
        }
        // The frontend checks if the step has a right type (Long for LongProgression and Int for {Int/Char}Progression)
        // so there is no need to cast it.
        assert(stepHasRightType(step, progressionType))

        val symbol = symbols.checkProgressionStep[step.type.toKotlinType()]
                ?: throw IllegalArgumentException("No `checkProgressionStep` for type ${step.type}")
        return IrCallImpl(step.startOffset, step.endOffset, symbol.owner.returnType, symbol).apply {
            putValueArgument(0, step)
        } to true
    }

    private fun IrType.getProgressionType(): ProgressionType? = when {
        isSubtypeOf(symbols.charProgression.owner.defaultType) -> ProgressionType.CHAR_PROGRESSION
        isSubtypeOf(symbols.intProgression.owner.defaultType) -> ProgressionType.INT_PROGRESSION
        isSubtypeOf(symbols.longProgression.owner.defaultType) -> ProgressionType.LONG_PROGRESSION
        else -> null
    }

    override fun visitElement(element: IrElement, data: Nothing?): ProgressionInfo? = null

    override fun visitCall(expression: IrCall, data: Nothing?): ProgressionInfo? {
        val progressionType = expression.type.getProgressionType() ?: return null

        return handleCall(expression, progressionType)
    }
}
