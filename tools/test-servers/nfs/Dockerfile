# A Synology-like NFSv3 server in user space: unfs3 registered with rpcbind on Alpine, exporting what the exports file
# says. Its MOUNT protocol answers EXPORT (showmount -e). Test-only.
FROM alpine:3.24.3@sha256:be2656061b536c1883c4034790e21d0a09c5aaaf51968b89368a3c0371ea2c9a

# unfs3 0.10 and rpcbind 1.2 of Alpine 3.24's branch.
RUN apk add --no-cache "unfs3~0.10" "rpcbind~1.2" \
    && apk info -v unfs3 rpcbind

# Synology's layout: the shared folders under /volume1, where compose.yaml mounts the samples. unfsd resolves each
# export as it reads the file, so the folders are made here to check it, and must be in the samples to start.
COPY exports /etc/exports
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 755 /usr/local/bin/entrypoint.sh \
    && mkdir -p /volume1/video /volume1/media /volume1/private \
    && unfsd -T -e /etc/exports

# unfsd refuses a path that exists but is not exported (MNT3ERR_ACCES) and says a path that does not exist is not there
# (MNT3ERR_NOENT). A Synology refuses both; so that a shared folder's name typed alone (video, media, photos, without
# /volume1) is refused as there (issue #59), those names exist at the root too, empty.
RUN mkdir -p /video /media /photos

EXPOSE 111 111/udp 892 892/udp 2049 2049/udp
# MOUNT (100005) and NFS (100003) version 3 answering over TCP, as Houselights asks them.
HEALTHCHECK --interval=2s --timeout=2s --retries=30 CMD rpcinfo -t 127.0.0.1 100005 3 && rpcinfo -t 127.0.0.1 100003 3 || exit 1
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
