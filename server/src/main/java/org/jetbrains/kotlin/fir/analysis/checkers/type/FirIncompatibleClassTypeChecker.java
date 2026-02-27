package org.jetbrains.kotlin.fir.analysis.checkers.type;

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter;
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind;
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext;
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef;
import org.jetbrains.kotlin.fir.types.FirTypeRef;

/**
 * Patched version of FirIncompatibleClassTypeChecker.
 *
 * The original delegates to FirIncompatibleClassExpressionChecker.checkType which crashes
 * with "source must not be null" on multiplatform code. This patched version is a safe no-op.
 *
 * See: https://github.com/JetBrains/kotlin/blob/master/compiler/fir/checkers/src/org/jetbrains/kotlin/fir/analysis/checkers/type/FirIncompatibleClassTypeChecker.kt
 */
public final class FirIncompatibleClassTypeChecker
        extends FirTypeChecker<FirResolvedTypeRef> {

    public static final FirIncompatibleClassTypeChecker INSTANCE =
            new FirIncompatibleClassTypeChecker();

    private FirIncompatibleClassTypeChecker() {
        super(MppCheckerKind.Common);
    }

    @Override
    public void check(FirResolvedTypeRef typeRef,
                      CheckerContext context,
                      DiagnosticReporter reporter) {
        // No-op: original delegates to FirIncompatibleClassExpressionChecker.checkType
        // which crashes with null element.source in multiplatform standalone mode
    }

}
