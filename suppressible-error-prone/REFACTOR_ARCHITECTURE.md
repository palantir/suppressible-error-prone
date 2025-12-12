# Refactor Architecture

This document describes the new mode architecture implemented in the `refactor` package.

## Overview

The new architecture solves the problem of mode interaction without combinatorial explosion by using a **declarative refactoring system** with a **4-phase resolution algorithm**.

## Key Components

### 1. LazyRefactor (Sealed Interface)

The base abstraction for all refactoring operations. Implementations:

- **LazySuppressionRetention** - Retains an existing suppression (highest priority)
- **LazySuppressionRemoval** - Removes a suppression (unless retained)
- **LazyFix** - Applies an automatic fix from Error Prone
- **LazySuppressionAddition** - Adds a suppression as fallback (lowest priority)

Each refactor declares:
- `targetPath()` - where it should be applied
- `generateFix()` - the actual SuggestedFix to apply

### 2. EffectiveState

Tracks the effective state of the code during resolution:

- **originalSuppressions** - suppressions from source code before any modifications
- **effectiveSuppressions** - what suppressions will exist after all refactorings
- **appliedFixes** - which fixes will be applied
- **retainedSuppressions** - which suppressions are being kept
- **removedSuppressions** - which suppressions are being removed

Provides query methods:
- `isSuppressed(TreePath, String)` - walks up tree to check if suppressed
- `isFixed(TreePath, String)` - checks if a fix is being applied
- `wereSourcesHandled(LazySuppressionAddition)` - checks if related fixes were applied

### 3. RefactorAccumulator

The central resolution engine. Uses a static `WeakHashMap<CompilationUnitTree, RefactorAccumulator>` to accumulate refactorings from all modes.

**4-Phase Resolution Algorithm:**

```
Phase 1: Apply all LazySuppressionRetention (highest priority)
         └─ These suppressions are definitely kept

Phase 2: Apply all LazySuppressionRemoval (respecting retentions)
         └─ Removes suppressions unless they're retained

Phase 3: Apply all LazyFix (checking effective suppressions)
         └─ Applies fixes only if not suppressed

Phase 4: Apply all LazySuppressionAddition (only if sources not handled)
         └─ Adds suppressions as fallback if fixes weren't applied
```

This ordering **breaks the circular dependency** between "fix if not suppressed" and "suppress if not fixed".

### 4. LazyRefactorResolver

Implements Error Prone's `Fix` interface. Triggers resolution when Error Prone calls `getReplacements()`:

1. First call triggers accumulator resolution
2. Collects all replacements from resolved refactors
3. Returns them to Error Prone for application

### 5. Mode Interface

Self-contained modes that register refactorings:

```java
public interface Mode {
    void handleDescription(Description d, VisitorState state, RefactorAccumulator accumulator);
    void onFirstVisit(VisitorState state, RefactorAccumulator accumulator);
    String getName();
}
```

## Design Principles

1. **Declarative** - Modes declare what they want, resolver decides what happens
2. **Self-contained** - Each mode's logic is fully within that mode
3. **Lazy Resolution** - Resolution happens after all checks run, when Error Prone needs replacements
4. **Phase-based Priority** - Strict ordering eliminates circular dependencies
5. **No Combinatorial Explosion** - N modes, not N! combinations

## Example: RemoveUnused + Apply Mode

```java
// RemoveUnused mode (runs during compilation)
void handleDescription(Description d, VisitorState state, RefactorAccumulator acc) {
    TreePath suppressionLocation = findSuppression(d, state);

    // Keep this suppression because the violation still fires
    acc.addRefactor(new LazySuppressionRetention(suppressionLocation, d.checkName));
}

void onFirstVisit(VisitorState state, RefactorAccumulator acc) {
    // Remove all suppressions initially
    Map<TreePath, Set<String>> suppressions = scanSuppressions(state);
    for (var entry : suppressions.entrySet()) {
        for (String checkName : entry.getValue()) {
            acc.addRefactor(new LazySuppressionRemoval(entry.getKey(), checkName));
        }
    }
}

// Apply mode (runs during compilation)
void handleDescription(Description d, VisitorState state, RefactorAccumulator acc) {
    if (!d.fixes.isEmpty()) {
        acc.addRefactor(new LazyFix(state.getPath(), d, keepExistingSuppressions: true));
    }
}

// Resolution (happens when Error Prone calls getReplacements())
Phase 1: Apply retentions → suppression at line 10 for "UnusedVariable" retained
Phase 2: Apply removals → removal of line 10 skipped (retained)
Phase 3: Apply fixes → fix at line 12 skipped (still suppressed by line 10)
Phase 4: Apply suppressions → (none added)

Result: Suppression stays, fix not applied ✓
```

## Integration Points

### For Mode Implementers

1. Create a mode class implementing `Mode` interface
2. In `handleDescription()`, add appropriate `LazyRefactor`s to accumulator
3. In `onFirstVisit()`, do any initialization (e.g., scan for existing suppressions)

### For Gradle Plugin

1. Scan compilation unit for original suppressions
2. Create `RefactorAccumulator` with original suppressions map
3. Let modes register refactors during compilation
4. Return `LazyRefactorResolver` as the Fix from modified BugCheckers

## TODOs

- [ ] Implement actual suppression removal logic in `LazySuppressionRemoval.generateFix()`
- [ ] Implement actual suppression addition logic in `LazySuppressionAddition.generateFix()`
- [ ] Handle Fix types other than SuggestedFix in `LazyFix.generateFix()`
- [ ] Aggregate imports from all refactors in `LazyRefactorResolver`
- [ ] Pass actual original suppressions to accumulator (currently empty map)
- [ ] Implement concrete Mode classes (RemoveUnusedMode, ApplyMode, SuppressMode, etc.)
- [ ] Integrate with existing VisitorStateModifications
- [ ] Add tests for resolution algorithm

## Files

```
suppressible-error-prone/src/main/java/com/palantir/suppressibleerrorprone/
├── refactor/
│   ├── LazyRefactor.java                  # Sealed interface
│   ├── LazySuppressionRetention.java      # Phase 1
│   ├── LazySuppressionRemoval.java        # Phase 2
│   ├── LazyFix.java                       # Phase 3
│   ├── LazySuppressionAddition.java       # Phase 4
│   ├── EffectiveState.java                # State tracking
│   ├── RefactorAccumulator.java           # Resolution engine
│   └── LazyRefactorResolver.java          # Fix implementation
└── modes/
    └── Mode.java                           # Mode interface
```
