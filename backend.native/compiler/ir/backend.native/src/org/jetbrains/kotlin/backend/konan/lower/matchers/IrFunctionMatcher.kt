package org.jetbrains.kotlin.backend.konan.lower.matchers

import org.jetbrains.kotlin.backend.konan.irasdescriptors.fqNameSafe
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.name.FqName

internal interface IrFunctionRestriction : (IrFunction) -> Boolean

internal class ParameterRestriction(
        val index: Int,
        val restriction: (IrValueParameter) -> Boolean
) : IrFunctionRestriction {

    override fun invoke(function: IrFunction): Boolean {
        val params = function.valueParameters
        return params.size > index && restriction(params[index])
    }
}

internal class DispatchReceiverRestriction(
        val restriction: (IrValueParameter?) -> Boolean
) : IrFunctionRestriction {

    override fun invoke(function: IrFunction): Boolean {
        return restriction(function.dispatchReceiverParameter)
    }
}

internal class ExtensionReceiverRestriction(
        val restriction: (IrValueParameter?) -> Boolean
) : IrFunctionRestriction {

    override fun invoke(function: IrFunction): Boolean {
        return restriction(function.extensionReceiverParameter)
    }
}

internal class ParameterCountRestriction(
        val restriction: (Int) -> Boolean
) : IrFunctionRestriction {

    override fun invoke(function: IrFunction): Boolean {
        return restriction(function.valueParameters.size)
    }
}

internal class FqNameRestriction(
        val restriction: (FqName) -> Boolean
) : IrFunctionRestriction {

    override fun invoke(function: IrFunction): Boolean {
        return restriction(function.fqNameSafe)
    }
}

internal open class IrFunctionRestrictionsContainer : IrFunctionRestriction {
    private val restrictions = mutableListOf<IrFunctionRestriction>()

    fun add(restriction: IrFunctionRestriction) {
        restrictions += restriction
    }

    fun fqNameRestriction(restriction: (FqName) -> Boolean) =
            add(FqNameRestriction(restriction))

    fun parameterCountRestriction(restriction: (Int) -> Boolean) =
            add(ParameterCountRestriction(restriction))

    fun extensionReceiverRestriction(restriction: (IrValueParameter?) -> Boolean) =
            add(ExtensionReceiverRestriction(restriction))

    fun dispatchReceiverRestriction(restriction: (IrValueParameter?) -> Boolean) =
            add(DispatchReceiverRestriction(restriction))

    fun parameterRestriction(index: Int, restriction: (IrValueParameter) -> Boolean) =
            add(ParameterRestriction(index, restriction))

    override fun invoke(function: IrFunction) = restrictions.all { it(function) }
}

internal fun createIrFunctionRestrictions(restrictions: IrFunctionRestrictionsContainer.() -> Unit) =
        IrFunctionRestrictionsContainer().apply(restrictions)

internal fun IrFunctionRestrictionsContainer.singleArgumentExtension(
        fqName: FqName,
        classes: Collection<IrClassSymbol>
): IrFunctionRestrictionsContainer {
    extensionReceiverRestriction { it != null && it.type.classifierOrNull in classes }
    parameterCountRestriction { it == 1 }
    fqNameRestriction { it == fqName }
    return this
}

