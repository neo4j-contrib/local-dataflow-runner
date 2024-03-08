# Run your `googlecloud-to-neo4j` pipeline locally

## Prerequisites

 - Java 21
 - Apache Maven
 - Docker
 - GCP account with Dataflow enabled
 - GCS bucket accessible for writes

## Building the CLI

First, build the `googlecloud-to-neo4j` template locally:

```shell
git clone git@github.com:GoogleCloudPlatform/DataflowTemplates.git
mvn --file DataflowTemplates/pom.xml --also-make --projects v2/googlecloud-to-neo4j install -DskipTests -Djib.skip
```

Then, go back to this project and run:

```shell
mvn package
```

You should then be able to run:

```shell
java -jar target/local-runner-1.0-SNAPSHOT-shaded.jar --help
```

And see some output similar to:
```shell
Usage: local-dataflow [-hV] -b=<bucket> [-i=<checkInterval>] -p=<project>
                      -r=<region> -s=<spec> [-t=<maxTimeout>]
                      [-c=<countQueryChecks>]...
  -b, --bucket=<bucket>     GCS bucket
  -c, --count-query-check=<countQueryChecks>
                            Count query checks (syntax: "<count>:<Cypher count
                              query>" with a single "count" column)
  -h, --help                Show this help message and exit.
  -i, --interval-check-duration=<checkInterval>
                            Execution completion check interval
  -p, --project=<project>   GCP project
  -r, --region=<region>     GCP region
  -s, --spec=<spec>         Path to local googlecloud-to-neo4j spec file
  -t, --max-timeout=<maxTimeout>
                            Execution timeout
  -V, --version             Print version information and exit.
```

## Quick start

For the guide, you will need:

 - to have built the CLI locally (see previous section)
 - to know your GCP project name
 - to pick a GCS bucket name accessible for writes
 - a running Docker Daemon

Create a local spec file, let's save it somewhere (the rest of the guide assumes `/path/to/spec.json`):

```json
{
  "sources": [
    {
      "type": "text",
      "name": "persons",
      "ordered_field_names": "id",
      "data": [
        ["person0"],
        ["person1"],
        ["person2"],
        ["person3"],
        ["person4"]
      ]
    }
  ],
  "targets": [
    {
      "node": {
        "source": "persons",
        "name": "person import",
        "mode": "merge",
        "mappings": {
          "labels": [
            "\"Person\""
          ],
          "properties": {
            "keys": [
              {"id": "id"}
            ]
          }
        }
      }
    }
  ]
}
```

Assuming the current location is the root of this project, now run:
```shell
java -jar ./target/local-runner-1.0-SNAPSHOT-shaded.jar \
  --project=<YOUR GCP PROJECT> \
  --region=<YOUR GCP REGION> \
  --bucket=<YOUR GCS BUCKET> \
  --spec=/path/to/spec.json
```
And that's it!
A local Neo4j instance is going to be started via Docker and the pipeline will run directly on your machine.
All logs are sent to standard output directly.
Once the execution is done, the container is shut down.

You can also specify Cypher query checks to make sure the data is created in the way you expect:

```shell
java -jar ./target/local-runner-1.0-SNAPSHOT-shaded.jar \
  --project=<YOUR GCP PROJECT> \
  --region=<YOUR GCP REGION> \
  --bucket=<YOUR GCS BUCKET> \
  --spec=/path/to/spec.json \
  --count-query-check="5:MATCH (p:Person) RETURN count(p) AS count" \
  --count-query-check="0:MATCH (p:Person) WHERE NOT p.id STARTS WITH 'person' RETURN count(p) AS count"
```
