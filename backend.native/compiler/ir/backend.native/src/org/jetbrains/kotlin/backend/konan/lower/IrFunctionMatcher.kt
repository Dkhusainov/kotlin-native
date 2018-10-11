package org.jetbrains.kotlin.backend.konan.lower

import org.jetbrains.kotlin.backend.konan.irasdescriptors.fqNameSafe
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.name.FqName


internal inline fun createFunctionMatcher(restrictions: IrFunctionMatcher.() -> Unit): IrFunctionMatcher =
        IrFunctionMatcher().apply(restrictions)

internal class IrFunctionMatcher {

    private val parameterRestrictions = mutableListOf<Pair<Int, (IrValueParameter) -> Boolean>>()

    private val extensionReceiverRestrictions = mutableListOf<(IrValueParameter) -> Boolean>()

    private val dispatchReceiverRestrictions = mutableListOf<(IrValueParameter) -> Boolean>()

    private val nameRestrictions = mutableListOf<(FqName) -> Boolean>()

    private val paramCountRestrictions = mutableListOf<(Int) -> Boolean>()

    fun fqNameRestriction(restriction: (FqName) -> Boolean) {
        nameRestrictions += restriction
    }

    fun parameterRestriction(idx: Int, restriction: (IrValueParameter) -> Boolean) {
        parameterRestrictions += idx to restriction
    }

    fun extensionReceiverRestriction(restriction: (IrValueParameter) -> Boolean) {
        extensionReceiverRestrictions += restriction
    }

    fun dispatchReceiverRestriction(restriction: (IrValueParameter) -> Boolean) {
        dispatchReceiverRestrictions += restriction
    }

    fun parametersSizeRestriction(restriction: (Int) -> Boolean) {
        paramCountRestrictions += restriction
    }

    fun match(function: IrFunction): Boolean {
        val params = function.valueParameters

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

        if (function.extensionReceiverParameter != null) {
            extensionReceiverRestrictions.forEach {
                if (!it(function.extensionReceiverParameter!!)) {
                    return false
                }
            }
        }

        if (function.dispatchReceiverParameter != null) {
            dispatchReceiverRestrictions.forEach {
                if (!it(function.dispatchReceiverParameter!!)) {
                    return false
                }
            }
        }
        return true
    }
}