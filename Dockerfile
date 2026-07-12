# Example: install clustered-wiremock into the stock WireMock image.
# Build the jar first (`mvn package`), then `docker build -t clustered-wiremock .`
#
# WireMock's ServiceLoader auto-scan loads the extension — no --extensions flag. Set cluster peers at
# run time, e.g.:
#   docker run -e WIREMOCK_CLUSTER_MEMBERS=node-a,node-b clustered-wiremock
FROM wiremock/wiremock:3.13.2

COPY target/clustered-wiremock-*-extension.jar /var/wiremock/extensions/clustered-wiremock.jar
