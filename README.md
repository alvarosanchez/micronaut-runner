<!-- Checklist: https://github.com/micronaut-projects/micronaut-core/wiki/New-Module-Checklist -->

# Micronaut Runner

[![Maven Central](https://img.shields.io/maven-central/v/io.micronaut.runner/micronaut-runner-launcher.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.micronaut.runner%22%20AND%20a:%22micronaut-runner-launcher%22)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.micronaut.runner?label=Gradle%20Plugin%20Portal)](https://plugins.gradle.org/plugin/io.micronaut.runner)
[![Build Status](https://github.com/micronaut-projects/micronaut-runner/workflows/Java%20CI/badge.svg)](https://github.com/micronaut-projects/micronaut-runner/actions)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=micronaut-projects_micronaut-runner&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=micronaut-projects_micronaut-runner)
[![Revved up by Develocity](https://img.shields.io/badge/Revved%20up%20by-Develocity-06A0CE?logo=Gradle&labelColor=02303A)](https://ge.micronaut.io/scans)

Fast-starting single-jar packaging for Micronaut applications.

`micronaut-runner` packages an application and all of its dependencies into one executable JAR that you
run with `java -jar app.jar`, **without** flattening the dependencies into a single namespace the way
Gradle Shadow and Maven Shade do. Every dependency stays an intact nested JAR, so `META-INF/services`
entries, `META-INF/micronaut/**` trees, multi-release classes, per-JAR manifests and duplicate resources
all keep the semantics they have on an ordinary classpath.

The archive carries a binary index built at packaging time that maps every entry to its absolute byte
offset in the outer file. At startup the launcher memory-maps the archive once and resolves a class with a
single hash probe, so no nested central directory is ever parsed and nothing is extracted to disk.

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
