<!-- Checklist: https://github.com/micronaut-projects/micronaut-core/wiki/New-Module-Checklist -->

# Micronaut Runner

[![Maven Central](https://img.shields.io/maven-central/v/io.micronaut.runner/micronaut-runner-launcher.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.micronaut.runner%22%20AND%20a:%22micronaut-runner-launcher%22)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.micronaut.runner.standalone?label=Gradle%20Plugin%20Portal)](https://plugins.gradle.org/plugin/io.micronaut.runner.standalone)
[![Build Status](https://github.com/micronaut-projects/micronaut-runner/workflows/Java%20CI/badge.svg)](https://github.com/micronaut-projects/micronaut-runner/actions)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=micronaut-projects_micronaut-runner&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=micronaut-projects_micronaut-runner)
[![Revved up by Develocity](https://img.shields.io/badge/Revved%20up%20by-Develocity-06A0CE?logo=Gradle&labelColor=02303A)](https://ge.micronaut.io/scans)

Fast-starting, lean single-JAR packaging for Micronaut applications.

`micronaut-runner` packages an application and its dependencies into one executable JAR that you run with
`java -jar`, without flattening the dependencies the way Gradle Shadow and Maven Shade do. Every dependency stays a
separate JAR inside the archive, so `META-INF/services` files, multi-release classes, per-JAR manifests and duplicate
resources behave as on an ordinary class path. Only Micronaut's `META-INF/micronaut/` bean metadata is merged, on
purpose, so that Micronaut reads it in one pass.

Without a JDK AOT cache, a Runner JAR typically reaches its first response in about a quarter less time than the same
application packaged with Shadow, and uses less memory
([Performance](https://micronaut-projects.github.io/micronaut-runner/latest/guide/#performance)).

## Quick start

Micronaut Runner packages Micronaut 5 applications, and needs nothing beyond Micronaut 5's own requirements, such as
Java 25. The Micronaut build plugins build Runner JARs: with Gradle, apply `io.micronaut.runner` next to
`io.micronaut.application`; with Maven, set the packaging to `runner`. Micronaut Launch offers it as the `runner`
feature, in place of Shadow. The [Quick Start](https://micronaut-projects.github.io/micronaut-runner/latest/guide/#quickStart)
has the details.

That support is in progress in Micronaut Launch (micronaut-projects/micronaut-starter#3134), the Micronaut Gradle
plugin (micronaut-projects/micronaut-gradle-plugin#1378) and the Micronaut Maven plugin
(micronaut-projects/micronaut-maven-plugin#1720). Until it ships, build Runner JARs with Runner's own interim plugins,
as [docs/interim-plugins.md](docs/interim-plugins.md) describes. With Gradle:

```groovy
plugins {
    id 'io.micronaut.runner.standalone' version '<version>'
}
```

```bash
./gradlew assemble
java -jar build/libs/<app>-<version>-all.jar
```

## Documentation

See the [Documentation](https://micronaut-projects.github.io/micronaut-runner/latest/guide/) for more information.

See the [Snapshot Documentation](https://micronaut-projects.github.io/micronaut-runner/snapshot/guide/) for the current development docs.

## Snapshots and Releases

Snapshots are automatically published to [Sonatype Snapshots](https://s01.oss.sonatype.org/content/repositories/snapshots/io/micronaut/) using [GitHub Actions](https://github.com/micronaut-projects/micronaut-runner/actions).

See the documentation in the [Micronaut Docs](https://docs.micronaut.io/latest/guide/index.html#usingsnapshots) for how to configure your build to use snapshots.

Releases are published to Maven Central via [GitHub Actions](https://github.com/micronaut-projects/micronaut-runner/actions).

Releases are completely automated. To perform a release use the following steps:

* [Publish the draft release](https://github.com/micronaut-projects/micronaut-runner/releases). There should be already a draft release created, edit and publish it. The Git Tag should start with `v`. For example `v1.0.0`.
* [Monitor the Workflow](https://github.com/micronaut-projects/micronaut-runner/actions?query=workflow%3ARelease) to check it passed successfully.
* If everything went fine, [publish to Maven Central](https://github.com/micronaut-projects/micronaut-runner/actions?query=workflow%3A"Maven+Central+Sync").
* Celebrate!
