// Args: -PerrorProneSuppress
// Type: Methods


// Before:
@SuppressWarnings("Something")
void already_has_something_else_suppressed() {
    System.out.println(new int[3].toString());
}
// After:
@SuppressWarnings({"Something", "for-rollout:ArrayToString"})
void already_has_something_else_suppressed() {
    System.out.println(new int[3].toString());
}


// Before:
@SuppressWarnings("ArrayToString")
void already_has_same_error_suppressed() {
    System.out.println(new int[3].toString());
}
// After:
@SuppressWarnings("ArrayToString")
void already_has_same_error_suppressed() {
    System.out.println(new int[3].toString());
}


// Before:
@SuppressWarnings({"A", "B"})
void maintains_human_authored_ordering() {
    System.out.println(new int[3].toString());
}
// After:
@SuppressWarnings({"A", "B", "for-rollout:ArrayToString"})
void maintains_human_authored_ordering() {
    System.out.println(new int[3].toString());
}


// Before:
@SuppressWarnings({"A"})
void auto_suppressions_are_alphabetically_ordered() {
    System.out.println(new int[3].toString());
    System.out.println(new int[3].equals(new int[3]));
}
// After:
@SuppressWarnings({"A", "for-rollout:ArrayEquals", "for-rollout:ArrayToString"})
void maintains_human_authored_ordering() {
    System.out.println(new int[3].toString());
    System.out.println(new int[3].equals(new int[3]));
}
