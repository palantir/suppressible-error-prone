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
@SuppressWarnings({"C", "A", "B"})
void maintains_human_authored_ordering() {
    System.out.println(new int[3].toString());
}
// After:
@SuppressWarnings({"C", "A", "B", "for-rollout:ArrayToString"})
void maintains_human_authored_ordering() {
    System.out.println(new int[3].toString());
}


// Before:
@SuppressWarnings({"A"})
void auto_suppressions_are_initially_alphabetically_ordered() {
    System.out.println(new int[3].toString());
    System.out.println(new int[3].equals(new int[3]));
}
// After:
@SuppressWarnings({"A", "for-rollout:ArrayEquals", "for-rollout:ArrayToString"})
void auto_suppressions_are_initially_alphabetically_ordered() {
    System.out.println(new int[3].toString());
    System.out.println(new int[3].equals(new int[3]));
}


// Before:
@SuppressWarnings({"Blah", "for-rollout:A", "for-rollout:Something"})
void maintains_alphabetical_order_for_automated_suppresions() {
    System.out.println(new int[3].toString());
}
// After:
@SuppressWarnings({"Blah", "for-rollout:A", "for-rollout:ArrayToString", "for-rollout:Something"})
void maintains_alphabetical_order_for_automated_suppresions() {
    System.out.println(new int[3].toString());
}


// Before:
@SuppressWarnings({"for-rollout:Something", "Derp"})
void reorders_automated_suppresions_to_the_end() {
    System.out.println(new int[3].toString());
}
// After:
@SuppressWarnings({"Derp", "for-rollout:ArrayToString", "for-rollout:Something"})
void reorders_automated_suppresions_to_the_end() {
    System.out.println(new int[3].toString());
}


// Before:
@SuppressWarnings({"A", "for-rollout:A"})
void tidies_up_same_authored_and_human_suppression() {
    System.out.println(new int[3].toString());
}
// After:
@SuppressWarnings({"A", "for-rollout:ArrayToString"})
void tidies_up_same_authored_and_human_suppression() {
    System.out.println(new int[3].toString());
}
