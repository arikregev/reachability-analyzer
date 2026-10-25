# source-fetch image (architecture.md 3.3, D-036): git, tar with zstd, curl. Runs as an arbitrary UID with a
# read-only root filesystem; /work, /tmp and $HOME are emptyDir volumes.
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.6

RUN microdnf install -y --nodocs git-core tar zstd findutils \
    && microdnf clean all \
    && mkdir -p /work /home/reach && chgrp -R 0 /work /home/reach && chmod -R g=u /work /home/reach

COPY --chmod=0755 fetch.sh /usr/local/bin/fetch.sh

ENV HOME=/home/reach
USER 1001
WORKDIR /work
ENTRYPOINT ["/usr/local/bin/fetch.sh"]
