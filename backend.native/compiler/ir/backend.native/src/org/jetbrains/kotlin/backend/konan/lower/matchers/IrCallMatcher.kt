package org.jetbrains.kotlin.backend.konan.lower.matchers

import org.jetbrains.kotlin.ir.expressions.IrCall


internal interface IrCallMatcher : (IrCall) -> Boolean

/**
 * IrCallMatcher that puts restrictions only on its callee.
 */
internal class SimpleCalleeMatcher(
        restrictions: IrFunctionRestrictionsContainer.() -> Unit
) : IrCallMatcher {

    private val calleeRestriction: IrFunctionRestriction
            = createIrFunctionRestrictions(restrictions)

    override fun invoke(call: IrCall) = calleeRestriction(call.symbol.owner)
}