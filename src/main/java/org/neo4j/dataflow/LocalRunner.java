package org.neo4j.dataflow;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.teleport.v2.neo4j.templates.GoogleCloudToNeo4j;
import org.apache.beam.it.common.PipelineLauncher;
import org.apache.beam.it.common.PipelineLauncher.JobState;
import org.apache.beam.it.common.PipelineLauncher.LaunchConfig;
import org.apache.beam.it.common.PipelineLauncher.LaunchInfo;
import org.apache.beam.it.common.PipelineOperator;
import org.apache.beam.it.common.PipelineOperator.Config;
import org.apache.beam.it.common.utils.ResourceManagerUtils;
import org.apache.beam.it.gcp.artifacts.utils.ArtifactUtils;
import org.apache.beam.it.gcp.dataflow.DirectRunnerClient;
import org.apache.beam.it.gcp.storage.GcsResourceManager;
import org.apache.beam.it.neo4j.Neo4jResourceManager;
import org.apache.beam.it.neo4j.conditions.Neo4jQueryCheck;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@Command(name = "local-dataflow", version = "0.0.1", mixinStandardHelpOptions = true)
public class LocalRunner implements Runnable, AutoCloseable {

    private GcsResourceManager gcs;
    private Neo4jResourceManager neo4j;
    private PipelineLauncher launcher;
    private LaunchInfo execution;

    @Option(names = {"--region", "-r"}, description = "GCP region", required = true)
    String region;
    @Option(names = {"--project", "-p"}, description = "GCP project", required = true)
    String project;
    @Option(names = {"--bucket", "-b"}, description = "GCS bucket", required = true)
    String bucket;
    @Option(names = {"--spec", "-s"}, description = "Path to local googlecloud-to-neo4j spec file", required = true)
    File spec;
    @Option(names = {"--max-timeout", "-t"}, description = "Execution timeout")
    Duration maxTimeout = Duration.ofMinutes(5);
    @Option(names = {"--interval-check-duration", "-i"}, description = "Execution completion check interval")
    Duration checkInterval = Duration.ofSeconds(5);
    @Option(names = {"--count-query-check", "-c"}, description = "Count query checks (syntax: \"<count>:<Cypher count query>\" with a single \"count\" column)", converter = CountQueryCheckConverter.class)
    Set<CountQueryCheck> countQueryChecks = new HashSet<>();


    public static void main(String[] args) {
        var localRunner = new LocalRunner();
        Runtime.getRuntime().addShutdownHook(new Thread(localRunner::close));
        System.exit(new CommandLine(localRunner).execute(args));
    }

    @Override
    public void run() {
        GoogleCredentials credentials = credentials();
        gcs = GcsResourceManager.builder(bucket, getClass().getSimpleName(), credentials).build();
        neo4j = Neo4jResourceManager.builder(executionId())
                .setAdminPassword("letmein!")
                .build();

        uploadTemplateInputs();
        execution = startPipeline(credentials);
        waitUntilDone();
    }

    @Override
    public void close() {
        stopPipeline();
        ResourceManagerUtils.cleanResources(gcs, neo4j);
    }

    private void uploadTemplateInputs() {
        try {
            gcs.createArtifact("spec.json", Files.readString(spec.toPath()));
            gcs.createArtifact("neo4j.json", localConnectionMetadata());
        } catch (IOException e) {
            throw new RuntimeException("Unable to upload template inputs", e);
        }
    }

    private LaunchInfo startPipeline(GoogleCredentials credentials) {
        launcher = DirectRunnerClient.builder(GoogleCloudToNeo4j.class).setCredentials(credentials).build();
        try {
            return launcher.launch(project, region, LaunchConfig.builder(getClass().getSimpleName(), null)
                    .addParameter("jobSpecUri", gcsPath("spec.json"))
                    .addParameter("neo4jConnectionUri", gcsPath("neo4j.json"))
                    .addParameter("tempLocation", "gs://" + bucket + "/temp/")
                    .build());
        } catch (IOException e) {
            throw new RuntimeException("Unable to start job", e);
        }
    }

    private void stopPipeline() {
        if (launcher == null || execution == null) {
            return;
        }
        if (JobState.FINISHING_STATES.contains(execution.state())) {
            return;
        }
        try {
            launcher.cancelJob(execution.projectId(), execution.region(), execution.jobId());
        } catch (IOException e) {
            throw new RuntimeException("Unable to stop job " + execution.jobId(), e);
        }
    }

    private static GoogleCredentials credentials() {
        try {
            return ServiceAccountCredentials.getApplicationDefault();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String executionId() {
        var suffix = new Random().ints('a', 'z' + 1)
                .limit(8)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        return String.format("local-runner-%d-%s", System.currentTimeMillis(), suffix);
    }

    private void waitUntilDone() {
        Neo4jQueryCheck[] conditions = countQueryChecks.stream()
                .map(check -> check.asRunnableCondition(neo4j))
                .toList()
                .toArray(new Neo4jQueryCheck[0]);
        new PipelineOperator(launcher)
                .waitForCondition(
                        Config.builder()
                                .setJobId(execution.jobId())
                                .setProject(project)
                                .setRegion(region)
                                .setTimeoutAfter(maxTimeout).setCheckAfter(checkInterval)
                                .build(),
                        conditions);
    }

    private String gcsPath(String artifactId) {
        return ArtifactUtils.getFullGcsPath(bucket, getClass().getSimpleName(), gcs.runId(), artifactId);
    }

    private String localConnectionMetadata() {
        return """
                {
                  "server_url": "%s",
                  "database": "%s",
                  "auth_type": "basic",
                  "username": "neo4j",
                  "pwd": "%s"
                }
                """.formatted(neo4j.getUri(), neo4j.getDatabaseName(), neo4j.getAdminPassword());
    }
}

final class CountQueryCheckConverter implements ITypeConverter<CountQueryCheck> {

    @Override
    public CountQueryCheck convert(String value) {
        int index = value.indexOf(":");
        if (index == -1) {
            throw new IllegalArgumentException("count queries must be written as: <expected_count>:<count_query> (e.g.: \"42:RETURN 42 AS count\"");
        }
        long expectedCount = Long.parseLong(value.substring(0, index), 10);
        String query = value.substring(index + 1);
        return new CountQueryCheck(expectedCount, query);
    }
}

record CountQueryCheck(long expectedCount, String query) {

    public Neo4jQueryCheck asRunnableCondition(Neo4jResourceManager neo4j) {
        return Neo4jQueryCheck.builder(neo4j)
                .setQuery(this.query())
                .setExpectedResult(List.of(Map.of("count", this.expectedCount())))
                .build();
    }


    @Override
    public String toString() {
        return "CountQueryCheck{" +
               "expectedCount=" + expectedCount +
               ", countQuery='" + query + '\'' +
               '}';
    }
}
