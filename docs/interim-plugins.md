# Runner's interim Gradle and Maven plugins

<!-- TEMPORARY (revert on transfer to micronaut-projects): the guide is published from this fork until then. -->
The [user guide](https://alvarosanchez.github.io/micronaut-runner/snapshot/guide/) describes Runner as it works
once its support in Micronaut Launch and in the Micronaut build plugins has shipped:

| Work in progress | Issue or pull request |
|---|---|
| The `runner` feature in Micronaut Launch | micronaut-projects/micronaut-starter#3134 |
| The `io.micronaut.runner` plugin of the Micronaut Gradle plugin | micronaut-projects/micronaut-gradle-plugin#1378 |
| The `runner` packaging of the Micronaut Maven plugin | micronaut-projects/micronaut-maven-plugin#1720 |
| Logback precompilation in Micronaut AOT, and the plugins that apply it | micronaut-projects/micronaut-aot#517, micronaut-projects/micronaut-gradle-plugin#1380, micronaut-projects/micronaut-maven-plugin#1722 |
| A JDK AOT cache trained in the plugins' Docker images | micronaut-projects/micronaut-gradle-plugin#1381, micronaut-projects/micronaut-maven-plugin#1723 |

Until then, Runner ships two interim plugins of its own. They call the same packaging library, `micronaut-runner-build`,
with the same defaults, so they build the same archive. Everything in the guide applies to them, except what this page
lists. Once the Micronaut plugins have shipped Runner support in a Micronaut platform release, the interim plugins are
deprecated, and Runner 2.0 removes them.

Runner itself is not released yet, so neither plugin is on the Gradle Plugin Portal or Maven Central until its first
release.

## Gradle: `io.micronaut.runner.standalone`

Apply the plugin, published to the Gradle Plugin Portal, at Runner's version:

```groovy
plugins {
    id 'io.micronaut.application' version '<micronaut-gradle-plugin version>'
    id 'io.micronaut.runner.standalone' version '<runner version>'
}
```

```bash
./gradlew assemble
java -jar build/libs/my-app-0.1-all.jar
```

How it differs from the guide's `io.micronaut.runner`:

- **The plugin ID and its version.** `io.micronaut.runner.standalone`, at Runner's version, not the Micronaut Gradle
  plugin's. The task names (`micronautRunnerJar`, `optimizedMicronautRunnerJar`, `recordStartupProfile`), the
  `micronaut { runner { } }` block, `options.put(...)` and `micronautRunnerElements` are the same.
- **Builds without a Micronaut plugin.** The settings block is also `micronautRunner { }`, which is the only name in a
  build that applies no Micronaut plugin. The plugin acts once the `java` plugin is applied, and takes the main class
  from the `application` block, or from the task's `mainClass`.
- **Task settings.** Every option of `micronaut { runner { } }`, `options` and `startupClasses` included, is also a
  property of `micronautRunnerJar` and `optimizedMicronautRunnerJar`, and a value set on a task wins for that task. The
  task also has `mainClass`, which defaults to the `application` block's. In the Kotlin DSL, the task type is
  `io.micronaut.runner.gradle.MicronautRunnerJar`.
- **Messages.** A Shadow collision fails with `The shadow plugin is configured to write ... which is also the
  micronautRunnerJar output`. Applying `io.micronaut.runner` from the Micronaut Gradle plugin as well fails with
  `The io.micronaut.runner plugin from micronaut-gradle-plugin builds the Runner JAR in this build`: remove
  `io.micronaut.runner.standalone`, and move any `micronautRunner { }` settings into `micronaut { runner { } }`.
- **Build log.** The lines that say what each build-time step did, and why when it did nothing, are logged at info
  level: run with `--info` to see them.
- **JDK AOT cache tasks.** Experimental, and only in this plugin: see [below](#jdk-aot-cache-tasks-and-goals).

To move to the Micronaut Gradle plugin:

1. Replace `id 'io.micronaut.runner.standalone'` with `id 'io.micronaut.runner'` at the version of
   `io.micronaut.application`.
2. Move `micronautRunner { }` settings into `micronaut { runner { } }`.
3. Move option values set on `micronautRunnerJar` or `optimizedMicronautRunnerJar` into `micronaut { runner { } }`,
   and a `mainClass` set on a task into the `application` block. Only the archive-name properties, such as
   `archiveClassifier`, stay on the task.
4. Remove the `jdkAotCache { }` block and any use of its tasks.

## Maven: `micronaut-runner-maven-plugin`

Add the plugin, published to Maven Central, and bind its `package` goal:

```xml
<plugin>
    <groupId>io.micronaut.runner</groupId>
    <artifactId>micronaut-runner-maven-plugin</artifactId>
    <version>RUNNER_VERSION</version>
    <executions>
        <execution>
            <goals>
                <goal>package</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

The Micronaut Maven plugin's `jar` packaging runs Maven Shade in the `package` phase. Turn that execution off, so that
only Runner packages the application:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <executions>
        <execution>
            <id>default-shade</id>
            <phase>none</phase>
        </execution>
    </executions>
</plugin>
```

Without it, the build still produces a Runner JAR, but it shades the application first, and
`original-my-app-0.1.jar` is then the shaded JAR instead of the thin one.

```bash
mvn package
java -jar target/my-app-0.1.jar
```

How it differs from the guide's `runner` packaging:

- **The packaging stays `jar`,** and this plugin's `package` goal builds the Runner JAR. Its goal prefix is
  `mn-runner`, not `mn`.
- **Configuration.** The `micronaut.runner.*` properties are the same. This plugin's `<configuration>` also takes
  `<compression>`, `<entryStub>`, `<multiRelease>`, `<enableNativeAccess>` and `<startupClasses>` as parameters, next
  to `<addOpens>`, `<addExports>`, `<manifestEntries>` and `<runnerOptions>`. `<classifier>`, `<mainClass>` and
  `<skip>` are the parameters of `micronaut.runner.classifier`, `micronaut.runner.mainClass` and
  `micronaut.runner.skip`.
- **The startup profile.** Record it with `mvn package mn-runner:record-startup-profile`. In `<configuration>`, each
  training setting is a parameter with the `training` prefix, such as `<trainingReadinessPath>` and
  `<trainingWorkloadPaths>`; the `micronaut.runner.training.*` properties are the same.
- **`runner` packaging.** In a project that uses the Micronaut Maven plugin's `runner` packaging, this plugin's
  `package` goal fails with `micronaut-maven-plugin builds the Runner JAR in runner packaging; remove this plugin's
  execution`.
- **No check for modules that depend on the application.** Without `micronaut.runner.classifier`, such a module of
  the same build receives the Runner JAR and fails to compile against it. Set the classifier.
- **JDK AOT cache goals.** Experimental, and only in this plugin: see [below](#jdk-aot-cache-tasks-and-goals).

To move to the Micronaut Maven plugin:

1. Remove this plugin and the `default-shade` override, and set the packaging to `runner`.
2. Move `<addOpens>`, `<addExports>`, `<manifestEntries>` and `<runnerOptions>` to the `micronaut-maven-plugin`
   entry.
3. Replace this plugin's other parameters: `<compression>`, `<entryStub>`, `<multiRelease>`, `<enableNativeAccess>`
   and `<startupClasses>` with the `micronaut.runner.*` property of the same name (or, except `startupClasses`, a
   `<runnerOptions>` entry); `<classifier>` and `<skip>` with `micronaut.runner.classifier` and
   `micronaut.runner.skip`; `<mainClass>`, or the `micronaut.runner.mainClass` property, with the `exec.mainClass` property; and the `<training*>` parameters with
   the `micronaut.runner.training.*` properties.
4. If the project uses Micronaut AOT, copy `aot-jar.properties` to `aot-runner.properties`: with `jar` packaging,
   Micronaut AOT read the first; with `runner` packaging, it reads the second.
5. Change command lines from `mn-runner:package` to plain `package`, and from `mn-runner:record-startup-profile` to
   `mn:runner-startup-profile`. `mn-runner:layout` and `mn-runner:jdk-aot-cache` have no counterpart.

## Logback precompilation

Until Runner uses Micronaut AOT's Logback precompilation (micronaut-projects/micronaut-aot#517), a Runner JAR
precompiles `logback.xml` with Runner's own copy of the same engine, whatever plugin builds it:

- the run-time switch is `-Dmicronaut.runner.logback.precompiled=false`, not `-Dmicronaut.logback.precompiled=false`;
- the build log says `Precompiled logback.xml (application layer) into
  io.micronaut.runner.generated.logback.LogbackConfigurator`, or `No Logback configuration was precompiled because
  ...`;
- the precompiler reads only the application's own `application*` and `bootstrap*` files, and does not follow
  `micronaut.config.import`. A `logger.config` set in a dependency JAR's configuration file, or in imported
  configuration, is not applied either, so add those to the guide's list of locations that only Micronaut sees.

## Container images

The Micronaut plugins' `dockerBuild` and `-Dpackaging=docker` never use the Runner JAR, with the interim plugins as
with the Micronaut ones. Until those images can train a JDK AOT cache themselves, train one by hand, in an image that
holds the Runner JAR or its extracted layout, as the guide's "Training a Cache" describes.

## JDK AOT cache tasks and goals

These build a JDK AOT cache with the build's own JDK. They are experimental, may change, and exist only in the interim
plugins; the Micronaut plugins do not take them over. The cache fits only the JDK build that trained it: the Gradle or
Maven toolchain JDK, else the JDK that runs the build. Use this output only when that is the exact JDK build of your
image; otherwise train in the image, as the guide's "Training a Cache" describes.

The tasks extract the Runner JAR, train a cache with the startup profile's training settings, verify it, and write a
launch argument file next to it:

```groovy
micronaut {
    runner {
        training {
            readinessPath = '/health'
            workloadPaths = ['/hello']
        }
        jdkAotCache {
            enabled = true                   // assemble also builds the cache; off by default
            target = 'layout'                // or 'singleJar'
            jvmArgs = ['-XX:+UseG1GC']       // flags the cache depends on, used in training and in app.jvmopts
        }
    }
}
```

```bash
./gradlew micronautRunnerJdkAotCache
```

In Maven, list the `jdk-aot-cache` goal after `package`, because Maven runs a phase's goals in the order they are
declared. Each setting is a parameter with the `jdkAotCache` or `training` prefix, or the property
`micronaut.runner.jdkAotCache.<name>` or `micronaut.runner.training.<name>`:

```xml
<plugin>
    <groupId>io.micronaut.runner</groupId>
    <artifactId>micronaut-runner-maven-plugin</artifactId>
    <version>RUNNER_VERSION</version>
    <executions>
        <execution>
            <goals>
                <goal>package</goal>
                <goal>jdk-aot-cache</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <jdkAotCacheEnabled>true</jdkAotCacheEnabled>
        <trainingReadinessPath>/health</trainingReadinessPath>
        <trainingWorkloadPaths>
            <trainingWorkloadPath>/hello</trainingWorkloadPath>
        </trainingWorkloadPaths>
    </configuration>
</plugin>
```

The goal does nothing unless `<jdkAotCacheEnabled>` is `true`, or `-Dmicronaut.runner.jdkAotCache.enabled=true` is
set. In Gradle, `micronautRunnerJdkAotCache` always runs when you call it; `enabled = true` only adds it to
`assemble`.

- **Settings.** `target` is `layout` (the default) or `singleJar`; `strict` (Maven: `jdkAotCacheStrict`) adds
  `-XX:AOTMode=on` to `app.jvmopts`, so that the JVM exits instead of starting slowly when the cache no longer fits;
  `jvmArgs` are the flags the cache depends on, such as the collector, and go into training and `app.jvmopts`. The
  training settings' own `jvmArgs` apply only to recording the startup profile.
- **Output.** `build/micronaut-runner/jdk-aot-cache/` (Maven: `target/micronaut-runner/jdk-aot-cache/`) holds the
  application, the cache `app.aot` and the argument file `app.jvmopts`. The argument file names the cache by a relative
  path, so start the application from that directory, and do not let a Kubernetes `workingDir` change it:

  ```dockerfile
  WORKDIR /app
  COPY build/micronaut-runner/jdk-aot-cache/ ./
  ENTRYPOINT ["java", "@app.jvmopts", "-jar", "my-app-0.1-all.jar"]
  ```

  With Maven, the JAR is `my-app-0.1.jar`.
- **Verification.** The build verifies the cache, and deletes it if a check fails, keeping the logs; `aot-report.json`
  records the result. On JDK 27 and later, the plugins add the cache creation flags themselves.
- **`singleJar`.** The plugins train on a copy of the Runner JAR and set `micronaut.runner.aot.training` for the
  training run only.
- **The layout alone.** `micronautRunnerLayout` and `mn-runner:layout` write only the extracted layout, to
  `build/micronaut-runner/layout/` (Maven: `target/micronaut-runner/layout/`), for a cache you train elsewhere, for
  example by copying that directory into the guide's container example instead of `app/`.
- **Windows.** With JDK 25.0.3 or earlier, the plugins need the training setting `stopPath` to build a cache on
  Windows.
