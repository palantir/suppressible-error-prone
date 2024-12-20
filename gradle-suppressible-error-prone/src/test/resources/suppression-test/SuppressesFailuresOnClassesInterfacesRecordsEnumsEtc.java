// Args: -PerrorProneSuppress
// Before:
package app;
public final class App {
    public void variables() {
        String variable;
    }
}
// After:
package app;
public final class App {
    public void variables() {
        @SuppressWarnings("for-rollout:UnusedVariable")
        String variable;
    }
}
