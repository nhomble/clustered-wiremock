# Example: install clustered-wiremock into the stock WireMock image, pulling the extension jar from
# Maven Central. Build with: docker build -t clustered-wiremock .
#
# WireMock's ServiceLoader auto-scan loads the extension — no --extensions flag. Set cluster peers at
# run time, e.g.:
#   docker run -e WIREMOCK_CLUSTER_MEMBERS=node-a,node-b clustered-wiremock
FROM wiremock/wiremock:3.13.2

ARG CWM_VERSION=0.1.0
ADD https://repo1.maven.org/maven2/io/github/nhomble/clustered-wiremock/${CWM_VERSION}/clustered-wiremock-${CWM_VERSION}-extension.jar \
    /var/wiremock/extensions/clustered-wiremock.jar
