package org.jetbrains.kotlin.backend.konan.lower.loops

import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.ir.KonanSymbols
import org.jetbrains.kotlin.backend.konan.irasdescriptors.isSubtypeOf
import org.jetbrains.kotlin.backend.konan.lower.matchers.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult

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

private interface ProgressionHandler {
    val matcher: IrCallMatcher

    fun build(call: IrCall, progressionType: ProgressionType): ProgressionInfo?

    fun handle(irCall: IrCall, progressionType: ProgressionType) = if (matcher(irCall)) {
        build(irCall, progressionType)
    } else {
        null
    }
}

private class RangeToHandler(val progressionElementClasses: Collection<IrClassSymbol>) : ProgressionHandler {
    override val matcher = SimpleCalleeMatcher {
        dispatchReceiverRestriction { it != null && it.type.classifierOrNull in progressionElementClasses }
        fqNameRestriction { it.pathSegments().last() == Name.identifier("rangeTo") }
        parameterCountRestriction { it == 1 }
        parameterRestriction(0) { it.type.classifierOrNull in progressionElementClasses }
    }

    override fun build(call: IrCall, progressionType: ProgressionType) =
            ProgressionInfo(progressionType, call.dispatchReceiver!!, call.getValueArgument(0)!!)
}

private class DownToHandler(val progressionElementClasses: Collection<IrClassSymbol>) : ProgressionHandler {
    override val matcher = SimpleCalleeMatcher {
        singleArgumentExtension(FqName("kotlin.ranges.downTo"), progressionElementClasses)
        parameterRestriction(0) { it.type.classifierOrNull in progressionElementClasses }
    }

    override fun build(call: IrCall, progressionType: ProgressionType): ProgressionInfo? =
            ProgressionInfo(progressionType,
                    call.extensionReceiver!!,
                    call.getValueArgument(0)!!,
                    increasing = false)
}

private class UntilHandler(val progressionElementClasses: Collection<IrClassSymbol>) : ProgressionHandler {
    override val matcher = SimpleCalleeMatcher {
        singleArgumentExtension(FqName("kotlin.ranges.until"), progressionElementClasses)
        parameterRestriction(0) { it.type.classifierOrNull in progressionElementClasses }
    }

    override fun build(call: IrCall, progressionType: ProgressionType): ProgressionInfo? =
            ProgressionInfo(progressionType,
                    call.extensionReceiver!!,
                    call.getValueArgument(0)!!,
                    closed = false)
}

private class IndicesHandler(val context: Context) : ProgressionHandler {

    private val symbols = context.ir.symbols

    private val supportedArrays = symbols.primitiveArrays.values + symbols.array

    override val matcher = SimpleCalleeMatcher {
        extensionReceiverRestriction { it != null && it.type.classifierOrNull in supportedArrays }
        fqNameRestriction { it == FqName("kotlin.collections.<get-indices>") }
        parameterCountRestriction { it == 0 }
    }

    override fun build(call: IrCall, progressionType: ProgressionType): ProgressionInfo? {
        val int0 = IrConstImpl.int(call.startOffset, call.endOffset, context.irBuiltIns.intType, 0)

        val bound = with(context.createIrBuilder(call.symbol, call.startOffset, call.endOffset)) {
            val clazz = call.extensionReceiver!!.type.classifierOrFail
            val symbol = symbols.arraySize[clazz]!!
            irCall(symbol).apply {
                dispatchReceiver = call.extensionReceiver
            }
        }
        return ProgressionInfo(progressionType, int0, bound, closed = false)
    }
}

private class StepHandler(context: Context, val visitor: IrElementVisitor<ProgressionInfo?, Nothing?>) : ProgressionHandler {
    private val symbols = context.ir.symbols

    override val matcher = SimpleCalleeMatcher {
        singleArgumentExtension(FqName("kotlin.ranges.step"), symbols.progressionClasses)
        parameterRestriction(0) { it.type.isInt() || it.type.isLong() }
    }

    override fun build(call: IrCall, progressionType: ProgressionType): ProgressionInfo? {
        return call.extensionReceiver!!.accept(visitor, null)?.let {
            val newStep = call.getValueArgument(0)!!
            val (newStepCheck, needBoundCalculation) = irCheckProgressionStep(symbols, progressionType, newStep)
            // TODO: what if newStep == newStepCheck
            val step = when (it.step) {
                null -> newStepCheck
                // There were step calls before. Just add our check in the container or create a new one.
                is IrStatementContainer -> {
                    it.step.statements.add(newStepCheck)
                    it.step
                }
                else -> IrCompositeImpl(call.startOffset, call.endOffset, newStep.type).apply {
                    statements.add(it.step)
                    statements.add(newStepCheck)
                }
            }
            ProgressionInfo(progressionType, it.first, it.bound, step, it.increasing, needBoundCalculation, it.closed)
        }
    }

    private fun IrConst<*>.stepIsOne() = when (kind) {
        IrConstKind.Long -> value as Long == 1L
        IrConstKind.Int -> value as Int == 1
        else -> false
    }

    private fun IrExpression.isPositiveConst() = this is IrConst<*> &&
            ((kind == IrConstKind.Long && value as Long > 0) || (kind == IrConstKind.Int && value as Int > 0))

    // Used only by the assert.
    private fun stepHasRightType(step: IrExpression, progressionType: ProgressionType) = when (progressionType) {

        ProgressionType.CHAR_PROGRESSION,
        ProgressionType.INT_PROGRESSION -> step.type.makeNotNull().isInt()

        ProgressionType.LONG_PROGRESSION -> step.type.makeNotNull().isLong()
    }

    private fun irCheckProgressionStep(symbols: KonanSymbols, progressionType: ProgressionType, step: IrExpression) =
            if (step.isPositiveConst()) {
                step to !(step as IrConst<*>).stepIsOne()
            } else {
                // The frontend checks if the step has a right type (Long for LongProgression and Int for {Int/Char}Progression)
                // so there is no need to cast it.
                assert(stepHasRightType(step, progressionType))

                val symbol = symbols.checkProgressionStep[step.type.makeNotNull().toKotlinType()]
                        ?: throw IllegalArgumentException("No `checkProgressionStep` for type ${step.type}")
                IrCallImpl(step.startOffset, step.endOffset, symbol.owner.returnType, symbol).apply {
                    putValueArgument(0, step)
                } to true
            }
}

internal class ProgressionInfoBuilder(val context: Context) : IrElementVisitor<ProgressionInfo?, Nothing?> {

    private val symbols = context.ir.symbols

    private val progressionElementClasses = symbols.integerClasses + symbols.char

    private val handlers = listOf(
            IndicesHandler(context),
            UntilHandler(progressionElementClasses),
            DownToHandler(progressionElementClasses),
            StepHandler(context, this),
            RangeToHandler(progressionElementClasses)
    )

    private fun IrType.getProgressionType(): ProgressionType? = when {
        isSubtypeOf(symbols.charProgression.owner.defaultType) -> ProgressionType.CHAR_PROGRESSION
        isSubtypeOf(symbols.intProgression.owner.defaultType) -> ProgressionType.INT_PROGRESSION
        isSubtypeOf(symbols.longProgression.owner.defaultType) -> ProgressionType.LONG_PROGRESSION
        else -> null
    }

    override fun visitElement(element: IrElement, data: Nothing?): ProgressionInfo? = null

    override fun visitCall(expression: IrCall, data: Nothing?): ProgressionInfo? {
        val progressionType = expression.type.getProgressionType()
                ?: return null

        return handlers.firstNotNullResult { it.handle(expression, progressionType) }
    }
}
