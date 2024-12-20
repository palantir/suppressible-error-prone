// Args: -PerrorProneSuppress
// Before:
package app;
public final class App {
    public final String field = new int[3].toString();

    public App() {
        System.out.println(new int[3].toString());
    }

    public void method() {
        System.out.println(new int[3].toString());
    }

    public void variables() {
        String variable = new int[3].toString();
        System.out.println(variable);
    }

    public static class SomeClass {
        static {
            System.out.println(new int[3].toString());
        }
    }
}
// After:
package app;
public final class App {
    @SuppressWarnings("for-rollout:ArrayToString")
    public final String field = new int[3].toString();

    @SuppressWarnings("for-rollout:ArrayToString")
    public App() {
        System.out.println(new int[3].toString());
    }

    @SuppressWarnings("for-rollout:ArrayToString")
    public void method() {
        System.out.println(new int[3].toString());
    }

    public void variables() {
        @SuppressWarnings("for-rollout:ArrayToString")
        String variable = new int[3].toString();
        System.out.println(variable);
    }

    @SuppressWarnings("for-rollout:ArrayToString")
    public static class SomeClass {
        static {
            System.out.println(new int[3].toString());
        }
    }
}
