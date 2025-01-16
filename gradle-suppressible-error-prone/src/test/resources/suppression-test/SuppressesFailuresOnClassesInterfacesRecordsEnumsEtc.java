// Args: -PerrorProneSuppress
// Type: SingleClass

// The initial version of this plugin only matched on classes - other "class like" elements did not match.
// This uses the NamedLikeContextualKeyword to fail on class like elements.

// Before:
public final class App {
    static class exports {}

    interface opens {}

    record provides(int cat) {}

    enum to {}

    @interface module {}
}
// After:
public final class App {
    @SuppressWarnings("for-rollout:NamedLikeContextualKeyword")
    static class exports {}

    @SuppressWarnings("for-rollout:NamedLikeContextualKeyword")
    interface opens {}

    @SuppressWarnings("for-rollout:NamedLikeContextualKeyword")
    record provides(int cat) {}

    @SuppressWarnings("for-rollout:NamedLikeContextualKeyword")
    enum to {}

    @SuppressWarnings("for-rollout:NamedLikeContextualKeyword")
    @interface module {}
}
