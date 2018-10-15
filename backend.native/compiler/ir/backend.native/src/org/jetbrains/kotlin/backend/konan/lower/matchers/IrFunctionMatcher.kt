package org.jetbrains.kotlin.backend.konan.lower.matchers

import org.jetbrains.kotlin.backend.konan.irasdescriptors.fqNameSafe
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.name.FqName


internal inline fun createFunctionMatcher(restrictions: IrFunctionMatcher.() -> Unit): IrFunctionMatcher =
        IrFunctionMatcher().apply(restrictions)

data class ParameterRestriction(
        val index: Int,
        val restriction: (IrValueParameter) -> Boolean
)

internal class IrFunctionMatcher(var functionKind: Kind = Kind.ANY) {

    enum class Kind {
        ANY,
        NO_RECEIVER,
        EXTENSION,
        METHOD
    }

    private val receiverRestrictions = mutableListOf<(IrValueParameter) -> Boolean>()

    private val parameterRestrictions = mutableListOf<ParameterRestriction>()

    private val nameRestrictions = mutableListOf<(FqName) -> Boolean>()

    private val paramCountRestrictions = mutableListOf<(Int) -> Boolean>()

    fun fqNameRestriction(restriction: (FqName) -> Boolean) {
        nameRestrictions += restriction
    }

    fun parameterRestriction(index: Int, restriction: (IrValueParameter) -> Boolean) {
        parameterRestrictions += ParameterRestriction(index, restriction)
    }

    fun receiverRestriction(restriction: (IrValueParameter) -> Boolean) {
        receiverRestrictions += restriction
    }

    fun parametersSizeRestriction(restriction: (Int) -> Boolean) {
        paramCountRestrictions += restriction
    }

    fun match(function: IrFunction): Boolean {
        val params = function.valueParameters

        if (!matchReceiver(function)) return false

        nameRestrictions.forEach {
            if (!it(function.fqNameSafe)) {
                return false
            }
        }
        paramCountRestrictions.forEach {
            if (!it(params.size)) {
                return false
            }
        }
        parameterRestrictions.forEach { (idx, match) ->
            if (params.size <= idx || !match(params[idx])){
                return false
            }
        }
        return true
    }

    private fun matchReceiver(function: IrFunction): Boolean {

        assert(functionKind != Kind.NO_RECEIVER || receiverRestrictions.isEmpty()) {
            "Simple function shouldn't have any restrictions"
        }

        val receiver = when {
            function.dispatchReceiverParameter != null -> function.dispatchReceiverParameter
            function.extensionReceiverParameter != null -> function.extensionReceiverParameter
            else -> null
        }

        return when (functionKind) {
            Kind.ANY -> {
                if (receiver == null) {
                    receiverRestrictions.isEmpty()
                } else {
                    receiverRestrictions.all { it(receiver) }
                }
            }
            Kind.NO_RECEIVER -> {
                receiver == null
            }
            Kind.EXTENSION -> {
                if (receiver == null) {
                    false
                } else {
                    function.extensionReceiverParameter != null && receiverRestrictions.all { it(receiver) }
                }
            }
            Kind.METHOD -> {
                if (receiver == null) {
                    false
                } else {
                    function.dispatchReceiverParameter != null && receiverRestrictions.all { it(receiver) }
                }
            }
        }
    }
}