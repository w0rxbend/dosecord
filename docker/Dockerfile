# syntax=docker/dockerfile:1

# Dosecord app image (ROADMAP M0.5, DESIGN.md sections 2 and 12).

FROM eclipse-temurin:25-jdk-alpine AS build
RUN apk add --no-cache curl
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.cache \
    ./mill app.assembly

FROM eclipse-temurin:25-jre-alpine

# M0.7 spike no-go remedy, required of M0.5 (docs/spikes/dst-primitives.md):
# the JDK-bundled lib/tzdb.dat can lag IANA releases, so refresh Alpine's
# tzdata at image build and point the JDK at the system zoneinfo via
# -Djdk.timezone.zip.dir (see ENTRYPOINT). Alpine ships compiled TZif files
# without a version marker, so the IANA version of the installed package is
# written to tzdata.zi in the standard header format; dosecord.infra.TzdbGuard
# reads and asserts that version at startup.
RUN apk add --no-cache --upgrade tzdata \
 && ver=$(awk '/^P:tzdata$/{found=1} found && /^V:/{sub(/^V:/,""); print; exit}' /lib/apk/db/installed) \
 && echo "# version ${ver%-r*}" > /usr/share/zoneinfo/tzdata.zi \
 && grep -q '^# version 2[0-9][0-9][0-9][a-z]$' /usr/share/zoneinfo/tzdata.zi

RUN addgroup -S dosecord && adduser -S -u 10001 -G dosecord dosecord
COPY --from=build /build/out/app/assembly.dest/out.jar /opt/dosecord/dosecord.jar
USER dosecord
EXPOSE 8080

ENTRYPOINT [ \
  "java", \
  "-XX:+UseSerialGC", \
  "-Xmx256m", \
  "-XX:+AutoCreateSharedArchive", \
  "-XX:SharedArchiveFile=/home/dosecord/dosecord.cds", \
  "-Djdk.timezone.zip.dir=/usr/share/zoneinfo", \
  "-jar", "/opt/dosecord/dosecord.jar" \
]
CMD ["run"]
