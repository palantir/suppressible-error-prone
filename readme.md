# Suppressible Error Prone

A Gradle Plugin that extends the [Gradle Error Prone Plugin](https://github.com/tbroyer/gradle-errorprone-plugin) and [Error Prone](https://errorprone.info/) itself to support automatically suppressing errors.

## Motivation

Error Prone is a great tool that lets you write custom checks within the Java compiler. However, when rolling out a new check to a large number of repos, the check will often fail the build, meaning that the plugin or jar the errorprones are distributed in has its upgrade blocked indefinitely until the code is fixed or manually suppressed. The result is errorprones are rarely added unless they have an autofix that work 100% of the time, or if so are set at the warning level. Or they get stuck, never upgrade, and newly written code does not benefit from the new errorprone checks.

If working in a single monorepo, it's somewhat easier to deal with this problem. You can just have the first version of your check suggest a fix that appends a suppression to an `@SuppressWarnings` annotation - there's even a helper method in errorprone to do this. Then you run the check on the monorepo, commit the suppressions, and remove/replace this suggested fix. The fix then runs on all new code.

In a polyrepo environment, it's much harder. You can't coordinate the suppression first then move on from there without a huge (probably impossible) amount of difficultly. Different repos will upgrade at different speeds, so you need to be able to suppress all errorprones in one go at any time. This is a feature this plugin provides.

Additionally, unlike the builtin errorprone helper method to suppress errors, this approach identifies that the suppression has been automated rather than manual in the actual suppression name, rather than using comments, which are brittle and hard to programmatically change. Example:

```java
// Here ArrayToString was added manually, but CollectionStreamForEach was 
// added automatically when the check was rolled out.
@SuppressWarnings({"ArrayToString", "for-rollout:CollectionStreamForEach"})
```

Other approaches like android-lint's baseline (and very large Palantir internal monorepo) have files in each source set or project that describe which Java files have which checks enabled. This plugin takes a different approach, instead suppressing checks inline in a more granular fashion.

## Usage

This plugin is (currently) mainly designed to be used by other Gradle plugins. They should include the `gradle-suppressible-error-prone` jar as a dependency, apply the plugin, then configure both this plugin and the Gradle Error Prone plugin as appropriate:

```java
public final class MyPlugin implements Plugin<Project> {
    public void apply(Project project) {
        project.getPluginManager().apply(SuppressibleErrorPronePlugin.class);
        
        SuppressibleErrorProneExtension suppressibleErrorProneExtension =
                project.getExtensions().getByType(SuppressibleErrorProneExtension.class);
        
        // Configure the base errorprone gradle plugin options in each sourceset
        suppressibleErrorProneExtension.configureEachErrorProneOptions(errorProneOptions -> {
            errorProneOptions.disable("CatchBlockLogException");
            errorProneOptions.check("JavaxInjectOnAbstractMethod", CheckSeverity.WARNING);
        });
        
        // You need to opt into which checks will apply suggested patches:
        suppressibleErrorProneExtension.getPatchChecks().addAll(
                "CollectionStreamForEach",
                "ObjectsHashCodeUnnecessaryVarargs");
        
        // You can also conditionally opt into patch checks if a module is in the transitive
        // runtimeClasspath of the source set:
        suppressibleErrorProneExtension.getConditionalPathChecks().add(
                new ConditionalPatchCheck(
                        new IfModuleIsUsed("com.palantir.safe-logging", "preconditions"),
                        "PreferSafeLoggingPreconditions",
                        "PreferSafeLoggableExceptions")
        );
    }
}
```

To actually suppress all the current failures, you need to run compilation twice:

```
./gradlew classes testClasses -PerrorProneSuppressStage1
./gradlew classes testClasses -PerrorProneSuppressStage2
```

If rolling out automatically to lots of repos, we'd recommend running the fixes first before suppressing:

```
./gradlew classes testClasses -PerrorProneApply
```

You can also run fixes for individual checks:

```
./gradlew classes testClasses -PerrorProneApply=Check
./gradlew classes testClasses -PerrorProneApply=Check,OtherCheck
```

Errorprone can be disabled by using the `-PerrorProneDisable` property.

### Using directly from Gradle

You can also use this directly in Gradle without making a plugin (not recommended if you are in a polyrepo environment):

```gradle
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath 'com.palantir.suppressible-error-prone:gradle-suppressible-error-prone:<version>'
    }
}

apply plugin: 'com.palantir.suppressible-error-prone'

dependencies {
    // You will actually need a source of errorprone checks, this include the default Google ones
    errorprone 'com.google.errorprone:error_prone_core:<version>'
}

suppressibleErrorProne {
    patchChecks.add('SomeCheck')
}
```
