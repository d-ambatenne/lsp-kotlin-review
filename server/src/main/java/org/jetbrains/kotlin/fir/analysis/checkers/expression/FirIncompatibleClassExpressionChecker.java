package org.jetbrains.kotlin.fir.analysis.checkers.expression;

import org.jetbrains.kotlin.descriptors.SourceElement;
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter;
import org.jetbrains.kotlin.fir.FirElement;
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind;
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext;
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression;
import org.jetbrains.kotlin.fir.expressions.FirStatement;
import org.jetbrains.kotlin.fir.types.ConeKotlinType;

/**
 * Patched version of FirIncompatibleClassExpressionChecker.
 *
 * The original K2 checker crashes with "source must not be null" (IllegalArgumentException)
 * when analyzing multiplatform code in standalone Analysis API mode, because synthesized
 * FIR elements from expect/actual declarations have null source info.
 *
 * This patched version is a safe no-op that prevents the crash while preserving all other
 * diagnostic checkers. The incompatible class diagnostic (binary compatibility between
 * Kotlin compiler versions) is not critical for a code review tool.
 *
 * See: https://github.com/JetBrains/kotlin/blob/master/compiler/fir/checkers/src/org/jetbrains/kotlin/fir/analysis/checkers/expression/FirIncompatibleClassExpressionChecker.kt
 */
public final class FirIncompatibleClassExpressionChecker
        extends FirExpressionChecker<FirQualifiedAccessExpression> {

    public static final FirIncompatibleClassExpressionChecker INSTANCE =
            new FirIncompatibleClassExpressionChecker();

    private FirIncompatibleClassExpressionChecker() {
        super(MppCheckerKind.Common);
    }

    @Override
    public void check(FirQualifiedAccessExpression expression,
                      CheckerContext context,
                      DiagnosticReporter reporter) {
        // No-op: original crashes on multiplatform code with null element.source
    }

    // Must match mangled name from Kotlin internal visibility: checkType$checkers
    public final void checkType$checkers(ConeKotlinType type,
                                         FirElement element,
                                         CheckerContext context,
                                         DiagnosticReporter reporter) {
        // No-op: called by FirIncompatibleClassTypeChecker
    }

}
