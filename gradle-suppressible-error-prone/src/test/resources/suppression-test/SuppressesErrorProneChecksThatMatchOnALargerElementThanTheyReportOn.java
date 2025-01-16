// Args: -PerrorProneSuppress
// Type: SingleClass

// The UnusedVariable check implements CompilationUnitTreeMatcher, so will start with a whole
// CompilationUnitTree and then narrows down to the specific variable declaration that is unused.
// This trips up the "naive" suppression logic, which looks at where the visitor has got to rather
// than where the diagnostic description was produced.

// Before:
public final class App {
    public void variables() {
        String variable;
    }
}
// After:
public final class App {
    public void variables() {
        @SuppressWarnings("for-rollout:UnusedVariable")
        String variable;
    }
}
