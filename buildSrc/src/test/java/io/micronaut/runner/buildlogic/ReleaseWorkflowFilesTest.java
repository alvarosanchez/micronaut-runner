/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.runner.buildlogic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseWorkflowFilesTest {

    @Test
    void releaseVerifiesTheFinalizedTreeAndPromotesOnlyTheCheckedBundle() throws IOException {
        String workflow = read(".github/workflows/release.yml");

        assertTrue(workflow.contains("permissions:\n  contents: read"), "default workflow permissions must be read-only");
        assertTrue(workflow.contains("if: github.repository_owner == 'micronaut-projects'"));
        assertTrue(workflow.contains("name: Record finalized source identity"));
        assertTrue(workflow.contains("--expected-commit \"${{ needs.finalize.outputs.commit }}\""));
        assertTrue(workflow.contains("--expected-tree \"${{ needs.finalize.outputs.tree }}\""));
        assertTrue(workflow.contains("./gradlew clean check docs --rerun-tasks --console=plain -Prunner.integration=required"));
        assertTrue(workflow.contains("--tests '*PublicationMetadataSmokeTest'"));
        assertTrue(workflow.contains("uses: actions/setup-python@"));
        assertTrue(workflow.contains("name: Run release artifact regression tests"));
        assertTrue(workflow.contains("python .github/scripts/test_release_artifacts.py"));
        assertTrue(workflow.contains("name: Stage signed release artifacts locally"));
        assertTrue(workflow.contains("release_artifacts.py prepare"));
        assertTrue(workflow.contains("--require-signatures"));
        assertTrue(workflow.contains("release_artifacts.py promote"));
        assertTrue(workflow.contains("--publishing-type AUTOMATIC"));
        assertFalse(workflow.contains("publishToMavenCentral"),
                "the release must not rebuild while uploading to Central");

        assertOrdered(workflow,
                "name: Run pre-release",
                "name: Record finalized source identity",
                "name: Verify finalized source and publications",
                "name: Stage signed release artifacts locally",
                "release_artifacts.py prepare",
                "release_artifacts.py promote");
    }

    @Test
    void promotionCredentialsAreIsolatedFromTheBuildAndPermissionsAreExplicit() throws IOException {
        String workflow = read(".github/workflows/release.yml");
        int verify = workflow.indexOf("  verify:");
        int promote = workflow.indexOf("  promote:");
        int provenance = workflow.indexOf("  provenance-subject:");

        assertTrue(verify > 0 && promote > verify && provenance > promote);
        String verifyJob = workflow.substring(verify, promote);
        String promoteJob = workflow.substring(promote, provenance);
        assertFalse(verifyJob.contains("SONATYPE_USERNAME"));
        assertFalse(verifyJob.contains("SONATYPE_PASSWORD"));
        assertTrue(verifyJob.contains("-DsonatypeOssUsername=local-staging"));
        assertTrue(verifyJob.contains("-DsonatypeOssPassword=local-staging"));
        assertTrue(promoteJob.contains("SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}"));
        assertTrue(promoteJob.contains("SONATYPE_PASSWORD: ${{ secrets.SONATYPE_PASSWORD }}"));
        assertTrue(promoteJob.contains("permissions:\n      contents: read"));
    }

    @Test
    void completionHasOnlyTheMutationPermissionItNeedsAndHandlesNoStableRelease() throws IOException {
        String workflow = read(".github/workflows/release.yml");
        int completion = workflow.indexOf("  release-completion:");
        int githubRelease = workflow.indexOf("  github_release:");
        String completionJob = workflow.substring(completion, githubRelease);

        assertTrue(completionJob.contains("contents: write"));
        assertTrue(completionJob.contains("issues: write"));
        assertTrue(completionJob.contains("case \"$status\" in"));
        assertTrue(completionJob.contains("404) latest='' ;;"));
    }

    @Test
    void maintainerDocumentationDeclaresTheUpstreamAndAuthorizationPolicy() throws IOException {
        String maintaining = read("MAINTAINING.md");
        String readme = read("README.md");

        assertTrue(maintaining.contains("Release workflow ownership and rollout"));
        assertTrue(maintaining.contains("micronaut-projects/micronaut-project-template"));
        assertTrue(maintaining.contains("AUTOMATIC"));
        assertTrue(maintaining.contains("central-bundle.zip"));
        assertTrue(readme.contains("automatically publishes the already verified bundle"));
        assertFalse(readme.contains("manually trigger the Maven Central"));
        assertFalse(Files.exists(repositoryRoot().resolve(".github/workflows/central-sync.yml")),
                "the obsolete manual publisher would bypass finalized-tree verification");
    }


    private static void assertOrdered(String text, String... markers) {
        int previous = -1;
        for (String marker : markers) {
            int current = text.indexOf(marker);
            assertTrue(current > previous, () -> marker + " is missing or out of order");
            previous = current;
        }
    }

    private static String read(String relative) throws IOException {
        return Files.readString(repositoryRoot().resolve(relative)).replace("\r\n", "\n");
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    && Files.isDirectory(current.resolve("test-suite"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the repository root from " + System.getProperty("user.dir"));
    }
}
