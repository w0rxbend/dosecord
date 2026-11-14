# syntax=docker/dockerfile:1

# Dosegram app image (ROADMAP M0.5, DESIGN.md sections 2 and 12).

# The Mini App (MINI_APP 13.1): built from the frozen lockfile with lifecycle scripts off; the
# build fails when the bundle exceeds its budget. Served under /app/ when MINIAPP_ENABLED=true.
FROM node:26-alpine AS miniapp
# The version CI uses and that wrote miniapp/pnpm-lock.yaml (.github/workflows/ci.yml).
RUN npm install --global pnpm@12.18.0
WORKDIR /build/miniapp
COPY miniapp/package.json miniapp/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --ignore-scripts
COPY miniapp/ ./
RUN pnpm run build

FROM eclipse-temurin:25-jdk-alpine AS build
RUN apk add --no-cache curl
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.cache \
    ./mill app.assembly

# The image renderer's rasteriser (ADR-022, ROADMAP G1.9): resvg 0.49.0, the
# version the pixel goldens are pinned to, built from its release source
# tarball. The tarball vendors every crate, so the build is offline and the
# checksum covers all of the code. The release binary links glibc, which the
# Alpine runtime lacks; this stage builds a musl one.
FROM rust:1.100.0-alpine AS resvg
RUN apk add --no-cache musl-dev
WORKDIR /src
ADD --checksum=sha256:a1b167d575d0be44e303c76b46fe0aefc5c5748a46979ebae6a7dc3276c2ce8c \
    https://github.com/linebender/resvg/releases/download/v0.49.0/resvg-0.49.0.tar.xz /src/
RUN tar xJf resvg-0.49.0.tar.xz \
 && cd resvg-0.49.0 \
 && cargo build --profile production --locked --offline -p resvg \
 && install -m 0755 target/production/resvg /usr/local/bin/resvg \
 && resvg --version | grep -qx '0.49.0'

FROM eclipse-temurin:25-jre-alpine

# M0.7 spike no-go remedy, required of M0.5 (docs/spikes/dst-primitives.md):
# the JDK-bundled lib/tzdb.dat can lag IANA releases, so refresh Alpine's
# tzdata at image build and point the JDK at the system zoneinfo via
# -Djdk.timezone.zip.dir (see ENTRYPOINT). Alpine ships compiled TZif files
# without a version marker, so the IANA version of the installed package is
# written to tzdata.zi in the standard header format; dosegram.infra.TzdbGuard
# reads and asserts that version at startup.
RUN apk add --no-cache --upgrade tzdata \
 && ver=$(awk '/^P:tzdata$/{found=1} found && /^V:/{sub(/^V:/,""); print; exit}' /lib/apk/db/installed) \
 && echo "# version ${ver%-r*}" > /usr/share/zoneinfo/tzdata.zi \
 && grep -q '^# version 2[0-9][0-9][0-9][a-z]$' /usr/share/zoneinfo/tzdata.zi

RUN addgroup -S dosegram && adduser -S -u 10001 -G dosegram dosegram
COPY --from=build /build/out/app/assembly.dest/out.jar /opt/dosegram/dosegram.jar
# The rasteriser and the pinned font pack (VISUALS section 3.4) with its OFL
# licences. With both set the status images are drawn; without them every
# image is sent as its text equivalent.
COPY --from=resvg /usr/local/bin/resvg /opt/dosegram/bin/resvg
COPY report-renderer/fonts/ /opt/dosegram/fonts/
ENV RESVG_PATH=/opt/dosegram/bin/resvg \
    RENDER_FONTS_DIR=/opt/dosegram/fonts
COPY --from=miniapp /build/miniapp/dist /opt/dosegram/miniapp
ENV MINIAPP_STATIC_DIR=/opt/dosegram/miniapp
USER dosegram
EXPOSE 8080 8081

ENTRYPOINT [ \
  "java", \
  "-XX:+UseSerialGC", \
  "-Xms32m", \
  "-Xmx256m", \
  "-XX:+AutoCreateSharedArchive", \
  "-XX:SharedArchiveFile=/home/dosegram/dosegram.cds", \
  "-Djdk.timezone.zip.dir=/usr/share/zoneinfo", \
  "-jar", "/opt/dosegram/dosegram.jar" \
]
CMD ["run"]
