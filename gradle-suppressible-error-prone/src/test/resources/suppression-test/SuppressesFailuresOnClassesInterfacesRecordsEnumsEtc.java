// Args: -PerrorProneSuppress
// Type: SingleClass
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
