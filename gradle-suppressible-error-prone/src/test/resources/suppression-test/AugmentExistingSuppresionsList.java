// Args: -PerrorProneSuppress
// Before:
package app;
public final class App {
    @SuppressWarnings("Something")
    public void method() {
        System.out.println(new int[3].toString());
    }
}
// After:
package app;
public final class App {
    @SuppressWarnings({"Something", "for-rollout:ArrayToString"})
    public void method() {
        System.out.println(new int[3].toString());
    }
}
